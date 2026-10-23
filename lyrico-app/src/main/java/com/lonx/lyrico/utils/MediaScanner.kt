package com.lonx.lyrico.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.lonx.lyrico.data.model.SongFile
import com.lonx.lyrico.data.model.entity.FolderEntity
import java.util.Locale
import kotlin.math.abs

/** 整个媒体库（MediaStore 索引）的扫描结果。 */
data class MediaStoreScanResult(
    val songs: List<MediaStoreScannedSong>,
    /** 查询成功的卷根。调用方只能在这些卷根范围内判定「文件已不存在」 */
    val queriedRoots: Set<String>,
    val errorMessage: String? = null
) {
    /** 至少有一个卷查询成功；false 时不能据此删除已有记录 */
    val queried: Boolean get() = queriedRoots.isNotEmpty()
}

data class MediaStoreScannedSong(
    val songFile: SongFile,
    /** 卷根，例如 /storage/emulated/0 */
    val rootPath: String,
    /** 文件所在目录的绝对路径 */
    val folderPath: String
)

data class SafScanResult(
    val songs: List<SafScannedSongFile>,
    val successfulFolderIds: Set<Long>,
    val failedFolderIds: Set<Long>,
    val missingFolderIds: Set<Long>,
)

data class SafScannedSongFile(
    val songFile: SongFile,
    val rootFolderId: Long,
    val folderPath: String
)

class MediaScanner(
    private val context: Context,
) {

    private val tag = "MediaScanner"

    fun querySongsFromSafFolders(folders: List<FolderEntity>): SafScanResult {
        val results = mutableListOf<SafScannedSongFile>()
        val successfulFolderIds = mutableSetOf<Long>()
        val failedFolderIds = mutableSetOf<Long>()
        val missingFolderIds = mutableSetOf<Long>()
        val visitedDocumentKeys = mutableSetOf<String>()

        for (folder in folders) {
            val treeUriString = folder.treeUri

            if (treeUriString.isNullOrBlank()) {
                failedFolderIds.add(folder.id)
                continue
            }

            try {
                val treeUri = treeUriString.toUri()
                val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

                // 用一次查询验证根目录是否可访问。不要用 DocumentFile.exists()
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    rootDocumentId
                )

                if (!canQueryDocument(rootUri)) {
                    Log.w(tag, "SAF 根目录不可访问或已被删除: ${folder.path}, uri=$treeUriString")
                    missingFolderIds.add(folder.id)
                    continue
                }

                scanTreeByDocumentsContract(
                    treeUri = treeUri,
                    rootDocumentId = rootDocumentId,
                    displayRootPath = folder.path,
                    rootFolderId = folder.id,
                    output = results,
                    visitedDocumentKeys = visitedDocumentKeys
                )

                successfulFolderIds.add(folder.id)
            } catch (e: SecurityException) {
                Log.e(tag, "扫描 SAF 文件夹权限不足: ${folder.path}", e)
                failedFolderIds.add(folder.id)
            } catch (e: Exception) {
                Log.e(tag, "扫描 SAF 文件夹失败: ${folder.path}", e)
                failedFolderIds.add(folder.id)
            }
        }

        return SafScanResult(
            songs = results,
            successfulFolderIds = successfulFolderIds,
            failedFolderIds = failedFolderIds,
            missingFolderIds = missingFolderIds
        )
    }

    private fun scanTreeByDocumentsContract(
        treeUri: Uri,
        rootDocumentId: String,
        displayRootPath: String,
        rootFolderId: Long,
        output: MutableList<SafScannedSongFile>,
        visitedDocumentKeys: MutableSet<String>
    ) {
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(rootDocumentId to "")

        while (stack.isNotEmpty()) {
            val (parentDocumentId, currentRelativePath) = stack.removeLast()

            val children = queryChildren(
                treeUri = treeUri,
                parentDocumentId = parentDocumentId
            )

            for (child in children) {
                val name = child.displayName
                if (name.isBlank()) continue

                if (child.isDirectory) {
                    if (shouldSkipDirectory(name)) continue

                    val nextRelativePath = if (currentRelativePath.isBlank()) {
                        name
                    } else {
                        "$currentRelativePath/$name"
                    }

                    stack.add(child.documentId to nextRelativePath)
                    continue
                }

                if (!isSupportedAudioFile(name, child.mimeType)) continue

                // documentId 在同一个 treeUri 下稳定；这里比 uri 字符串更适合作去重 key
                val documentKey = "${treeUri}|${child.documentId}"
                if (!visitedDocumentKeys.add(documentKey)) continue

                val filePath = buildDisplayPath(
                    rootPath = displayRootPath,
                    relativePath = currentRelativePath,
                    fileName = name
                )

                output.add(
                    SafScannedSongFile(
                        rootFolderId = rootFolderId,
                        folderPath = buildDisplayFolderPath(
                            rootPath = displayRootPath,
                            relativePath = currentRelativePath
                        ),
                        songFile = SongFile(
                            mediaId = createVirtualMediaId(child.uri.toString()),
                            uri = child.uri,
                            filePath = filePath,
                            fileName = name,
                            lastModified = child.lastModified,
                            dateAdded = child.lastModified,
                            duration = 0L,
                            fileSize = child.size
                        )
                    )
                )
            }
        }
    }

    private fun queryChildren(
        treeUri: Uri,
        parentDocumentId: String
    ): List<SafDocumentRow> =
        SafDocuments.children(context, treeUri, parentDocumentId).orEmpty()

    private fun canQueryDocument(documentUri: Uri): Boolean {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        return try {
            context.contentResolver.query(
                documentUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                cursor.moveToFirst()
            } == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 查询整个媒体库。
     *
     * 与 [querySongsFromSafFolders] 的区别：这里读系统媒体库索引（MediaStore），
     * 不需要用户逐个目录授权，因此能覆盖 /Download 这类 Android 11+ 无法用 SAF 授权根目录的位置。
     */
    fun querySongsFromMediaStore(): MediaStoreScanResult {
        val songs = mutableListOf<MediaStoreScannedSong>()
        val queriedRoots = mutableSetOf<String>()
        var errorMessage: String? = null

        for (volume in externalVolumes()) {
            try {
                songs.addAll(queryMediaStoreVolume(volume))
                queriedRoots.add(volumeRootPath(volume))
            } catch (e: SecurityException) {
                Log.e(tag, "查询媒体库卷 $volume 权限不足", e)
                errorMessage = e.message ?: e::class.java.simpleName
            } catch (e: Exception) {
                Log.e(tag, "查询媒体库卷 $volume 失败", e)
                errorMessage = e.message ?: e::class.java.simpleName
            }
        }

        // 多卷之间可能重叠（同一文件被不同卷索引）
        val uniqueSongs = songs.distinctBy { it.songFile.filePath }
        Log.d(tag, "媒体库扫描：songs=${uniqueSongs.size}, roots=$queriedRoots")
        return MediaStoreScanResult(
            songs = uniqueSongs,
            queriedRoots = queriedRoots,
            errorMessage = errorMessage
        )
    }

    private fun externalVolumes(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return listOf(VOLUME_EXTERNAL)
        return try {
            MediaStore.getExternalVolumeNames(context).toList().ifEmpty { listOf(VOLUME_EXTERNAL) }
        } catch (e: Exception) {
            Log.w(tag, "获取外部存储卷失败，退回 external", e)
            listOf(VOLUME_EXTERNAL)
        }
    }

    private fun volumeRootPath(volume: String): String {
        if (volume == VOLUME_EXTERNAL || volume == VOLUME_EXTERNAL_PRIMARY) {
            // 主卷就是内置共享存储，用官方 API 拿绝对路径
            return Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        }
        // 可移动卷（SD 卡）的卷名就是卷 UUID
        return "/storage/$volume"
    }

    private fun queryMediaStoreVolume(volume: String): List<MediaStoreScannedSong> {
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val collection = if (useRelativePath) {
            MediaStore.Audio.Media.getContentUri(volume)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.MIME_TYPE)
            if (useRelativePath) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        val sortOrder = if (useRelativePath) {
            "${MediaStore.Audio.Media.RELATIVE_PATH} ASC, ${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
        } else {
            "${MediaStore.Audio.Media.DATA} ASC"
        }

        // 不按 IS_MUSIC 过滤：下载目录里的音频常被标成 0，正是需要纳管的文件
        val selection = "${MediaStore.Audio.Media.SIZE} > 0"
        val rootPath = volumeRootPath(volume)
        val result = mutableListOf<MediaStoreScannedSong>()

        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val pathIndex = if (useRelativePath) {
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            }

            while (cursor.moveToNext()) {
                val fileName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: continue
                if (!isSupportedAudioFile(fileName, cursor.getString(mimeIndex))) continue

                val mediaId = cursor.getLong(idIndex)
                val rawPath = cursor.getString(pathIndex).orEmpty().replace('\\', '/')
                val folderPath = if (useRelativePath) {
                    joinPath(rootPath, rawPath)
                } else if (rawPath.isNotBlank()) {
                    rawPath.substringBeforeLast('/', rootPath)
                } else {
                    rootPath
                }

                result.add(
                    MediaStoreScannedSong(
                        rootPath = rootPath,
                        folderPath = folderPath,
                        songFile = SongFile(
                            mediaId = mediaId,
                            uri = ContentUris.withAppendedId(collection, mediaId),
                            filePath = joinPath(folderPath, fileName),
                            fileName = fileName,
                            lastModified = cursor.getLong(modifiedIndex) * 1000L,
                            dateAdded = cursor.getLong(addedIndex) * 1000L,
                            duration = cursor.getLong(durationIndex),
                            fileSize = cursor.getLong(sizeIndex)
                        )
                    )
                )
            }
        }

        return result
    }

    private fun joinPath(parent: String, child: String): String {
        val left = parent.trimEnd('/')
        val right = child.trim('/')
        return when {
            right.isBlank() -> left
            left.isBlank() -> "/$right"
            else -> "$left/$right"
        }
    }

    private val supportedAudioExtensions = setOf(
        "mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma", "ape"
    )

    private fun shouldSkipDirectory(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return normalized.startsWith(".") ||
                normalized == "android" ||
                normalized == "data" ||
                normalized == "obb" ||
                normalized == "cache" ||
                normalized == "tmp"
    }

    private fun isSupportedAudioFile(fileName: String, mimeType: String?): Boolean {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)

        return extension in supportedAudioExtensions ||
                mimeType?.startsWith("audio/") == true
    }

    private fun buildDisplayPath(
        rootPath: String,
        relativePath: String,
        fileName: String
    ): String {
        return if (relativePath.isBlank()) {
            "${rootPath.trimEnd('/')}/$fileName"
        } else {
            "${rootPath.trimEnd('/')}/$relativePath/$fileName"
        }
    }

    private fun buildDisplayFolderPath(
        rootPath: String,
        relativePath: String
    ): String {
        return if (relativePath.isBlank()) {
            rootPath.trimEnd('/')
        } else {
            "${rootPath.trimEnd('/')}/$relativePath"
        }
    }

    private fun createVirtualMediaId(uriString: String): Long {
        val hash = uriString.hashCode().toLong()
        return -abs(hash).coerceAtLeast(1L)
    }

    private companion object {
        // 与 MediaStore.VOLUME_EXTERNAL / VOLUME_EXTERNAL_PRIMARY 等值，避免 minSdk 28 上的 API 依赖
        const val VOLUME_EXTERNAL = "external"
        const val VOLUME_EXTERNAL_PRIMARY = "external_primary"
    }
}

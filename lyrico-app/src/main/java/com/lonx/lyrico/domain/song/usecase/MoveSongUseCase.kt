package com.lonx.lyrico.domain.song.usecase

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.room.withTransaction
import androidx.room.withTransaction
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.utils.OrganizePlanner
import com.lonx.lyrico.utils.SafDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 目录整理（V0.09）的单文件移动：
 * 复制 → 校验字节数 → 删源。刻意不用 DocumentsContract.moveDocument——
 * 部分系统（实测 MIUI）的 moveDocument 是「复制+删除」，删除失败时仍返回新 URI，
 * 会留下重复文件；复制+校验+删源的失败模式是「源文件保留、新文件已清掉」，更安全。
 */
class MoveSongUseCase(
    private val context: Context,
    private val database: LyricoDatabase
) {
    suspend operator fun invoke(
        song: SongEntity,
        plan: OrganizePlanner.PlanItem,
        folder: FolderEntity,
        dirIdByPath: MutableMap<String, String> = mutableMapOf()
    ): MoveSongResult = withContext(Dispatchers.IO) {
        runCatching { move(song, plan, folder, dirIdByPath) }.getOrElse { MoveSongResult.Failed(it) }
    }

    private suspend fun move(
        song: SongEntity,
        plan: OrganizePlanner.PlanItem,
        folder: FolderEntity,
        dirIdByPath: MutableMap<String, String>
    ): MoveSongResult {
        val treeUri = folder.treeUri?.toUri()
            ?: return MoveSongResult.Failed(IllegalStateException("文件夹缺少 treeUri"))
        val source = song.uri.toUri()

        // ① 逐级解析/创建「艺人/专辑」目录（带缓存：同一目标目录只列一次）
        val targetDocId = resolveTargetDirId(treeUri, plan, dirIdByPath)
            ?: return MoveSongResult.Failed(IllegalStateException("无法访问目标目录"))
        val targetDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)

        // ② 目标目录里的同名冲突 → 跳过（不覆盖已有文件）
        val children = SafDocuments.children(context, treeUri, targetDocId)
            ?: return MoveSongResult.Failed(IllegalStateException("无法列出目标目录"))
        if (children.any { !it.isDirectory && it.displayName == song.fileName }) {
            return MoveSongResult.NameConflict(song.fileName)
        }

        // ③ 复制（staged）
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            song.fileName.substringAfterLast('.', "").lowercase()
        ) ?: "application/octet-stream"
        val dest = DocumentsContract.createDocument(
            context.contentResolver, targetDirUri, mime, song.fileName
        ) ?: return MoveSongResult.Failed(IllegalStateException("创建目标文件失败"))

        val expectedSize = if (song.fileSize > 0) song.fileSize else statSize(source)
        val copied = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                context.contentResolver.openOutputStream(dest, "wt")?.use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                } ?: throw IllegalStateException("无法写入目标文件")
            } ?: throw IllegalStateException("无法读取源文件")
        }

        val sizeOk = copied.getOrNull()?.let { copiedBytes ->
            expectedSize <= 0 || copiedBytes == expectedSize
        } == true

        if (copied.isFailure || !sizeOk) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, dest) }
            return MoveSongResult.Failed(
                copied.exceptionOrNull() ?: IllegalStateException("复制不完整（${copied.getOrNull()}/$expectedSize 字节）")
            )
        }

        // ④ 复制完整才删源；删不掉则回滚新文件，不重复
        val sourceDeleted = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, source)
        }.getOrDefault(false)
        if (!sourceDeleted) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, dest) }
            return MoveSongResult.Failed(IllegalStateException("删除源文件失败（已回滚）"))
        }

        // ⑤ 更新库记录（fileName 不变，uri/filePath 变）
        val newUri = dest.toString()
        val updated = song.copy(
            uri = newUri,
            filePath = plan.newFilePath,
            fileLastModified = System.currentTimeMillis()
        )
        return try {
            database.withTransaction {
                database.songDao().update(updated)
            }
            MoveSongResult.Success(updated, song.uri)
        } catch (e: Exception) {
            MoveSongResult.Failed(e)
        }
    }

    /** 逐级下钻/创建目录，返回最终目录的 documentId。dirIdByPath 缓存绝对目录路径 → docId。 */
    private fun resolveTargetDirId(
        treeUri: Uri,
        plan: OrganizePlanner.PlanItem,
        dirIdByPath: MutableMap<String, String>
    ): String? {
        var docId = dirIdByPath[plan.rootPath]
            ?: DocumentsContract.getTreeDocumentId(treeUri).also { dirIdByPath[plan.rootPath] = it }

        for (segment in plan.targetDirRelative.split('/').filter { it.isNotBlank() }) {
            val nextPath = OrganizePlanner.joinPath(
                dirIdByPath.entries.firstOrNull { it.value == docId }?.key ?: plan.rootPath,
                segment
            )
            val cached = dirIdByPath[nextPath]
            if (cached != null) {
                docId = cached
                continue
            }
            val children = SafDocuments.children(context, treeUri, docId) ?: return null
            val existing = children.firstOrNull { it.isDirectory && it.displayName == segment }
            docId = if (existing != null) {
                existing.documentId
            } else {
                val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val created = DocumentsContract.createDocument(
                    context.contentResolver, dirUri, DocumentsContract.Document.MIME_TYPE_DIR, segment
                ) ?: return null
                DocumentsContract.getDocumentId(created)
            }
            dirIdByPath[nextPath] = docId
        }
        return docId
    }

    private fun statSize(uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L)
}

sealed interface MoveSongResult {
    data class Success(val song: SongEntity, val oldUri: String) : MoveSongResult
    data class NameConflict(val targetName: String) : MoveSongResult
    data class Failed(val throwable: Throwable) : MoveSongResult
}

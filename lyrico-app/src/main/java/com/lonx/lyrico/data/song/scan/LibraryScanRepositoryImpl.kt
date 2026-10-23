package com.lonx.lyrico.data.song.scan

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.lonx.audiotag.model.CustomTagField
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.SongFile
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.mapper.SongMetadataMapper
import com.lonx.lyrico.data.song.search.LyricFtsIndexer
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.utils.MediaScanner
import com.lonx.lyrico.utils.LyricsSearchTextExtractor
import com.lonx.lyrico.utils.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LibraryScanRepositoryImpl(
    private val context: Context,
    private val database: LyricoDatabase,
    private val mediaScanner: MediaScanner,
    private val settingsRepository: SettingsRepository,
    private val audioTagRepository: AudioTagRepository,
    private val songMetadataMapper: SongMetadataMapper,
    private val libraryIndexRepository: LibraryIndexRepository,
    private val appLogRepository: AppLogRepository
) : LibraryScanRepository {

    private val songDao = database.songDao()
    private val folderDao = database.folderDao()
    private val syncMutex = Mutex()

    override suspend fun synchronize(
        request: LibraryScanRequest,
        onProgress: suspend (LibraryScanProgress) -> Unit
    ): LibraryScanResult {
        return syncMutex.withLock {
            withContext(Dispatchers.IO) {
                synchronizeLocked(request, onProgress)
            }
        }
    }

    private suspend fun synchronizeLocked(
        request: LibraryScanRequest,
        onProgress: suspend (LibraryScanProgress) -> Unit
    ): LibraryScanResult {
        try {
            Log.d(
                TAG,
                "Start library sync: fullRescan=${request.fullRescan}, " +
                    "scanMediaStore=${request.scanMediaStore}, folderIds=${request.folderIds}"
            )
            onProgress(LibraryScanProgress(stage = LibraryScanStage.LISTING_FILES))

            val removedSafFolders = if (request.removeMissingSafFolders) {
                removeSafFoldersWithoutPermission()
            } else {
                0
            }
            val dbSyncInfos = songDao.getAllSyncInfo()
            val dbSongMap = dbSyncInfos.associateBy { it.uri }
            val dbSongByPath = dbSyncInfos.groupBy { normalizePath(it.filePath) }

            val minDuration = 60_000L
            val indexLyrics = settingsRepository.lyricIndexEnabled.first()
            val unchangedSongUris = mutableListOf<String>()
            val failures = mutableListOf<LibraryScanFailure>()
            val songsToUpsert = mutableListOf<ScannedSongMetadata>()

            val safFolders = if (request.folderIds == null) {
                folderDao.getSafFolders()
            } else {
                folderDao.getScanRootFoldersFor(request.folderIds)
            }
            val safFolderById = safFolders.associateBy { it.id }
            val safScanResult = mediaScanner.querySongsFromSafFolders(safFolders)

            // 整库扫描只在「全库扫描」时生效：单目录刷新若也走媒体库，会误判其它目录的歌曲已删除
            val mediaStoreResult = if (request.scanMediaStore && request.folderIds == null) {
                mediaScanner.querySongsFromMediaStore()
            } else {
                null
            }
            if (mediaStoreResult != null && !mediaStoreResult.queried) {
                Log.w(TAG, "媒体库查询未成功: ${mediaStoreResult.errorMessage}")
            }

            val ignoredPaths = folderDao.getAllFoldersOnce()
                .filter { it.isIgnored }
                .map { normalizePath(it.path) }
                .filter { it.isNotBlank() }

            // 媒体库结果优先；同一路径只保留一条候选项，避免 SAF 与媒体库重复入库
            val candidates = mutableListOf<ScanCandidate>()
            val claimedPaths = mutableSetOf<String>()
            mediaStoreResult?.songs?.forEach { scanned ->
                val pathKey = normalizePath(scanned.songFile.filePath)
                if (isUnderIgnoredPath(pathKey, ignoredPaths)) return@forEach
                if (!claimedPaths.add(pathKey)) return@forEach
                candidates.add(
                    ScanCandidate(
                        songFile = scanned.songFile,
                        rootPath = scanned.rootPath,
                        folderPath = scanned.folderPath,
                        source = SOURCE_MEDIA_STORE,
                        isIgnored = false
                    )
                )
            }
            safScanResult.songs.forEach { scanned ->
                val root = safFolderById[scanned.rootFolderId]
                val pathKey = normalizePath(scanned.songFile.filePath)
                if (!claimedPaths.add(pathKey)) return@forEach
                candidates.add(
                    ScanCandidate(
                        songFile = scanned.songFile,
                        rootPath = root?.path ?: scanned.folderPath,
                        folderPath = scanned.folderPath,
                        source = SOURCE_SAF,
                        isIgnored = root?.isIgnored ?: false
                    )
                )
            }

            onProgress(
                LibraryScanProgress(
                    stage = LibraryScanStage.READING_METADATA,
                    current = 0,
                    total = candidates.size
                )
            )

            val deviceUris = mutableSetOf<String>()
            val impactedFolderIds = mutableSetOf<Long>()
            /** 本次写入复用了既有记录的主键；这些记录换了 uri，不能按旧 uri 判删 */
            val reusedIds = mutableSetOf<Long>()
            /** 同一文件的冗余记录（例如旧 SAF uri 与新媒体库 uri 并存），写入后清掉 */
            val mergedAwayUris = mutableSetOf<String>()
            val folderIdCache = mutableMapOf<String, Long>()

            for ((index, candidate) in candidates.withIndex()) {
                val deviceSong = candidate.songFile
                try {
                    val deviceUriString = deviceSong.uri.toString()
                    val pathKey = normalizePath(deviceSong.filePath)
                    val samePathInfos = dbSongByPath[pathKey].orEmpty()
                    // 先按 uri 命中；再按路径命中，就把旧记录（例如 SAF 时代的）接管过来
                    val dbInfo = dbSongMap[deviceUriString]
                        ?: samePathInfos.firstOrNull { it.source == SOURCE_MEDIA_STORE }
                        ?: samePathInfos.firstOrNull()

                    val needsUpdate = request.fullRescan ||
                        dbInfo == null ||
                        dbInfo.uri != deviceUriString ||
                        dbInfo.source != candidate.source ||
                        dbInfo.fileLastModified != deviceSong.lastModified ||
                        dbInfo.fileSize != deviceSong.fileSize ||
                        dbInfo.filePath != deviceSong.filePath
                    val knownDuration = when {
                        deviceSong.duration > 0L -> deviceSong.duration
                        dbInfo != null && !needsUpdate -> dbInfo.durationMilliseconds.toLong()
                        else -> 0L
                    }

                    if (
                        request.ignoreShortAudio &&
                        knownDuration > 0L &&
                        knownDuration <= minDuration
                    ) {
                        continue
                    }

                    if (needsUpdate) {
                        dbInfo?.let { reusedIds.add(it.id) }
                        if (dbInfo != null && dbInfo.uri != deviceUriString) {
                            mergedAwayUris.add(dbInfo.uri)
                        }
                        samePathInfos
                            .filter { it.id != (dbInfo?.id ?: 0L) }
                            .forEach { mergedAwayUris.add(it.uri) }

                        val folderId = resolveFolderId(candidate, folderIdCache)
                        impactedFolderIds.add(folderId)

                        val metadata = extractSongMetadata(
                            songFile = deviceSong,
                            folderId = folderId,
                            existingId = dbInfo?.id ?: 0L,
                            source = candidate.source,
                            indexLyrics = indexLyrics
                        )

                        if (
                            request.ignoreShortAudio &&
                            metadata != null &&
                            metadata.entity.durationMilliseconds > 0 &&
                            metadata.entity.durationMilliseconds <= minDuration
                        ) {
                            continue
                        }

                        metadata?.let(songsToUpsert::add)
                    } else if (indexLyrics) {
                        unchangedSongUris.add(deviceUriString)
                    }

                    deviceUris.add(deviceUriString)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process song: ${deviceSong.fileName}", e)
                    failures.add(
                        LibraryScanFailure(
                            uri = deviceSong.uri.toString(),
                            fileName = deviceSong.fileName,
                            stage = LibraryScanFailureStage.ReadingMetadata,
                            message = e.message ?: e::class.java.simpleName,
                            throwable = e
                        )
                    )
                } finally {
                    onProgress(
                        LibraryScanProgress(
                            stage = LibraryScanStage.READING_METADATA,
                            current = index + 1,
                            total = candidates.size,
                            currentFile = deviceSong.fileName
                        )
                    )
                }
            }

            val missingSafFolderIds = safScanResult.missingFolderIds
            val successfulScannedFolderIds = folderDao
                .getFolderTreeIds(safScanResult.successfulFolderIds)
                .toSet()
            val mediaStoreRoots = mediaStoreResult?.queriedRoots.orEmpty()

            val deletedUris = dbSyncInfos
                .filter { info ->
                    if (info.uri in deviceUris) return@filter false
                    if (info.id in reusedIds) return@filter false
                    when (info.source) {
                        SOURCE_SAF -> info.folderId in successfulScannedFolderIds
                        // 只在确实查询成功的卷根范围内判删，避免部分卷查询失败导致误删
                        SOURCE_MEDIA_STORE -> mediaStoreRoots.any { root ->
                            normalizePath(info.filePath).startsWith("$root/")
                        }
                        else -> false
                    }
                }
                .map { it.uri }
                .toSet()

            val missingFolderSongUris = dbSyncInfos
                .filter { info -> info.source == SOURCE_SAF && info.folderId in missingSafFolderIds }
                .map { it.uri }
                .toSet()

            val allDeletedUris = deletedUris + missingFolderSongUris + mergedAwayUris
            if (allDeletedUris.isNotEmpty()) {
                impactedFolderIds.addAll(
                    dbSyncInfos
                        .filter { it.uri in allDeletedUris }
                        .map { it.folderId }
                )
            }
            impactedFolderIds.addAll(missingSafFolderIds)

            val lyricsToIndex = unchangedSongUris.chunked(BATCH_SIZE).flatMap { uris ->
                songDao.getSongLyricsMissingIndex(uris).map { row ->
                    row.copy(lyricSearchText = LyricsSearchTextExtractor.toSearchText(row.lyrics).orEmpty())
                }
            }
            val databaseChanges = songsToUpsert.size + allDeletedUris.size +
                missingSafFolderIds.size + lyricsToIndex.size
            onProgress(
                LibraryScanProgress(
                    stage = LibraryScanStage.WRITING_DATABASE,
                    current = 0,
                    total = databaseChanges
                )
            )

            database.withTransaction {
                songsToUpsert.chunked(BATCH_SIZE).forEach { chunk ->
                    val songs = chunk.map { it.entity }
                    songDao.upsertAll(songs)
                    if (indexLyrics) {
                        LyricFtsIndexer.replaceSongs(songDao, songs)
                    } else {
                        songDao.deleteLyricFtsByUris(songs.map { it.uri })
                    }
                    chunk.forEach { metadata ->
                        database.songCustomTagKeyDao().replaceForSong(
                            songUri = metadata.entity.uri,
                            keys = metadata.customFields.mapNotNull { field ->
                                field.key.trim().takeIf { it.isNotBlank() }?.uppercase()
                            }
                        )
                    }
                }

                lyricsToIndex.chunked(BATCH_SIZE).forEach { rows ->
                    rows.forEach { row ->
                        songDao.updateLyricSearchText(row.uri, row.lyricSearchText)
                    }
                    LyricFtsIndexer.replaceSongLyrics(songDao, rows)
                }

                allDeletedUris.chunked(BATCH_SIZE).forEach { chunk ->
                    songDao.deleteByUris(chunk.toList())
                    songDao.deleteLyricFtsByUris(chunk.toList())
                    database.songCustomTagKeyDao().deleteForSongs(chunk.toList())
                }

                missingSafFolderIds.forEach { folderId ->
                    folderDao.deleteFolderTreePermanently(folderId)
                }

                impactedFolderIds
                    .filterNot { it in missingSafFolderIds }
                    .forEach { folderId -> folderDao.refreshSongCount(folderId) }

                folderDao.performPostScanCleanup()
            }

            if (songsToUpsert.isNotEmpty()) {
                val indexedSongs = songDao.getSongsByUris(songsToUpsert.map { it.entity.uri })
                libraryIndexRepository.reindexSongs(indexedSongs)
            }
            if (allDeletedUris.isNotEmpty() || missingSafFolderIds.isNotEmpty()) {
                libraryIndexRepository.refreshAndPruneIndexes()
            }

            onProgress(
                LibraryScanProgress(
                    stage = LibraryScanStage.WRITING_DATABASE,
                    current = databaseChanges,
                    total = databaseChanges
                )
            )

            settingsRepository.saveLastScanTime(System.currentTimeMillis())
            val result = LibraryScanResult(
                scanned = candidates.size,
                inserted = songsToUpsert.count { it.isInsert },
                updated = songsToUpsert.count { !it.isInsert },
                deleted = allDeletedUris.size,
                skipped = candidates.size - songsToUpsert.size,
                failures = failures
            )
            logResult(request, result, removedSafFolders, impactedFolderIds.size)
            onProgress(LibraryScanProgress(stage = LibraryScanStage.FINISHED))
            return result
        } catch (e: Exception) {
            logException("Library synchronization failed", e)
            throw e
        }
    }

    private suspend fun resolveFolderId(
        candidate: ScanCandidate,
        cache: MutableMap<String, Long>
    ): Long {
        val cacheKey = normalizePath(candidate.folderPath)
        cache[cacheKey]?.let { return it }
        val folderId = folderDao.upsertScannedFolderTreeAndGetLeafId(
            rootPath = candidate.rootPath,
            folderPath = candidate.folderPath,
            isIgnored = candidate.isIgnored
        )
        cache[cacheKey] = folderId
        return folderId
    }

    private fun normalizePath(path: String): String {
        val normalized = path.replace('\\', '/').trim().trimEnd('/')
        return normalized.ifBlank { path.trim() }
    }

    private fun isUnderIgnoredPath(path: String, ignoredPaths: Collection<String>): Boolean {
        return ignoredPaths.any { ignored ->
            ignored.isNotBlank() && (path == ignored || path.startsWith("$ignored/"))
        }
    }

    private suspend fun removeSafFoldersWithoutPermission(): Int {
        val foldersWithoutPermission = folderDao.getSafFoldersForPermissionCheck()
            .filterNot { folder ->
                UriUtils.hasPersistedReadPermission(context, folder.treeUri)
            }
        val folderIds = foldersWithoutPermission.map { it.id }
        if (folderIds.isEmpty()) return 0
        database.withTransaction {
            folderIds.forEach { folderId ->
                folderDao.deleteFolderTreePermanently(folderId)
            }
        }
        return folderIds.size
    }

    private suspend fun extractSongMetadata(
        songFile: SongFile,
        folderId: Long,
        existingId: Long,
        source: String,
        indexLyrics: Boolean
    ): ScannedSongMetadata? {
        val audioData = audioTagRepository.read(
            uri = songFile.uri.toString(),
            options = AudioTagReadOptions(
                multiValueSeparator = settingsRepository.separator.first()
            )
        )
        val entity = songMetadataMapper.fromScannedFile(
            file = songFile,
            tag = audioData,
            folderId = folderId,
            existingId = existingId,
            source = source,
            indexLyrics = indexLyrics
        )
        return ScannedSongMetadata(
            entity = entity,
            customFields = audioData.customFields,
            isInsert = existingId == 0L
        )
    }

    private suspend fun logResult(
        request: LibraryScanRequest,
        result: LibraryScanResult,
        removedSafFolders: Int,
        foldersUpdated: Int
    ) {
        try {
            appLogRepository.log(
                level = if (result.failures.isEmpty()) AppLogLevel.INFO else AppLogLevel.WARNING,
                type = AppLogType.APP,
                tag = TAG,
                message = "Library synchronization finished",
                detail = buildString {
                    appendLine("fullRescan=${request.fullRescan}")
                    appendLine("scanMediaStore=${request.scanMediaStore}")
                    appendLine("scanned=${result.scanned}")
                    appendLine("inserted=${result.inserted}")
                    appendLine("updated=${result.updated}")
                    appendLine("deleted=${result.deleted}")
                    appendLine("removedSafFolders=$removedSafFolders")
                    appendLine("foldersUpdated=$foldersUpdated")
                    appendLine("failures=${result.failures.size}")
                    appendLine("ignoreShortAudio=${request.ignoreShortAudio}")
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write scan log", e)
        }
    }

    private suspend fun logException(message: String, throwable: Throwable) {
        try {
            appLogRepository.logException(
                type = AppLogType.APP,
                tag = TAG,
                message = message,
                throwable = throwable
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write scan exception log", e)
        }
    }

    private data class ScanCandidate(
        val songFile: SongFile,
        val rootPath: String,
        val folderPath: String,
        val source: String,
        val isIgnored: Boolean
    )

    private data class ScannedSongMetadata(
        val entity: SongEntity,
        val customFields: List<CustomTagField>,
        val isInsert: Boolean
    )

    private companion object {
        const val TAG = "LibraryScanRepository"
        const val BATCH_SIZE = 50
        const val SOURCE_SAF = "SAF"
        const val SOURCE_MEDIA_STORE = "MEDIA_STORE"
    }
}

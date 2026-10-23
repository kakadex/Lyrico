package com.lonx.lyrico.worker.processor

import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity
import com.lonx.lyrico.data.model.match.ApplyMatchPreviewTaskConfig
import com.lonx.lyrico.data.model.match.MatchPreviewEntry
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.match.toPatch
import com.lonx.lyrico.data.model.match.valueOrNullOf
import com.lonx.lyrico.data.repository.UndoItem
import com.lonx.lyrico.data.repository.UndoRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.domain.song.usecase.PatchSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsResult
import kotlinx.serialization.json.Json

/**
 * 第二阶段：把用户在预览页勾选的字段真正写进文件。
 *
 * 写入内容全部来自配置里内联的 [ApplyMatchPreviewTaskConfig.entries]，
 * 不再联网——所以这一步是纯本地写盘，失败原因只会是文件权限或格式。
 *
 * V0.06：写盘前先抓取每个字段的「当前值」作为撤销旧值，按 [BatchTaskEntity.taskId]
 * 登记进撤销表；之后用户可在历史里整批回滚。封面（COVER）按 MusicOrganizer 约定
 * 不进撤销链路。
 */
class ApplyMatchPreviewProcessor(
    private val patchSongTagsUseCase: PatchSongTagsUseCase,
    private val songLibraryRepository: SongLibraryRepository,
    private val audioTagRepository: AudioTagRepository,
    private val undoRepository: UndoRepository
) : BatchTaskProcessor {

    override suspend fun process(
        task: BatchTaskEntity,
        item: BatchTaskItemEntity,
        onProgress: suspend (Float) -> Unit
    ): BatchTaskProcessResult {
        val config = task.configJson?.let {
            Json { ignoreUnknownKeys = true }.decodeFromString<ApplyMatchPreviewTaskConfig>(it)
        } ?: throw BatchTaskSkippedException("No config")

        val entries = config.entries[item.songUri].orEmpty()
        if (entries.isEmpty()) throw BatchTaskSkippedException("Nothing selected")

        val song = songLibraryRepository.getSongByUri(item.songUri)
            ?: throw BatchTaskSkippedException("Song not found")

        val undoItems = buildUndoItems(item.songUri, entries)

        onProgress(0.3f)
        val patch = entries.toPatch()
        onProgress(0.6f)

        val result = patchSongTagsUseCase(song.uri, patch)
        if (result !is SaveAudioTagsResult.Success) throw Exception("Write failed")

        if (undoItems.isNotEmpty()) {
            undoRepository.recordBatch(
                batchId = task.taskId,
                kind = "BATCH_MATCH",
                label = "批量匹配写入 · ${undoItems.size} 项",
                items = undoItems
            )
        }

        onProgress(1f)
        return BatchTaskProcessResult()
    }

    /**
     * 读取写入前各字段的当前值，作为撤销旧值。
     * 读标签失败（如文件已不在）则跳过该首的撤销记录，不影响本次写入。
     */
    private suspend fun buildUndoItems(
        songUri: String,
        entries: List<MatchPreviewEntry>
    ): List<UndoItem> {
        val current = runCatching { audioTagRepository.read(songUri) }.getOrNull() ?: return emptyList()
        return entries.mapNotNull { entry ->
            if (entry.target == MetadataFieldTarget.COVER) return@mapNotNull null
            UndoItem(
                songUri = songUri,
                field = entry.target,
                beforeValue = current.valueOrNullOf(entry.target),
                afterValue = entry.newValue.takeIf { it.isNotBlank() }
            )
        }
    }
}

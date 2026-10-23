package com.lonx.lyrico.data.repository

import androidx.room.withTransaction
import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.dao.UndoDao
import com.lonx.lyrico.data.model.entity.UndoBatchEntity
import com.lonx.lyrico.data.model.entity.UndoItemEntity
import com.lonx.lyrico.data.model.match.withValue
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.domain.song.usecase.PatchSongTagsUseCase

/** 单条撤销项：写入前某字段的旧值。 */
data class UndoItem(
    val songUri: String,
    val field: MetadataFieldTarget,
    val beforeValue: String?,
    val afterValue: String?
)

sealed interface RollbackResult {
    data class Success(val songCount: Int) : RollbackResult
    data class Failed(val error: Throwable) : RollbackResult
}

interface UndoRepository {
    /** 记录一次批量写入的「前态」，供日后整批回滚。items 为空则跳过。 */
    suspend fun recordBatch(batchId: String, kind: String, label: String, items: List<UndoItem>)

    /** 按 batchId 整批回滚：把 each 字段写回 beforeValue，成功后清除记录。 */
    suspend fun rollback(batchId: String): RollbackResult

    /** 列出尚未回滚的撤销批次（供 UI 展示历史）。 */
    suspend fun listBatches(): List<UndoBatchEntity>
}

class UndoRepositoryImpl(
    private val undoDao: UndoDao,
    private val patchSongTagsUseCase: PatchSongTagsUseCase,
    private val database: LyricoDatabase
) : UndoRepository {

    override suspend fun recordBatch(batchId: String, kind: String, label: String, items: List<UndoItem>) {
        if (items.isEmpty()) return
        val entities = items.map { item ->
            UndoItemEntity(
                batchId = batchId,
                songUri = item.songUri,
                field = item.field.name,
                beforeValue = item.beforeValue,
                afterValue = item.afterValue
            )
        }
        database.withTransaction {
            undoDao.insertBatch(UndoBatchEntity(batchId = batchId, kind = kind, label = label))
            undoDao.insertItems(entities)
        }
    }

    override suspend fun listBatches(): List<UndoBatchEntity> = undoDao.listBatches()

    override suspend fun rollback(batchId: String): RollbackResult = runCatching {
        val items = undoDao.getItems(batchId)
        if (items.isEmpty()) return@runCatching RollbackResult.Success(0)

        // 同一首歌的字段合并成一次补丁，避免重复写盘。
        items.groupBy { it.songUri }.forEach { (songUri, songItems) ->
            var data = AudioTagData()
            for (item in songItems) {
                val target = runCatching { MetadataFieldTarget.valueOf(item.field) }.getOrNull()
                    ?: return@forEach
                data = data.withValue(target, item.beforeValue ?: "")
            }
            patchSongTagsUseCase(songUri, data)
        }

        val songCount = items.distinctBy { it.songUri }.size
        undoDao.deleteItems(batchId)
        undoDao.deleteBatch(batchId)
        RollbackResult.Success(songCount)
    }.getOrElse { RollbackResult.Failed(it) }
}

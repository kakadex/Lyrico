package com.lonx.lyrico.data.model.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次可撤销的批量写入（对应 MusicOrganizer 的 Journal 批次）。
 * batchId 复用 [BatchTaskEntity.taskId]，单文件编辑可另起 UUID。
 */
@Entity(tableName = "undo_batches")
data class UndoBatchEntity(
    @PrimaryKey val batchId: String,
    val kind: String,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
    val rolledBack: Boolean = false
)

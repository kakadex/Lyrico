package com.lonx.lyrico.data.model.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 撤销批次里的一条「字段旧值」记录，主键 (batchId, songUri, field) 保证幂等写入。
 * beforeValue 为 null/空串都表示「该字段写入前为空」；回滚时统一写回空串以清空。
 * 封面（COVER）不进撤销链路（与 MusicOrganizer 约定一致：封面写入不可整批撤销）。
 */
@Entity(
    tableName = "undo_items",
    primaryKeys = ["batchId", "songUri", "field"],
    indices = [Index("batchId")]
)
data class UndoItemEntity(
    val batchId: String,
    val songUri: String,
    val field: String,
    val beforeValue: String?,
    val afterValue: String?,
    val createdAt: Long = System.currentTimeMillis()
)

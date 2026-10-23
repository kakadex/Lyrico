package com.lonx.lyrico.data.model.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lonx.lyrico.data.model.entity.UndoBatchEntity
import com.lonx.lyrico.data.model.entity.UndoItemEntity

@Dao
interface UndoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: UndoBatchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<UndoItemEntity>)

    @Query("SELECT * FROM undo_batches ORDER BY createdAt DESC")
    suspend fun listBatches(): List<UndoBatchEntity>

    @Query("SELECT * FROM undo_batches WHERE batchId = :batchId")
    suspend fun getBatch(batchId: String): UndoBatchEntity?

    @Query("SELECT COUNT(*) FROM undo_items WHERE batchId = :batchId")
    suspend fun countItems(batchId: String): Int

    @Query("SELECT * FROM undo_items WHERE batchId = :batchId")
    suspend fun getItems(batchId: String): List<UndoItemEntity>

    @Query("DELETE FROM undo_items WHERE batchId = :batchId")
    suspend fun deleteItems(batchId: String)

    @Query("DELETE FROM undo_batches WHERE batchId = :batchId")
    suspend fun deleteBatch(batchId: String)
}

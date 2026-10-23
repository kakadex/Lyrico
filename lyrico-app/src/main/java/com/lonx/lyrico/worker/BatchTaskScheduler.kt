package com.lonx.lyrico.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lonx.lyrico.data.repository.BatchTaskRepository

class BatchTaskScheduler(
    private val context: Context,
    private val taskRepository: BatchTaskRepository
) {
    suspend fun enqueue(taskId: String) {
        val request = OneTimeWorkRequestBuilder<BatchTaskWorker>()
            .setInputData(
                Data.Builder()
                    .putString(BatchTaskWorker.KEY_TASK_ID, taskId)
                    .build()
            )
            .addTag("batch_task")
            .addTag("batch_task:$taskId")
            .build()

        taskRepository.bindWorkId(taskId, request.id.toString())

        WorkManager.getInstance(context).enqueueUniqueWork(
            "batch_task_$taskId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(taskId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag("batch_task:$taskId")
    }
}

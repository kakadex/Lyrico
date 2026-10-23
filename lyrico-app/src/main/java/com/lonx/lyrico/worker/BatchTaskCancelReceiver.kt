package com.lonx.lyrico.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.lonx.lyrico.data.repository.BatchTaskRepository

class BatchTaskCancelReceiver : BroadcastReceiver(), KoinComponent {

    private val batchTaskRepository: BatchTaskRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BatchTaskNotification.ACTION_CANCEL) {
            val taskId = intent.getStringExtra("task_id") ?: return
            WorkManager.getInstance(context).cancelAllWorkByTag("batch_task:$taskId")
            CoroutineScope(Dispatchers.IO).launch {
                batchTaskRepository.markCancelled(taskId)
            }
        }
    }
}

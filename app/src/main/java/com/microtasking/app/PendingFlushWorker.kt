// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-24, after version v0.2.0-81 main 2026-09-24
package com.microtasking.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * The one background job in the synchronization design: when a flush of the pending-changes queue
 * leaves entries behind (offline, or the Web App had trouble), this flushes it again as soon as the
 * network is back - even if MicroTasking isn't open - and sends the cross-app messages for whatever
 * the Sheet accepts. One-shot, not periodic, and it shows no notification; it only asks to be run
 * again ([Result.retry], which WorkManager backs off) while entries remain.
 */
class PendingFlushWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // No Web App configured any more: there is nothing this job can ever send, so don't keep
        // asking to be retried. The next sync (or saving a URL) picks the queue back up.
        val webAppUrl = TaskDelivery.prefs(applicationContext).getString("web_app_url", "") ?: ""
        if (webAppUrl.isBlank()) return Result.success()
        val remaining = PendingChanges.flushToSheet(applicationContext)
        return if (remaining > 0) Result.retry() else Result.success()
    }
}

object PendingFlushScheduler {
    private const val UNIQUE_WORK_NAME = "pending_changes_flush"

    /**
     * Queues (replacing any already queued) a one-shot flush that waits for a network connection.
     * Never throws - a failure to schedule just means the next sync flushes instead.
     */
    fun schedule(context: Context) {
        runCatching {
            val request = OneTimeWorkRequest.Builder(PendingFlushWorker::class.java)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

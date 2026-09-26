// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-25, after version v0.2.0-84 main 2026-09-25
package com.microtasking.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives ActiveTasks's messages (see TaskEvents.kt for the contract). Declared in the manifest
 * `exported="true"` but guarded by the signature-level [TaskEventContract.PERMISSION], so only an
 * app signed with the same key can reach it. Works whether MicroTasking is on screen, in the
 * background, or not running at all - Android starts the process just for the broadcast.
 *
 * Deliberately tiny: decode, write the one change into the saved task list, done. No network call
 * and no Sheet read here - the next sync (every time the app becomes visible) confirms it. If the
 * app is open, its SharedPreferences listener sees the write and updates the screen in place.
 */
class TaskEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val event = parseTaskEvent(intent) ?: return
        val prefs = TaskDelivery.prefs(context)
        // One receiver call at a time, and never interleaved with another thread's read-modify-write
        // of the same key from inside this process.
        synchronized(TaskEventReceiver::class.java) {
            val tasks = readManagedTasks(prefs.getString("managed_tasks", "[]") ?: "[]")
            val updated = applyIncomingEvent(tasks, event)
            val changed = updated != tasks
            if (changed) prefs.edit().putString("managed_tasks", writeManagedTasks(updated)).apply()
            // The Sheet row is gone, but a sync that catches a stale/lagged CSV cache read could
            // still resurrect it locally - remember it briefly so that read gets filtered. See
            // RecentlyRemovedTasks.kt / DEFECTS.md item 8.
            if (changed && event.event == TaskEventContract.EVENT_FULLY_COMPLETED) {
                RecentlyRemovedTasks.markRemoved(context, event.taskId ?: WebAppClient.rowKey(event.category, event.description))
            }
            DiagnosticLog.log(
                context, "TASK_EVENT_RECEIVED",
                "event=${event.event} task=${event.taskId ?: event.description} category=${event.category} applied=$changed"
            )
        }
    }
}

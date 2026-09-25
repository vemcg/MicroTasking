// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-24, after version v0.2.0-81 main 2026-09-24
package com.microtasking.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Cross-app messages between MicroTasking and ActiveTasks (SPEC.md "Synchronization" > "Message
 * contract, v1"). Identical in both apps' code; change only in step.
 *
 * A message is the fast path only: the sync every time an app becomes visible is what guarantees
 * correctness, so a missed message (app force-stopped, aggressive battery management) costs
 * nothing but a few seconds' delay. Both directions are sent only AFTER the Sheet has the change,
 * so the other app never shows something its own next sync would contradict.
 */
object TaskEventContract {
    /** Declared by both apps (`protectionLevel="signature"`) - only an app signed with the same key can send or receive. */
    const val PERMISSION = "com.vernmcgeorge.tasks.permission.TASK_EVENTS"
    const val ACTION = "com.vernmcgeorge.tasks.action.TASK_EVENT"
    const val SCHEMA_VERSION = 1

    const val OTHER_PACKAGE = "com.activetasks.app"
    const val OTHER_RECEIVER = "com.activetasks.app.TaskEventReceiver"

    const val EXTRA_SCHEMA_VERSION = "schemaVersion"
    const val EXTRA_EVENT = "event"
    const val EXTRA_EVENT_AT = "eventAtEpochMs"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_DESCRIPTION = "description"
    const val EXTRA_LINK = "link"
    const val EXTRA_IMPORTANCE = "importance"
    const val EXTRA_URGENCY = "urgency"

    const val EVENT_REFERRED = "referred"
    const val EVENT_COMPLETED_FOR_NOW = "completedForNow"
    const val EVENT_FULLY_COMPLETED = "fullyCompleted"
}

/** One decoded message. [taskId] is null for a row that predates task ids; [link]/[importance]/[urgency] only ride on "referred". */
data class TaskEvent(
    val event: String,
    val eventAtEpochMs: Long,
    val taskId: String?,
    val category: String,
    val description: String,
    val link: String = "",
    val importance: Double? = null,
    val urgency: Double? = null
)

/**
 * The "referred" message for a referral the Sheet has now accepted. [PendingChange.queuedAtEpochMs]
 * is the time the user confirmed the referral (not when the queue finally flushed), so a message
 * that goes out late - after the network came back - still carries its real time.
 */
fun buildReferredIntent(change: PendingChange): Intent =
    Intent(TaskEventContract.ACTION)
        .setComponent(ComponentName(TaskEventContract.OTHER_PACKAGE, TaskEventContract.OTHER_RECEIVER))
        .putExtra(TaskEventContract.EXTRA_SCHEMA_VERSION, TaskEventContract.SCHEMA_VERSION)
        .putExtra(TaskEventContract.EXTRA_EVENT, TaskEventContract.EVENT_REFERRED)
        .putExtra(TaskEventContract.EXTRA_EVENT_AT, change.queuedAtEpochMs)
        .apply { if (change.taskId != null) putExtra(TaskEventContract.EXTRA_TASK_ID, change.taskId) }
        .putExtra(TaskEventContract.EXTRA_CATEGORY, change.category)
        .putExtra(TaskEventContract.EXTRA_DESCRIPTION, change.description)
        .putExtra(TaskEventContract.EXTRA_LINK, change.link)
        .putExtra(TaskEventContract.EXTRA_IMPORTANCE, change.importance)
        .putExtra(TaskEventContract.EXTRA_URGENCY, change.urgency)

/**
 * Tells ActiveTasks a task was referred. Never throws: an uninstalled or unreachable receiver (or
 * a permission not granted yet because of install order) is simply skipped - the other app's own
 * sync will pick the change up from the Sheet instead.
 */
fun sendReferredEvent(context: Context, change: PendingChange) {
    runCatching {
        context.sendBroadcast(buildReferredIntent(change), TaskEventContract.PERMISSION)
    }.onFailure { error ->
        DiagnosticLog.log(context, "TASK_EVENT_SEND_FAILED", "event=referred task=${change.taskId ?: change.description} error=${error.message}")
    }
}

/**
 * Decodes an incoming message, or null if it isn't one this version understands: a schemaVersion
 * newer than [TaskEventContract.SCHEMA_VERSION] (ignored, per the contract), a missing/unknown
 * event, or missing required fields.
 */
fun parseTaskEvent(intent: Intent?): TaskEvent? {
    if (intent == null || intent.action != TaskEventContract.ACTION) return null
    val schema = intent.getIntExtra(TaskEventContract.EXTRA_SCHEMA_VERSION, 0)
    if (schema < 1 || schema > TaskEventContract.SCHEMA_VERSION) return null
    val event = intent.getStringExtra(TaskEventContract.EXTRA_EVENT) ?: return null
    if (event != TaskEventContract.EVENT_REFERRED &&
        event != TaskEventContract.EVENT_COMPLETED_FOR_NOW &&
        event != TaskEventContract.EVENT_FULLY_COMPLETED
    ) return null
    if (!intent.hasExtra(TaskEventContract.EXTRA_EVENT_AT)) return null
    val category = intent.getStringExtra(TaskEventContract.EXTRA_CATEGORY) ?: return null
    val description = intent.getStringExtra(TaskEventContract.EXTRA_DESCRIPTION) ?: return null
    return TaskEvent(
        event = event,
        eventAtEpochMs = intent.getLongExtra(TaskEventContract.EXTRA_EVENT_AT, 0L),
        taskId = intent.getStringExtra(TaskEventContract.EXTRA_TASK_ID)?.takeIf { it.isNotBlank() },
        category = category,
        description = description,
        link = intent.getStringExtra(TaskEventContract.EXTRA_LINK).orEmpty(),
        importance = if (intent.hasExtra(TaskEventContract.EXTRA_IMPORTANCE)) intent.getDoubleExtra(TaskEventContract.EXTRA_IMPORTANCE, 0.0) else null,
        urgency = if (intent.hasExtra(TaskEventContract.EXTRA_URGENCY)) intent.getDoubleExtra(TaskEventContract.EXTRA_URGENCY, 0.0) else null
    )
}

/**
 * Writes one incoming change into a task list - no network, no Sheet read; the next sync confirms it.
 *  - **completedForNow**: ActiveTasks cleared the row's priority, so the task is back in this
 *    app's pool - clear [ManagedTask.referredAt].
 *  - **fullyCompleted**: ActiveTasks deleted the row - drop the task.
 *  - **referred** is for ActiveTasks (MicroTasking is the sender); ignored here.
 *
 * Late or duplicate delivery is harmless because events are stamped: one whose time is older than
 * the local referral it would undo means the task was referred again since - it's stale, ignored.
 * (completedForNow on a task that isn't referred is already a no-op.)
 */
fun applyIncomingEvent(tasks: List<ManagedTask>, event: TaskEvent): List<ManagedTask> {
    fun matches(task: ManagedTask) =
        sameRow(event.taskId, event.category, event.description, task.taskId, task.category, task.description)
    fun notStale(task: ManagedTask) = task.referredAt == null || event.eventAtEpochMs >= task.referredAt
    return when (event.event) {
        TaskEventContract.EVENT_COMPLETED_FOR_NOW ->
            tasks.map { task ->
                if (matches(task) && task.referredAt != null && notStale(task)) task.copy(referredAt = null) else task
            }
        TaskEventContract.EVENT_FULLY_COMPLETED ->
            // The row is deleted from the Sheet, so a task that merely wasn't marked referred here
            // (e.g. referred from another device) goes too - only a stale event is ignored.
            tasks.filterNot { task -> matches(task) && notStale(task) }
        else -> tasks
    }
}

// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-24, after version v0.2.0-81 main 2026-09-24
package com.microtasking.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The Sheet-write side of the synchronization design (SPEC.md "Synchronization"): every write to
 * the Sheet goes through a persisted queue, so an action takes effect locally at once and reaches
 * the Sheet when it can - a failed or offline write is no longer "just not applied".
 *
 * MicroTasking only writes one thing today, a referral's `setPriority` ([OP_SET_PRIORITY]); the
 * [operation] field is here so `createRow` (My Tasks "Add task", PUNCH_LIST item 9) can join later
 * without a persisted-format change.
 */
data class PendingChange(
    val id: String,
    val operation: String = OP_SET_PRIORITY,
    val taskId: String?,
    val category: String,
    val description: String,
    val link: String = "",
    val importance: Double,
    val urgency: Double,
    /** When the user took the action (not when it finally reached the Sheet) - also the time stamped on its cross-app message. */
    val queuedAtEpochMs: Long
) {
    companion object {
        const val OP_SET_PRIORITY = "setPriority"
    }
}

/** What happened when one queued change was sent to the Sheet. */
enum class SendOutcome {
    /** The Sheet has the change: dequeue it, then send its cross-app message. */
    SUCCESS,

    /** The row (or its tab) no longer exists: nothing left to do - dequeue, send no message. */
    ROW_NOT_FOUND,

    /** Network or server trouble: keep it queued and try again later. */
    RETRY
}

/** Queued changes are matched to a task/event with the same identity rule as everything else - see [sameRow]. */
fun PendingChange.matches(task: ManagedTask): Boolean =
    sameRow(taskId, category, description, task.taskId, task.category, task.description)

/** Adds [change], replacing an older unsent change for the same row and operation (the newer one wins). */
fun enqueuePending(existing: List<PendingChange>, change: PendingChange): List<PendingChange> =
    existing.filterNot {
        it.operation == change.operation &&
            sameRow(it.taskId, it.category, it.description, change.taskId, change.category, change.description)
    } + change

/**
 * Lays unsent changes over a task list that was just rebuilt from the Sheet: a task whose referral
 * is still waiting to reach the Sheet stays referred (so an offline referral isn't "corrected" back
 * into the pool by the very next sync, which would read the Sheet without it).
 */
fun applyPendingOverlay(tasks: List<ManagedTask>, pending: List<PendingChange>): List<ManagedTask> {
    val referrals = pending.filter { it.operation == PendingChange.OP_SET_PRIORITY }
    if (referrals.isEmpty()) return tasks
    return tasks.map { task ->
        if (task.referredAt != null) return@map task
        val waiting = referrals.firstOrNull { it.matches(task) } ?: return@map task
        task.copy(referredAt = waiting.queuedAtEpochMs)
    }
}

fun readPendingChanges(json: String): List<PendingChange> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { index ->
        val entry = values.getJSONObject(index)
        PendingChange(
            id = entry.getString("id"),
            operation = entry.optString("operation", PendingChange.OP_SET_PRIORITY),
            taskId = if (entry.has("taskId") && !entry.isNull("taskId")) entry.getString("taskId") else null,
            category = entry.getString("category"),
            description = entry.getString("description"),
            link = entry.optString("link", ""),
            importance = entry.getDouble("importance"),
            urgency = entry.getDouble("urgency"),
            queuedAtEpochMs = entry.getLong("queuedAtEpochMs")
        )
    }
}.getOrDefault(emptyList())

fun writePendingChanges(changes: List<PendingChange>): String = JSONArray().apply {
    changes.forEach { change ->
        put(JSONObject().apply {
            put("id", change.id)
            put("operation", change.operation)
            put("taskId", change.taskId ?: JSONObject.NULL)
            put("category", change.category)
            put("description", change.description)
            put("link", change.link)
            put("importance", change.importance)
            put("urgency", change.urgency)
            put("queuedAtEpochMs", change.queuedAtEpochMs)
        })
    }
}.toString()

/**
 * The persisted queue itself (SharedPreferences, so it survives the app being killed) and the flush
 * that drains it. Every function that touches storage is safe to call from any thread.
 *
 * Two locks, deliberately separate: [dataLock] guards each short read-modify-write of the stored
 * list (so the UI thread enqueueing while a flush removes an entry can't lose either change), and
 * [flushLock] makes flushes run one at a time (the foreground and the WorkManager job can both
 * flush - without it they could send the same entry twice). The network calls happen under
 * [flushLock] only, never [dataLock], so enqueueing from the UI thread never waits on the network.
 */
object PendingChanges {
    const val PREFS_KEY = "pending_changes"

    private val dataLock = ReentrantLock()
    private val flushLock = ReentrantLock()

    fun all(context: Context): List<PendingChange> = dataLock.withLock {
        readPendingChanges(TaskDelivery.prefs(context).getString(PREFS_KEY, "[]") ?: "[]")
    }

    fun count(context: Context): Int = all(context).size

    fun enqueue(context: Context, change: PendingChange) = dataLock.withLock {
        save(context, enqueuePending(all(context), change))
    }

    fun remove(context: Context, id: String) = dataLock.withLock {
        save(context, all(context).filterNot { it.id == id })
    }

    /** Throws away every queued change - used when the user points the app at a different Sheet. */
    fun clear(context: Context) = dataLock.withLock { save(context, emptyList()) }

    private fun save(context: Context, changes: List<PendingChange>) {
        TaskDelivery.prefs(context).edit().putString(PREFS_KEY, writePendingChanges(changes)).apply()
    }

    /**
     * Sends every queued change, oldest first, and returns how many are still queued. A change that
     * can't be sent doesn't hold up the others (they target different rows). [send] does the
     * blocking network call, so callers run this off the main thread; [onSent] runs for each change
     * the Sheet accepted - never before - and is where its cross-app message goes out.
     */
    fun flush(
        context: Context,
        send: (PendingChange) -> SendOutcome,
        onSent: (PendingChange) -> Unit = {}
    ): Int = flushLock.withLock {
        for (change in all(context).sortedBy { it.queuedAtEpochMs }) {
            when (send(change)) {
                SendOutcome.SUCCESS -> {
                    remove(context, change.id)
                    onSent(change)
                }
                SendOutcome.ROW_NOT_FOUND -> remove(context, change.id)
                SendOutcome.RETRY -> Unit
            }
        }
        count(context)
    }

    /** [flush] against the Web App configured in Settings, sending the "referred" message for what succeeds. */
    fun flushToSheet(context: Context): Int {
        val webAppUrl = TaskDelivery.prefs(context).getString("web_app_url", "") ?: ""
        if (webAppUrl.isBlank()) return count(context)
        return flush(context, sheetSender(webAppUrl)) { change -> sendReferredEvent(context, change) }
    }

    /** Turns a Web App call into a [SendOutcome] - split out of [flushToSheet] so the classification is one obvious place. */
    fun sheetSender(webAppUrl: String): (PendingChange) -> SendOutcome = { change ->
        WebAppClient.setPriority(
            webAppUrl, change.category, change.description, change.importance, change.urgency, change.taskId
        ).fold(
            onSuccess = { SendOutcome.SUCCESS },
            onFailure = { error ->
                if (error is WebAppException && error.rowNotFound) SendOutcome.ROW_NOT_FOUND else SendOutcome.RETRY
            }
        )
    }
}

// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

data class ManagedTask(
    val id: String,
    val description: String,
    val category: String,
    val durationMinutes: Int,
    val builtIn: Boolean,
    val enabled: Boolean = true,
    val temporarilyUnavailable: Boolean = false,
    val neverSuggest: Boolean = false,
    // Epoch ms this task was referred to ActiveTasks, null = not referred. Set locally the instant a
    // referral write succeeds (see MainActivity's referral flow), then overwritten from the
    // sheet's Importance/Urgency state at every sync (see refreshReferralState in this file) -
    // a dedicated field, deliberately not a reuse of neverSuggest, which mergeImportedManagedTasks
    // below must instead *preserve* untouched across every re-sync. See SPEC.md
    // "Task referral to ActiveTasks".
    val referredAt: Long? = null,
    // DEV (sheet-surrogate-keys): the Sheet's hidden per-row "Task ID" (populate_google_sheet.js
    // ensureTaskIds_) - null for a seed-/custom- task (no Sheet row at all), or an external- task
    // from a sheet that hasn't been repaired to have the column yet. When present, [id] is built
    // from it instead of category+description (see parseExternalTaskCsv), so a description
    // rename no longer changes identity; refreshReferralState below and WebAppClient prefer it
    // over the legacy category|description key the same way. Not yet used for category identity -
    // a tab rename is a separate, not-yet-solved problem (needs categoryId, which nothing reads
    // yet - see the Sheet script's ensureCategoryId_).
    val taskId: String? = null
)

enum class TaskLifecycleState {
    READY,
    STARTED,
    COMPLETED,
    ABANDONED,
    TIMED_OUT
}

data class TaskStackEntry(
    val task: ManagedTask,
    val state: TaskLifecycleState = TaskLifecycleState.READY,
    // Set once, at construction - copy() (start/complete/abandon/timeout below) never touches it,
    // so it always reflects when this task actually joined the queue, not its last state change.
    val queuedAtEpochMs: Long = System.currentTimeMillis(),
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null
) {
    fun start(): TaskStackEntry = copy(
        state = TaskLifecycleState.STARTED,
        startedAtEpochMs = startedAtEpochMs ?: System.currentTimeMillis()
    )

    // completedAtEpochMs isn't read anywhere yet - kept so a future elapsed-time/running-average
    // feature doesn't need a persisted-data migration to add it.
    fun complete(): TaskStackEntry = copy(state = TaskLifecycleState.COMPLETED, completedAtEpochMs = System.currentTimeMillis())

    fun abandon(): TaskStackEntry = copy(state = TaskLifecycleState.ABANDONED)

    fun timeout(): TaskStackEntry = copy(state = TaskLifecycleState.TIMED_OUT)

    fun isActionable(): Boolean = state == TaskLifecycleState.READY || state == TaskLifecycleState.STARTED
}

fun makeTaskStack(tasks: List<ManagedTask>, maxEntries: Int = 3): List<TaskStackEntry> =
    tasks.take(maxEntries).map { TaskStackEntry(task = it, state = TaskLifecycleState.READY) }

val builtInTasks = listOf(
    ManagedTask("declutter-surface", "Clear one flat surface completely.", "Decluttering", 5, true),
    ManagedTask("declutter-drawer", "Clear out one kitchen drawer.", "Decluttering", 10, true),
    ManagedTask("clean-counters", "Wipe down the kitchen counters.", "Cleaning", 5, true),
    ManagedTask("clean-room", "Vacuum and mop an entire room.", "Cleaning", 15, true),
    ManagedTask("admin-mail", "Open and sort today's mail.", "Admin/Paperwork", 5, true),
    ManagedTask("admin-bill", "Pay one outstanding bill.", "Admin/Paperwork", 10, true),
    ManagedTask("finance-balance", "Check your bank account balance and recent transactions.", "Finances", 5, true),
    ManagedTask("finance-budget", "Create or update a simple monthly budget.", "Finances", 15, true),
    ManagedTask("health-stretch", "Do a quick stretch routine.", "Health", 5, true),
    ManagedTask("health-walk", "Go for a short walk.", "Health", 10, true),
    ManagedTask("errand-list", "Add missing items to your grocery list.", "Errands", 5, true),
    ManagedTask("errand-grocery", "Do a full grocery run for a few essential items.", "Errands", 15, true)
)

fun loadSeedTasks(context: Context): List<ManagedTask> = runCatching {
    val root = context.assets.open("tasks.json").bufferedReader().use { it.readText() }
    val categories = JSONObject(root).getJSONArray("categories")
    buildList {
        for (categoryIndex in 0 until categories.length()) {
            val category = categories.getJSONObject(categoryIndex)
            val categoryName = category.getString("name")
            val tasks = category.getJSONArray("tasks")
            for (taskIndex in 0 until tasks.length()) {
                val task = tasks.getJSONObject(taskIndex)
                add(
                    ManagedTask(
                        id = "seed-${category.getString("id")}-$taskIndex",
                        description = task.getString("description"),
                        category = categoryName,
                        durationMinutes = task.getInt("durationMinutes"),
                        builtIn = true
                    )
                )
            }
        }
    }
}.getOrDefault(builtInTasks)

private fun managedTaskToJson(task: ManagedTask): JSONObject = JSONObject().apply {
    put("id", task.id)
    put("description", task.description)
    put("category", task.category)
    put("durationMinutes", task.durationMinutes)
    put("builtIn", task.builtIn)
    put("enabled", task.enabled)
    put("temporarilyUnavailable", task.temporarilyUnavailable)
    put("neverSuggest", task.neverSuggest)
    put("referredAt", task.referredAt ?: JSONObject.NULL)
    put("taskId", task.taskId ?: JSONObject.NULL)
}

private fun managedTaskFromJson(task: JSONObject): ManagedTask = ManagedTask(
    id = task.getString("id"),
    description = task.getString("description"),
    category = task.getString("category"),
    durationMinutes = task.getInt("durationMinutes"),
    builtIn = task.getBoolean("builtIn"),
    enabled = task.getBoolean("enabled"),
    temporarilyUnavailable = task.getBoolean("temporarilyUnavailable"),
    neverSuggest = task.getBoolean("neverSuggest"),
    // Absent on a queue/pool entry persisted by a build older than this field - treat that as
    // "not referred" (null), not a crash, same pattern optNullableLong already uses below.
    referredAt = if (task.has("referredAt") && !task.isNull("referredAt")) task.getLong("referredAt") else null,
    // DEV (sheet-surrogate-keys): same absent-on-an-older-build tolerance as referredAt above.
    taskId = if (task.has("taskId") && !task.isNull("taskId")) task.getString("taskId") else null
)

fun readManagedTasks(json: String): List<ManagedTask> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { index -> managedTaskFromJson(values.getJSONObject(index)) }
}.getOrDefault(emptyList())

fun writeManagedTasks(tasks: List<ManagedTask>): String = JSONArray().apply {
    tasks.forEach { put(managedTaskToJson(it)) }
}.toString()

/**
 * Folds a fresh sheet import into the existing pool. Once you've imported a sheet, that sheet's
 * tabs *are* your category list. Any task - imported (`external-`), shipped built-in (`seed-`), or
 * hand-added (`custom-`) - whose category no longer has a tab in the sheet is dropped, so a
 * deleted tab takes its whole category with it. A `custom-` task the user added by hand *inside a
 * category the sheet still has* is kept. For a task that survives the import (same
 * [ManagedTask.id]), the sheet is authoritative for [ManagedTask.enabled] - its column-A checkbox
 * is the source of truth - but the flags the user set in the app ([ManagedTask.neverSuggest],
 * [ManagedTask.temporarilyUnavailable]) are carried over so a re-sync doesn't silently undo them.
 *
 * [authoritativeCategories] is the sheet's tab list - the categories allowed to exist after this
 * import. It defaults to the categories present in [imported], but the caller passes the actual
 * tab names so a tab with zero task rows still counts as a live category, and a category with no
 * tab is dropped even when the import brought no tasks.
 *
 * [ManagedTask.referredAt] is carried over here too, same as the other two flags - this CSV-based
 * import never knows about it either way (Importance/Urgency aren't CSV columns, see SPEC.md
 * "Sheet connection & API"). It's [refreshReferralState] below, called separately against the Apps
 * Script Web App, that's actually authoritative for it.
 *
 * DEV (sheet-surrogate-keys): [ManagedTask.id] is taskId-based when the sheet has one
 * (`parseExternalTaskCsv`), so this function's existing merge-by-[ManagedTask.id] already does the
 * right thing once both the prior and freshly-imported task share that stable id - a description
 * rename no longer looks like "old task dropped, new task appeared" the way it used to, and
 * neverSuggest/temporarilyUnavailable/referredAt above survive it. No logic change needed here for
 * that; it falls out of merging by id the way this function always has.
 *
 * Hardening (PUNCH_LIST "Harden against user edits to the shared Sheet"): [imported] is not
 * trusted to have unique ids. Two sheet rows can share one - a not-yet-repaired sheet still uses
 * the legacy `external-<category>-<description>` id, so two rows with identical text collide;
 * even a repaired sheet's taskId column can briefly hold a duplicate right after a whole-row
 * copy/paste, before the script's own dedupe (`onSheetEdit_`/a "Repair headers & triggers" run)
 * catches up. Every screen that lists this pool keys a `LazyColumn` by [ManagedTask.id]
 * (`items(visibleTasks, key = { it.id })` in the Task Pool screen) - an actual duplicate there is
 * a hard crash, the same bug class `readTaskQueue`'s `.distinctBy` below already guards the queue
 * against. The `distinctBy` at the end here is that same guard for the pool itself: first
 * occurrence wins, so a transient sheet-side duplicate degrades to "one of the two rows is
 * temporarily invisible" instead of crashing every screen that renders the pool.
 */
fun mergeImportedManagedTasks(
    imported: List<ManagedTask>,
    existing: List<ManagedTask>,
    authoritativeCategories: Set<String> = imported.map { it.category }.toSet()
): List<ManagedTask> {
    val priorById = existing.associateBy { it.id }
    val reconciled = imported.map { task ->
        val prior = priorById[task.id] ?: return@map task
        task.copy(
            neverSuggest = prior.neverSuggest,
            temporarilyUnavailable = prior.temporarilyUnavailable,
            referredAt = prior.referredAt
        )
    }
    return (reconciled + existing.filter { task ->
        task.id.startsWith("custom-") && task.category in authoritativeCategories
    }).distinctBy { it.id }
}

/**
 * Reconciles local [ManagedTask.referredAt] against the sheet's actual Importance/Urgency state,
 * fetched from the Apps Script Web App's get-priorities endpoint (never CSV - see SPEC.md "Sheet
 * write-back": hiding a column doesn't exclude it from CSV/gviz export, so those two columns are
 * read via the Web App exclusively). Call this at the same sync boundaries as the CSV import
 * (manual refresh + periodic background sync) - not from [chooseWeightedTask]/`tick`, which must
 * stay a fast local-only read with no network dependency of its own.
 *
 * [referredRowKeys] is the set of keys the Web App reports as having a non-empty Importance or
 * Urgency: each entry is a row's `taskId` when the sheet has one (DEV, sheet-surrogate-keys - see
 * [WebAppClient.getReferredRowKeys]), else the legacy `"<category>|<description>"` text key (see
 * [WebAppClient.rowKey] - row identity is `(tab, description)`, not row index, since rows shift
 * under the bound script's delete-row-on-empty-description behavior). Each local task's own key is
 * computed the same way below, preferring [ManagedTask.taskId] when it has one, so matching is
 * id-based (rename-proof) once both sides have one, and gracefully falls back to the old
 * text-based key for a sheet not yet repaired to have task ids. A task whose key isn't in the set
 * gets `referredAt = null` (covers "Complete (for now)" clearing it sheet-side); a task whose key
 * *is* in the set keeps its existing `referredAt` if already set (referral happened locally and
 * this call is just confirming it), or gets stamped with the current time if this is the first
 * time this client has observed it referred (e.g. a second device where the referral itself
 * happened elsewhere).
 */
fun refreshReferralState(
    tasks: List<ManagedTask>,
    referredRowKeys: Set<String>,
    now: Long = System.currentTimeMillis()
): List<ManagedTask> = tasks.map { task ->
    val key = task.taskId ?: "${task.category}|${task.description}"
    when {
        key !in referredRowKeys -> if (task.referredAt != null) task.copy(referredAt = null) else task
        task.referredAt != null -> task
        else -> task.copy(referredAt = now)
    }
}

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

fun readTaskQueue(json: String): List<TaskStackEntry> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { index ->
        val entry = values.getJSONObject(index)
        TaskStackEntry(
            task = managedTaskFromJson(entry.getJSONObject("task")),
            state = TaskLifecycleState.valueOf(entry.getString("state")),
            // A queue persisted by a build older than this field won't have it - treat that as
            // "queued right now" (the best available approximation) rather than epoch 0.
            queuedAtEpochMs = entry.optNullableLong("queuedAtEpochMs") ?: System.currentTimeMillis(),
            startedAtEpochMs = entry.optNullableLong("startedAtEpochMs"),
            completedAtEpochMs = entry.optNullableLong("completedAtEpochMs")
        )
    }
    // The queue must never hold two entries for the same task - the task screen keys its
    // LazyColumn by task id and a duplicate key is a hard crash. Persisted state from an older
    // build could still carry one, so collapse them here (keep the first / oldest occurrence).
}.getOrDefault(emptyList()).distinctBy { it.task.id }

fun writeTaskQueue(queue: List<TaskStackEntry>): String = JSONArray().apply {
    queue.forEach { entry ->
        put(JSONObject().apply {
            put("task", managedTaskToJson(entry.task))
            put("state", entry.state.name)
            put("queuedAtEpochMs", entry.queuedAtEpochMs)
            put("startedAtEpochMs", entry.startedAtEpochMs ?: JSONObject.NULL)
            put("completedAtEpochMs", entry.completedAtEpochMs ?: JSONObject.NULL)
        })
    }
}.toString()

/**
 * Picks a category weighted by its position in [activeCategoryOrder] (first category is twice as
 * likely as the last, linear in between), then picks uniformly at random among that category's
 * tasks in [tasks].
 */
fun chooseWeightedTask(
    tasks: List<ManagedTask>,
    activeCategoryOrder: List<String>,
    previousTaskId: String?
): ManagedTask {
    val tasksByCategory = tasks.groupBy { it.category }
    val orderedCategories = activeCategoryOrder.filter { tasksByCategory.containsKey(it) }
        .ifEmpty { tasksByCategory.keys.toList() }
    val categoryCount = orderedCategories.size
    val weightedCategories = orderedCategories.mapIndexed { index, category ->
        val weight = if (categoryCount <= 1) 1.0 else 2.0 - (index.toDouble() / (categoryCount - 1))
        category to weight
    }
    val target = Random.nextDouble() * weightedCategories.sumOf { it.second }
    var cumulativeWeight = 0.0
    var chosenCategory = weightedCategories.last().first
    for ((category, weight) in weightedCategories) {
        cumulativeWeight += weight
        if (cumulativeWeight >= target) {
            chosenCategory = category
            break
        }
    }
    val categoryTasks = tasksByCategory.getValue(chosenCategory)
    val candidates = categoryTasks.filter { it.id != previousTaskId }.ifEmpty { categoryTasks }
    return candidates.random()
}

// One entry per completed task: the streak length it reached, so a per-period "longest streak"
// is just the max streakAtCompletion among entries whose epochMs falls in that period - no need to
// replay the whole history to reconstruct it.
data class CompletionRecord(val epochMs: Long, val streakAtCompletion: Int)

fun readCompletionLog(json: String): List<CompletionRecord> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { index ->
        val entry = values.getJSONObject(index)
        CompletionRecord(
            epochMs = entry.getLong("epochMs"),
            streakAtCompletion = entry.getInt("streakAtCompletion")
        )
    }
}.getOrDefault(emptyList())

fun writeCompletionLog(log: List<CompletionRecord>): String = JSONArray().apply {
    log.forEach { record ->
        put(JSONObject().apply {
            put("epochMs", record.epochMs)
            put("streakAtCompletion", record.streakAtCompletion)
        })
    }
}.toString()

fun longestStreakSince(log: List<CompletionRecord>, sinceEpochMs: Long): Int =
    log.filter { it.epochMs >= sinceEpochMs }.maxOfOrNull { it.streakAtCompletion } ?: 0

fun readDeclineCounts(json: String): Map<String, Int> = runCatching {
    val values = JSONObject(json)
    values.keys().asSequence().associateWith { values.getInt(it) }
}.getOrDefault(emptyMap())

private val queuedAtFormatter = DateTimeFormatter.ofPattern("h:mm:ss a")

/** Human-readable local time a task joined the queue, e.g. "3:42:11 PM" - see DEFECTS.md item 3. */
fun formatQueuedAt(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(queuedAtFormatter)

fun taskStateLabel(state: TaskLifecycleState): String = when (state) {
    TaskLifecycleState.READY -> "Ready"
    TaskLifecycleState.STARTED -> "Started"
    TaskLifecycleState.COMPLETED -> "Completed"
    TaskLifecycleState.ABANDONED -> "Abandoned"
    TaskLifecycleState.TIMED_OUT -> "Timed out"
}

fun writeDeclineCounts(counts: Map<String, Int>): String = JSONObject(counts).toString()
// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

data class ManagedTask(
    val id: String,
    val description: String,
    val category: String,
    val durationMinutes: Int,
    val builtIn: Boolean,
    val enabled: Boolean = true,
    val temporarilyUnavailable: Boolean = false,
    val neverSuggest: Boolean = false
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
}

private fun managedTaskFromJson(task: JSONObject): ManagedTask = ManagedTask(
    id = task.getString("id"),
    description = task.getString("description"),
    category = task.getString("category"),
    durationMinutes = task.getInt("durationMinutes"),
    builtIn = task.getBoolean("builtIn"),
    enabled = task.getBoolean("enabled"),
    temporarilyUnavailable = task.getBoolean("temporarilyUnavailable"),
    neverSuggest = task.getBoolean("neverSuggest")
)

fun readManagedTasks(json: String): List<ManagedTask> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { index -> managedTaskFromJson(values.getJSONObject(index)) }
}.getOrDefault(emptyList())

fun writeManagedTasks(tasks: List<ManagedTask>): String = JSONArray().apply {
    tasks.forEach { put(managedTaskToJson(it)) }
}.toString()

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

fun readTaskQueue(json: String): List<TaskStackEntry> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { index ->
        val entry = values.getJSONObject(index)
        TaskStackEntry(
            task = managedTaskFromJson(entry.getJSONObject("task")),
            state = TaskLifecycleState.valueOf(entry.getString("state")),
            startedAtEpochMs = entry.optNullableLong("startedAtEpochMs"),
            completedAtEpochMs = entry.optNullableLong("completedAtEpochMs")
        )
    }
}.getOrDefault(emptyList())

fun writeTaskQueue(queue: List<TaskStackEntry>): String = JSONArray().apply {
    queue.forEach { entry ->
        put(JSONObject().apply {
            put("task", managedTaskToJson(entry.task))
            put("state", entry.state.name)
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

fun readDeclineCounts(json: String): Map<String, Int> = runCatching {
    val values = JSONObject(json)
    values.keys().asSequence().associateWith { values.getInt(it) }
}.getOrDefault(emptyMap())

fun taskStateLabel(state: TaskLifecycleState): String = when (state) {
    TaskLifecycleState.READY -> "Ready"
    TaskLifecycleState.STARTED -> "Started"
    TaskLifecycleState.COMPLETED -> "Completed"
    TaskLifecycleState.ABANDONED -> "Abandoned"
    TaskLifecycleState.TIMED_OUT -> "Timed out"
}

fun writeDeclineCounts(counts: Map<String, Int>): String = JSONObject(counts).toString()
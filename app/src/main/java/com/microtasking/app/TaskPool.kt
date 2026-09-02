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
    DEFERRED
}

data class TaskStackEntry(
    val task: ManagedTask,
    val state: TaskLifecycleState = TaskLifecycleState.READY,
    val startedAtEpochMs: Long? = null,
    val deferredUntilEpochMs: Long? = null
) {
    fun start(): TaskStackEntry = copy(
        state = TaskLifecycleState.STARTED,
        startedAtEpochMs = startedAtEpochMs ?: System.currentTimeMillis()
    )

    fun complete(): TaskStackEntry = copy(state = TaskLifecycleState.COMPLETED)

    fun abandon(): TaskStackEntry = copy(state = TaskLifecycleState.ABANDONED)

    fun defer(days: Long): TaskStackEntry = copy(
        state = TaskLifecycleState.DEFERRED,
        deferredUntilEpochMs = System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000L)
    )

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

fun readManagedTasks(json: String): List<ManagedTask> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { index ->
        val task = values.getJSONObject(index)
        ManagedTask(
            id = task.getString("id"),
            description = task.getString("description"),
            category = task.getString("category"),
            durationMinutes = task.getInt("durationMinutes"),
            builtIn = task.getBoolean("builtIn"),
            enabled = task.getBoolean("enabled"),
            temporarilyUnavailable = task.getBoolean("temporarilyUnavailable"),
            neverSuggest = task.getBoolean("neverSuggest")
        )
    }
}.getOrDefault(emptyList())

fun writeManagedTasks(tasks: List<ManagedTask>): String = JSONArray().apply {
    tasks.forEach { task ->
        put(JSONObject().apply {
            put("id", task.id)
            put("description", task.description)
            put("category", task.category)
            put("durationMinutes", task.durationMinutes)
            put("builtIn", task.builtIn)
            put("enabled", task.enabled)
            put("temporarilyUnavailable", task.temporarilyUnavailable)
            put("neverSuggest", task.neverSuggest)
        })
    }
}.toString()

fun chooseWeightedTask(
    tasks: List<ManagedTask>,
    declineCounts: Map<String, Int>,
    previousTaskId: String?
): ManagedTask {
    val nonRepeatedTasks = tasks.filter { it.id != previousTaskId }.ifEmpty { tasks }
    val weightedTasks = nonRepeatedTasks.map { task ->
        task to (1.0 / (1 + (declineCounts[task.id] ?: 0)))
    }
    val target = Random.nextDouble() * weightedTasks.sumOf { it.second }
    var cumulativeWeight = 0.0
    for ((task, weight) in weightedTasks) {
        cumulativeWeight += weight
        if (cumulativeWeight >= target) return task
    }
    return weightedTasks.last().first
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
    TaskLifecycleState.DEFERRED -> "Deferred"
}

fun writeDeclineCounts(counts: Map<String, Int>): String = JSONObject(counts).toString()
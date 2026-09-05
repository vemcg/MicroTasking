package com.microtasking.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

data class UserTask(val description: String, val category: String, val enabled: Boolean)

fun readUserTasks(json: String): List<UserTask> = runCatching {
    val tasks = JSONArray(json)
    List(tasks.length()) { index ->
        val task = tasks.getJSONObject(index)
        UserTask(
            description = task.getString("description"),
            category = task.getString("category"),
            enabled = task.getBoolean("enabled")
        )
    }
}.getOrDefault(emptyList())

fun writeUserTasks(tasks: List<UserTask>): String = JSONArray().apply {
    tasks.forEach { task ->
        put(JSONObject().apply {
            put("description", task.description)
            put("category", task.category)
            put("enabled", task.enabled)
        })
    }
}.toString()

fun eligiblePromptTasks(
    managedTasks: List<ManagedTask>,
    legacyUserTasks: List<UserTask>,
    selectedCategories: Set<String>
): List<ManagedTask> {
    val managedEligibleTasks = managedTasks
        .filter { task ->
            task.enabled && !task.temporarilyUnavailable && !task.neverSuggest &&
                task.category in selectedCategories
        }
    val legacyEligibleTasks = legacyUserTasks
        .filter { it.enabled && it.category in selectedCategories }
        .mapIndexed { index, task ->
            ManagedTask(
                id = "legacy-$index-${task.description}",
                description = task.description,
                category = task.category,
                durationMinutes = 5,
                builtIn = false
            )
        }
    return managedEligibleTasks + legacyEligibleTasks
}

/**
 * The single producer of queued tasks, shared by the foreground pacing loop and the background
 * alarm receiver so both read and write the exact same persisted state instead of drifting apart.
 */
object TaskDelivery {
    const val PREFS_NAME = "microtasking_settings"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private class Settings(
        val startHour: Int,
        val endHour: Int,
        val promptsPerDay: Int,
        val maxQueueSize: Int,
        val promptTasks: List<ManagedTask>,
        val activeCategoryOrder: List<String>
    )

    private fun loadSettings(context: Context): Settings? {
        val prefs = prefs(context)
        if (!prefs.getBoolean("setup_complete", false)) return null
        val managedTasks = readManagedTasks(prefs.getString("managed_tasks", "[]") ?: "[]")
        val userTasks = readUserTasks(prefs.getString("user_tasks", "[]") ?: "[]")
        val selectedCategories = prefs.getStringSet("selected_categories", emptySet()) ?: emptySet()
        val promptTasks = eligiblePromptTasks(managedTasks, userTasks, selectedCategories)
        if (promptTasks.isEmpty()) return null
        val availableCategories = (managedTasks.map { it.category } + userTasks.map { it.category }).distinct()
        return Settings(
            startHour = (prefs.getString("start_hour", "9") ?: "9").toIntOrNull() ?: 0,
            endHour = (prefs.getString("end_hour", "21") ?: "21").toIntOrNull() ?: 24,
            promptsPerDay = (prefs.getString("prompts_per_day", "6") ?: "6").toIntOrNull() ?: 0,
            maxQueueSize = prefs.getInt("max_task_queue_size", 3),
            promptTasks = promptTasks,
            activeCategoryOrder = availableCategories.filter { it in selectedCategories }
        )
    }

    /** Millis until the next delivery slot, or null if nothing should ever be delivered right now. */
    fun computeNextDelayMillis(context: Context): Long? {
        val settings = loadSettings(context) ?: return null
        val prefs = prefs(context)
        val now = LocalDateTime.now()
        val windowStart = currentWindowStart(now, settings.startHour, settings.endHour).toEpochMillis()
        val deliveredInWindow = if (windowStart == prefs.getLong("prompts_window_start_epoch", 0L)) {
            prefs.getInt("prompts_delivered_in_window", 0)
        } else {
            0
        }
        return nextPromptDelayMillis(now, settings.startHour, settings.endHour, settings.promptsPerDay, deliveredInWindow)
    }

    /**
     * Runs one delivery slot against persisted state: rolls the active window over first (timing
     * out anything left in the queue and resetting the streak if the window just closed), then
     * either adds a task to the queue or - if background prompts are paused - just consumes the
     * slot silently, so a long pause can't cram a burst of catch-up deliveries in when it ends.
     * Returns true if a task was actually added (the caller decides whether to notify from that).
     */
    fun deliverOrConsumeSlot(context: Context): Boolean {
        val settings = loadSettings(context) ?: return false
        val prefs = prefs(context)
        val now = LocalDateTime.now()

        var queue = readTaskQueue(prefs.getString("task_queue", "[]") ?: "[]")
        var streak = prefs.getInt("streak", 0)
        val longestStreak = prefs.getInt("longest_streak", 0)
        var deliveredInWindow = prefs.getInt("prompts_delivered_in_window", 0)
        var windowStartEpoch = prefs.getLong("prompts_window_start_epoch", 0L)

        val newWindowStart = currentWindowStart(now, settings.startHour, settings.endHour).toEpochMillis()
        if (newWindowStart != windowStartEpoch) {
            if (queue.any { it.isActionable() }) {
                queue = queue.filterNot { it.isActionable() }
                streak = 0
            }
            windowStartEpoch = newWindowStart
            deliveredInWindow = 0
        }

        var taskAdded = false
        if (prefs.getBoolean("background_prompts_enabled", true)) {
            val activeTasks = queue.filter { it.isActionable() }
            if (activeTasks.size >= settings.maxQueueSize) {
                streak = 0
            }
            val nextTask = chooseWeightedTask(
                tasks = settings.promptTasks,
                activeCategoryOrder = settings.activeCategoryOrder,
                previousTaskId = activeTasks.lastOrNull()?.task?.id
            )
            queue = activeTasks.takeLast((settings.maxQueueSize - 1).coerceAtLeast(0)) + TaskStackEntry(nextTask)
            taskAdded = true
        }
        deliveredInWindow++

        prefs.edit()
            .putString("task_queue", writeTaskQueue(queue))
            .putInt("streak", streak)
            .putInt("longest_streak", maxOf(longestStreak, streak))
            .putInt("prompts_delivered_in_window", deliveredInWindow)
            .putLong("prompts_window_start_epoch", windowStartEpoch)
            .apply()

        return taskAdded
    }
}

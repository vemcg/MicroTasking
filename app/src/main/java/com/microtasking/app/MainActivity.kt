// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.lightColorScheme
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("microtasking_settings", MODE_PRIVATE)
        preferences.edit().putBoolean("app_in_foreground", true).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
        if (preferences.getBoolean("setup_complete", false)) {
            // A fresh launch always (re)starts delivery, regardless of whatever paused state was
            // in effect before the app was last closed - pausing only lasts for the current
            // running session, not across a full restart.
            preferences.edit().putBoolean("background_prompts_enabled", true).apply()
            // Catches up on any day/window rollover that happened while the app was closed,
            // before the streak/queue below get read into UI state - otherwise a stale streak
            // could show until whichever delivery tick happens to run first.
            TaskDelivery.reconcile(this)
        }
        setContent {
            MaterialTheme(colorScheme = microTaskingColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MicroTaskingApp(
                        setupComplete = preferences.getBoolean("setup_complete", false),
                        selectedCategories = preferences.getStringSet(
                            "selected_categories",
                            emptySet()
                        ) ?: emptySet(),
                        startHour = preferences.getString("start_hour", "9") ?: "9",
                        endHour = preferences.getString("end_hour", "21") ?: "21",
                        promptsPerDay = preferences.getString("prompts_per_day", "6") ?: "6",
                        maxQueueSize = preferences.getInt("max_task_queue_size", 3),
                        externalSheetUrl = preferences.getString("external_sheet_url", "") ?: "",
                        managedTasks = readManagedTasks(
                            preferences.getString("managed_tasks", "[]") ?: "[]"
                        ),
                        declineCounts = readDeclineCounts(
                            preferences.getString("task_decline_counts", "{}") ?: "{}"
                        ),
                        userTasks = readUserTasks(preferences.getString("user_tasks", "[]") ?: "[]"),
                        initialTaskQueue = readTaskQueue(preferences.getString("task_queue", "[]") ?: "[]"),
                        initialStreak = preferences.getInt("streak", 0),
                        initialLongestStreak = preferences.getInt("longest_streak", 0),
                        initialTimeoutStreak = preferences.getInt("timeout_streak", 0),
                        initialLastOutcome = runCatching {
                            TaskLifecycleState.valueOf(preferences.getString("last_outcome", TaskLifecycleState.COMPLETED.name)!!)
                        }.getOrDefault(TaskLifecycleState.COMPLETED),
                        initialCompletionLog = readCompletionLog(preferences.getString("completion_log", "[]") ?: "[]"),
                        onSettingsSaved = { categories, start, end, prompts, maxQueueSize, sheetUrl ->
                            preferences.edit()
                                .putBoolean("setup_complete", true)
                                .putStringSet("selected_categories", categories)
                                .putString("start_hour", start)
                                .putString("end_hour", end)
                                .putString("prompts_per_day", prompts)
                                .putInt("max_task_queue_size", maxQueueSize)
                                .putString("external_sheet_url", sheetUrl)
                                .putBoolean("background_prompts_enabled", true)
                                .apply()
                        },
                        onUserTasksSaved = { userTasks ->
                            preferences.edit()
                                .putString("user_tasks", writeUserTasks(userTasks))
                                .apply()
                        },
                        onManagedTasksSaved = { tasks ->
                            preferences.edit()
                                .putString("managed_tasks", writeManagedTasks(tasks))
                                .apply()
                        },
                        onDeclineCountsSaved = { counts ->
                            preferences.edit()
                                .putString("task_decline_counts", writeDeclineCounts(counts))
                                .apply()
                        },
                        onSheetUrlSaved = { sheetUrl ->
                            preferences.edit()
                                .putString("external_sheet_url", sheetUrl)
                                .apply()
                        },
                        onBackgroundPromptsChanged = { enabled ->
                            // Only persisted here - while the app is foregrounded the alarm is
                            // cancelled anyway (onStart/onStop own it) and the live pacing loop
                            // reads this flag directly on every tick.
                            preferences.edit().putBoolean("background_prompts_enabled", enabled).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences("microtasking_settings", MODE_PRIVATE)
            .edit()
            .putBoolean("app_in_foreground", true)
            .apply()
        // The foreground pacing loop owns delivery while open; the background alarm chain would
        // otherwise fire redundantly alongside it.
        PromptScheduler.cancel(this)
    }

    override fun onStop() {
        super.onStop()
        val preferences = getSharedPreferences("microtasking_settings", MODE_PRIVATE)
        preferences.edit().putBoolean("app_in_foreground", false).apply()
        if (preferences.getBoolean("setup_complete", false)) {
            PromptScheduler.scheduleNext(this)
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 1
    }
}

@Composable
fun MicroTaskingApp(
    setupComplete: Boolean,
    selectedCategories: Set<String>,
    startHour: String,
    endHour: String,
    promptsPerDay: String,
    maxQueueSize: Int,
    externalSheetUrl: String,
    userTasks: List<UserTask>,
    managedTasks: List<ManagedTask>,
    declineCounts: Map<String, Int>,
    initialTaskQueue: List<TaskStackEntry>,
    initialStreak: Int,
    initialLongestStreak: Int,
    initialTimeoutStreak: Int,
    initialLastOutcome: TaskLifecycleState,
    initialCompletionLog: List<CompletionRecord>,
    onSettingsSaved: (Set<String>, String, String, String, Int, String) -> Unit,
    onUserTasksSaved: (List<UserTask>) -> Unit,
    onManagedTasksSaved: (List<ManagedTask>) -> Unit,
    onDeclineCountsSaved: (Map<String, Int>) -> Unit,
    onSheetUrlSaved: (String) -> Unit,
    onBackgroundPromptsChanged: (Boolean) -> Unit
) {
    var showingSettings by remember {
        mutableStateOf(!setupComplete || (managedTasks.isEmpty() && userTasks.isEmpty()))
    }
    var showingMyTasks by remember { mutableStateOf(false) }
    var showingTaskPool by remember { mutableStateOf(false) }
    var showingQrScanner by remember { mutableStateOf(false) }
    var showingScore by remember { mutableStateOf(false) }
    var savedCategories by remember { mutableStateOf(selectedCategories) }
    var savedStartHour by remember { mutableStateOf(startHour) }
    var savedEndHour by remember { mutableStateOf(endHour) }
    var savedPromptsPerDay by remember { mutableStateOf(promptsPerDay) }
    var savedMaxQueueSize by remember { mutableIntStateOf(maxQueueSize) }
    var savedSheetUrl by remember { mutableStateOf(externalSheetUrl) }
    var savedUserTasks by remember { mutableStateOf(userTasks) }
    var savedManagedTasks by remember { mutableStateOf(managedTasks) }
    var savedDeclineCounts by remember { mutableStateOf(declineCounts) }
    var streak by remember { mutableIntStateOf(initialStreak) }
    var longestStreak by remember { mutableIntStateOf(initialLongestStreak) }
    var timeoutStreak by remember { mutableIntStateOf(initialTimeoutStreak) }
    var lastOutcome by remember { mutableStateOf(initialLastOutcome) }
    var completionLog by remember { mutableStateOf(initialCompletionLog) }
    var scoreEntryToken by remember { mutableIntStateOf(0) }
    var backgroundPromptsRunning by remember { mutableStateOf(true) }
    var isImportingSheet by remember { mutableStateOf(false) }
    var sheetImportMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val availableCategories = (savedManagedTasks.map { it.category } + savedUserTasks.map { it.category })
        .distinct()
    val activeCategoryOrder = availableCategories.filter { it in savedCategories }
    val promptTasks = eligiblePromptTasks(savedManagedTasks, savedUserTasks, savedCategories)
    var taskQueue by remember(promptTasks, savedMaxQueueSize) {
        mutableStateOf(makeTaskStack(promptTasks, maxEntries = savedMaxQueueSize))
    }
    // Restores whatever was persisted from a prior session, once, without disturbing the
    // regenerate-on-settings-change behavior above (which must keep using fresh data, not this
    // one-time snapshot, whenever promptTasks/savedMaxQueueSize change later).
    LaunchedEffect(Unit) {
        if (initialTaskQueue.isNotEmpty()) {
            taskQueue = initialTaskQueue
        }
    }
    val visibleTaskEntries = taskQueue.filter { it.isActionable() }

    fun persistTaskQueue(newQueue: List<TaskStackEntry>) {
        taskQueue = newQueue
        TaskDelivery.prefs(context).edit().putString("task_queue", writeTaskQueue(newQueue)).apply()
    }

    fun persistStreak(newStreak: Int) {
        streak = newStreak
        if (newStreak > longestStreak) longestStreak = newStreak
        TaskDelivery.prefs(context).edit()
            .putInt("streak", newStreak)
            .putInt("longest_streak", longestStreak)
            .apply()
    }

    // A manual action on a task is real engagement, so it always clears the "pushed off the top
    // with no action taken" timeout streak - otherwise a stale timeout_streak read back from prefs
    // on the next delivery tick would clobber this outcome even though nothing timed out since.
    fun persistLastOutcome(outcome: TaskLifecycleState) {
        lastOutcome = outcome
        timeoutStreak = 0
        TaskDelivery.prefs(context).edit()
            .putString("last_outcome", outcome.name)
            .putInt("timeout_streak", 0)
            .apply()
    }

    // 45 days comfortably covers the longest real (non-test) score window - a month never has
    // more than 31 days - with margin, so this never trims something a real "this month" query
    // still needs, regardless of RAPID_TESTING_MODE.
    fun persistCompletionLog(newLog: List<CompletionRecord>) {
        val cutoff = System.currentTimeMillis() - 45L * 24 * 60 * 60 * 1000
        val trimmed = newLog.filter { it.epochMs >= cutoff }
        completionLog = trimmed
        TaskDelivery.prefs(context).edit().putString("completion_log", writeCompletionLog(trimmed)).apply()
    }

    fun runSheetImport(url: String) {
        isImportingSheet = true
        sheetImportMessage = null
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                importExternalTasksFromSheet(url)
            }
            isImportingSheet = false
            val importedTasks = result.tasks
            if (importedTasks.isNotEmpty()) {
                savedManagedTasks = importedTasks + savedManagedTasks.filter { task ->
                    task.category !in importedTasks.map { it.category }
                }
                onManagedTasksSaved(savedManagedTasks)
                val categoryCount = importedTasks.map { it.category }.distinct().size
                sheetImportMessage = "Imported ${importedTasks.size} tasks across $categoryCount categories."
            } else if (result.tabNames.isNotEmpty()) {
                sheetImportMessage = "Found tabs (${result.tabNames.joinToString(", ")}) but no task rows in them. " +
                    "Check that row 1 of each tab has a \"description\" column header."
            } else {
                sheetImportMessage = "Couldn't read any tabs from this Sheet. Check the URL and that sharing is " +
                    "\"Anyone with the link can view\"."
            }
        }
    }

    LaunchedEffect(
        promptTasks,
        savedStartHour,
        savedEndHour,
        savedPromptsPerDay
    ) {
        if (promptTasks.isEmpty()) {
            return@LaunchedEffect
        }
        while (true) {
            val delayMs = TaskDelivery.computeNextDelayMillis(context)
            if (delayMs == null) {
                delay(5 * 60_000L)
                continue
            }
            delay(delayMs)
            // Goes through the same shared function the background alarm receiver uses, so
            // whichever one is active (this loop while open, the alarm chain while closed) reads
            // and writes the exact same persisted queue/streak/window state.
            val added = TaskDelivery.deliverOrConsumeSlot(context)
            val prefs = TaskDelivery.prefs(context)
            taskQueue = readTaskQueue(prefs.getString("task_queue", "[]") ?: "[]")
            streak = prefs.getInt("streak", 0)
            longestStreak = prefs.getInt("longest_streak", 0)
            timeoutStreak = prefs.getInt("timeout_streak", 0)
            lastOutcome = runCatching {
                TaskLifecycleState.valueOf(prefs.getString("last_outcome", TaskLifecycleState.COMPLETED.name)!!)
            }.getOrDefault(TaskLifecycleState.COMPLETED)
            // A new window starting auto-clears a pause, which happens inside deliverOrConsumeSlot -
            // pick that up here so the Pause/Resume button doesn't show stale state.
            backgroundPromptsRunning = prefs.getBoolean("background_prompts_enabled", true)
            if (added) {
                showingScore = false
            }
        }
    }

    if (showingQrScanner) {
        QrScannerScreen(
            onResult = { scannedUrl ->
                showingQrScanner = false
                savedSheetUrl = scannedUrl
                onSheetUrlSaved(scannedUrl)
                runSheetImport(scannedUrl)
            },
            onCancel = { showingQrScanner = false }
        )
    } else if (showingTaskPool) {
        TaskPoolScreen(
            tasks = savedManagedTasks,
            onBack = { showingTaskPool = false },
            onTasksChanged = { updatedTasks ->
                savedManagedTasks = updatedTasks
                onManagedTasksSaved(updatedTasks)
            }
        )
    } else if (showingMyTasks) {
        MyTasksScreen(
            tasks = savedUserTasks,
            existingCategories = availableCategories,
            onBack = { showingMyTasks = false },
            onTasksChanged = { updatedTasks ->
                savedUserTasks = updatedTasks
                onUserTasksSaved(updatedTasks)
            }
        )
    } else if (showingSettings) {
        SettingsScreen(
            initialCategories = savedCategories,
            availableCategories = availableCategories,
            initialStartHour = savedStartHour,
            initialEndHour = savedEndHour,
            initialPromptsPerDay = savedPromptsPerDay,
            initialMaxQueueSize = savedMaxQueueSize,
            initialSheetUrl = savedSheetUrl,
            isImportingSheet = isImportingSheet,
            importMessage = sheetImportMessage,
            backgroundPromptsRunning = backgroundPromptsRunning,
            onOpenMyTasks = { showingMyTasks = true },
            onOpenTaskPool = { showingTaskPool = true },
            onOpenQrScanner = { showingQrScanner = true },
            onSyncSheet = { url -> runSheetImport(url) },
            onCancel = { if (setupComplete) showingSettings = false },
            onBackgroundPromptsChanged = { enabled ->
                backgroundPromptsRunning = enabled
                onBackgroundPromptsChanged(enabled)
            },
            onSave = { categories, start, end, prompts, queueSize, sheetUrl ->
                savedCategories = categories
                savedStartHour = start
                savedEndHour = end
                savedPromptsPerDay = prompts
                savedMaxQueueSize = queueSize
                savedSheetUrl = sheetUrl
                onSettingsSaved(categories, start, end, prompts, queueSize, sheetUrl)
                showingSettings = false
            }
        )
    } else if (showingScore) {
        ScoreScreen(
            streak = streak,
            longestStreak = longestStreak,
            completionLog = completionLog,
            outcome = lastOutcome,
            timeoutStreak = timeoutStreak,
            entryToken = scoreEntryToken,
            backgroundPromptsRunning = backgroundPromptsRunning,
            onOpenSettings = { showingSettings = true },
            onBackgroundPromptsChanged = { enabled ->
                backgroundPromptsRunning = enabled
                onBackgroundPromptsChanged(enabled)
            },
            onReturnToTaskList = {
                showingScore = false
            }
        )
    } else {
        TaskPromptScreen(
            taskEntries = visibleTaskEntries,
            streak = streak,
            maxQueueSize = savedMaxQueueSize,
            onOpenSettings = { showingSettings = true },
            onOpenScore = {
                scoreEntryToken++
                showingScore = true
            },
            onStart = { taskId ->
                persistTaskQueue(taskQueue.map { if (it.task.id == taskId) it.start() else it })
            },
            onComplete = { taskId ->
                val newStreak = streak + 1
                persistLastOutcome(TaskLifecycleState.COMPLETED)
                persistStreak(newStreak)
                persistCompletionLog(completionLog + CompletionRecord(System.currentTimeMillis(), newStreak))
                persistTaskQueue(taskQueue.map { if (it.task.id == taskId) it.complete() else it })
                scoreEntryToken++
                showingScore = true
            },
            onAbandon = { taskId ->
                persistLastOutcome(TaskLifecycleState.ABANDONED)
                persistStreak(0)
                persistTaskQueue(taskQueue.map { if (it.task.id == taskId) it.abandon() else it })
                scoreEntryToken++
                showingScore = true
            },
            onSubstitute = { taskId ->
                // Not a decline and not scored - the point is that nothing about this task's
                // outcome is being decided, it's simply being swapped for a different one in the
                // same queue slot.
                val queuedTaskIds = taskQueue.map { it.task.id }.toSet()
                val candidates = promptTasks.filter { it.id !in queuedTaskIds }.ifEmpty { promptTasks }
                val replacement = chooseWeightedTask(
                    tasks = candidates,
                    activeCategoryOrder = activeCategoryOrder,
                    previousTaskId = taskId
                )
                persistTaskQueue(taskQueue.map { if (it.task.id == taskId) TaskStackEntry(replacement) else it })
            },
            onNextPrompt = {
                persistTaskQueue(makeTaskStack(promptTasks, maxEntries = savedMaxQueueSize))
                showingScore = false
            },
            backgroundPromptsRunning = backgroundPromptsRunning,
            onBackgroundPromptsChanged = { enabled ->
                backgroundPromptsRunning = enabled
                onBackgroundPromptsChanged(enabled)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPromptScreen(
    taskEntries: List<TaskStackEntry>,
    streak: Int,
    maxQueueSize: Int,
    backgroundPromptsRunning: Boolean,
    onOpenSettings: () -> Unit,
    onOpenScore: () -> Unit,
    onBackgroundPromptsChanged: (Boolean) -> Unit,
    onStart: (String) -> Unit,
    onComplete: (String) -> Unit,
    onAbandon: (String) -> Unit,
    onSubstitute: (String) -> Unit,
    onNextPrompt: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("MicroTasking") },
            actions = {
                Button(onClick = onOpenScore) { Text("Score") }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        )

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            onClick = { onBackgroundPromptsChanged(!backgroundPromptsRunning) }
        ) {
            Text(if (backgroundPromptsRunning) "Pause task queue" else "Resume task queue")
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Task queue: ${taskEntries.size} active of $maxQueueSize",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            items(taskEntries, key = { it.task.id }) { entry ->
                val task = entry.task
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                ) {
                    Text(
                        text = task.description,
                        modifier = Modifier.padding(vertical = 6.dp),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "State: ${taskStateLabel(entry.state)} • ${task.durationMinutes} min",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    when (entry.state) {
                        TaskLifecycleState.READY -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Button(modifier = Modifier.weight(1f), onClick = { onStart(task.id) }) { Text("Start") }
                                Button(modifier = Modifier.weight(1f), onClick = { onSubstitute(task.id) }) { Text("Substitute") }
                            }
                        }
                        TaskLifecycleState.STARTED -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Button(modifier = Modifier.weight(1f), onClick = { onComplete(task.id) }) { Text("Done") }
                                Button(modifier = Modifier.weight(1f), onClick = { onAbandon(task.id) }) { Text("Abandon") }
                            }
                        }
                        else -> {
                            Button(modifier = Modifier.fillMaxWidth(), onClick = onNextPrompt) { Text("Next task") }
                        }
                    }
                }
            }

            if (streak > 0) {
                item {
                    Text(
                        text = "$streak in a row",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(
    streak: Int,
    longestStreak: Int,
    completionLog: List<CompletionRecord>,
    outcome: TaskLifecycleState,
    timeoutStreak: Int,
    entryToken: Int,
    backgroundPromptsRunning: Boolean,
    onOpenSettings: () -> Unit,
    onBackgroundPromptsChanged: (Boolean) -> Unit,
    onReturnToTaskList: () -> Unit
) {
    LaunchedEffect(entryToken) {
        delay(3_000)
        onReturnToTaskList()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("MicroTasking") },
            actions = {
                Button(onClick = onReturnToTaskList) { Text("Task List") }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        )
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            onClick = { onBackgroundPromptsChanged(!backgroundPromptsRunning) }
        ) {
            Text(if (backgroundPromptsRunning) "Pause task queue" else "Resume task queue")
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val (headlineText, headlineColor) = when (outcome) {
                TaskLifecycleState.ABANDONED -> "Task abandoned" to MaterialTheme.colorScheme.error
                TaskLifecycleState.TIMED_OUT -> "Task timed out" to MaterialTheme.colorScheme.error
                else -> "Task completed" to MaterialTheme.colorScheme.primary
            }
            Text(
                text = headlineText,
                style = MaterialTheme.typography.headlineMedium,
                color = headlineColor
            )
            if (outcome == TaskLifecycleState.TIMED_OUT) {
                if (timeoutStreak > 0) {
                    Text(
                        text = "$timeoutStreak in a row",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else if (streak > 0) {
                Text(
                    text = "$streak in a row",
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val nowMs = System.currentTimeMillis()
            val now = LocalDateTime.now()
            val todaySince = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val todayLongest = longestStreakSince(completionLog, todaySince)
            val weekLongest = longestStreakSince(completionLog, nowMs - weekScoreWindowMillis())
            val monthLongest = longestStreakSince(completionLog, nowMs - monthScoreWindowMillis(now))
            Text(
                text = "Longest streak",
                modifier = Modifier.padding(top = 32.dp),
                style = MaterialTheme.typography.labelLarge
            )
            Text("Today: $todayLongest")
            Text("This week: $weekLongest")
            Text("This month: $monthLongest")
            Text("All time: $longestStreak")
            if (!backgroundPromptsRunning) {
                Text(
                    text = "Background prompts are stopped.",
                    modifier = Modifier.padding(top = 24.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    initialCategories: Set<String>,
    availableCategories: List<String>,
    initialStartHour: String,
    initialEndHour: String,
    initialPromptsPerDay: String,
    initialMaxQueueSize: Int,
    initialSheetUrl: String = "",
    isImportingSheet: Boolean = false,
    importMessage: String? = null,
    backgroundPromptsRunning: Boolean,
    onOpenMyTasks: () -> Unit,
    onOpenTaskPool: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onSyncSheet: (String) -> Unit,
    onCancel: () -> Unit,
    onBackgroundPromptsChanged: (Boolean) -> Unit,
    onSave: (Set<String>, String, String, String, Int, String) -> Unit
) {
    var selectedCategories by remember { mutableStateOf(initialCategories) }
    var startHour by remember { mutableStateOf(initialStartHour) }
    var endHour by remember { mutableStateOf(initialEndHour) }
    var promptsPerDay by remember { mutableStateOf(initialPromptsPerDay) }
    var maxQueueSize by remember { mutableStateOf(initialMaxQueueSize.toString()) }
    var sheetUrl by remember { mutableStateOf(initialSheetUrl) }
    var categoriesExpanded by remember { mutableStateOf(true) }
    var scheduleExpanded by remember { mutableStateOf(true) }
    var externalPoolExpanded by remember { mutableStateOf(true) }
    var localTasksExpanded by remember { mutableStateOf(true) }
    var aboutExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    @Composable
    fun sectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onToggle) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Settings",
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectionHeader("Import External Task Pool", externalPoolExpanded) {
                            externalPoolExpanded = !externalPoolExpanded
                        }
                        if (externalPoolExpanded) {
                            Text(
                                "Paste your Google Sheet URL into the onboarding page, then tap Scan QR Code below to register it here with your phone's camera. Each tab in the sheet (except a tab named \"README\") becomes a task category. Tasks import automatically right after a scan; use Update Tasks any time afterward to re-sync.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = sheetUrl,
                                onValueChange = { sheetUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Google Sheet / CSV URL") },
                                placeholder = { Text("https://docs.google.com/spreadsheets/d/...") },
                                singleLine = true
                            )
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onOpenQrScanner
                            ) {
                                Text("Scan QR Code")
                            }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = sheetUrl.isNotBlank() && !isImportingSheet,
                                onClick = { onSyncSheet(sheetUrl.trim()) }
                            ) {
                                Text(if (isImportingSheet) "Updating..." else "Update Tasks")
                            }
                            if (importMessage != null) {
                                Text(
                                    importMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectionHeader("Active Categories", categoriesExpanded) {
                            categoriesExpanded = !categoriesExpanded
                        }
                        if (categoriesExpanded) {
                            Text(
                                "Choose which task categories are eligible for daily task prompts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (availableCategories.isEmpty()) {
                                Text(
                                    "No categories yet. Import your Google Sheet above to add categories (one per tab).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            availableCategories.forEach { category ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = category in selectedCategories,
                                        onCheckedChange = { checked ->
                                            selectedCategories = if (checked) selectedCategories + category else selectedCategories - category
                                        }
                                    )
                                    Text(category)
                                }
                            }
                        }
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        sectionHeader("Prompting Schedule", scheduleExpanded) {
                            scheduleExpanded = !scheduleExpanded
                        }
                        if (scheduleExpanded) {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onBackgroundPromptsChanged(!backgroundPromptsRunning) }
                            ) {
                                Text(if (backgroundPromptsRunning) "Pause task queue" else "Resume task queue")
                            }
                            Text(
                                "Configure your active daily window and prompt frequency.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = startHour,
                                onValueChange = { startHour = it.filter(Char::isDigit).take(2) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Start hour (0-24)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) })
                            )
                            OutlinedTextField(
                                value = endHour,
                                onValueChange = { endHour = it.filter(Char::isDigit).take(2) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("End hour (0-24)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) })
                            )
                            Text(
                                "Start 0 and end 24 means the active window never ends, so tasks are never timed out.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = promptsPerDay,
                                onValueChange = { promptsPerDay = it.filter(Char::isDigit) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Prompts per day (0 or more, no upper limit)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) })
                            )
                            OutlinedTextField(
                                value = maxQueueSize,
                                onValueChange = { maxQueueSize = it.filter(Char::isDigit) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Task queue size") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                            )
                        }
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        sectionHeader("Local Task Management", localTasksExpanded) {
                            localTasksExpanded = !localTasksExpanded
                        }
                        if (localTasksExpanded) {
                            Text(
                                "View, create, or edit your local custom tasks and task pool.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(modifier = Modifier.weight(1f), onClick = onOpenMyTasks) {
                                    Text("My Tasks")
                                }
                                Button(modifier = Modifier.weight(1f), onClick = onOpenTaskPool) {
                                    Text("Task Pool")
                                }
                            }
                        }
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectionHeader("About", aboutExpanded) {
                            aboutExpanded = !aboutExpanded
                        }
                        if (aboutExpanded) {
                            Text(
                                "v${BuildConfig.VERSION_BASE}-${BuildConfig.BUILD_NUMBER}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "${BuildConfig.BUILD_TIMESTAMP} - ${BuildConfig.GIT_SHORT_SHA} - ${BuildConfig.GIT_BRANCH}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        Surface(shadowElevation = 4.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onCancel) {
                    Text("Cancel")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = selectedCategories.isNotEmpty() || availableCategories.isEmpty(),
                    onClick = {
                        onSave(
                            selectedCategories,
                            (startHour.toIntOrNull() ?: 0).coerceIn(0, 24).toString(),
                            (endHour.toIntOrNull() ?: 24).coerceIn(0, 24).toString(),
                            (promptsPerDay.toIntOrNull() ?: 0).coerceAtLeast(0).toString(),
                            maxQueueSize.toIntOrNull()?.coerceAtLeast(1) ?: 3,
                            sheetUrl
                        )
                    }
                ) {
                    Text("Save Settings")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Scan Sheet QR Code") },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel scan")
                }
            }
        )
        if (hasCameraPermission) {
            var hasScanned by remember { mutableStateOf(false) }
            var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
            DisposableEffect(Unit) {
                onDispose { cameraProvider?.unbindAll() }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val scanner = BarcodeScanning.getClient()
                    val executor = ContextCompat.getMainExecutor(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        cameraProvider = provider
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null && !hasScanned) {
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                                        if (!hasScanned && !value.isNullOrBlank()) {
                                            hasScanned = true
                                            onResult(value)
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }, executor)
                    previewView
                }
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera permission is required to scan a QR code.", modifier = Modifier.padding(bottom = 12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera permission")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPoolScreen(
    tasks: List<ManagedTask>,
    onBack: () -> Unit,
    onTasksChanged: (List<ManagedTask>) -> Unit
) {
    val poolCategories = tasks.map { it.category }.distinct()
    var categoryFilter by remember { mutableStateOf("All categories") }
    var description by remember { mutableStateOf("") }
    var newTaskCategory by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("5") }
    var editingTask by remember { mutableStateOf<ManagedTask?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    val visibleTasks = tasks.filter { categoryFilter == "All categories" || it.category == categoryFilter }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Task Pool") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back to settings")
                }
            }
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Categories and active task counts",
                    modifier = Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (poolCategories.isEmpty()) {
                item {
                    Text(
                        "No categories yet. Import a Google Sheet from Settings, or add a task below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(poolCategories) { category ->
                Text("$category: ${tasks.count { it.category == category && it.enabled && !it.temporarilyUnavailable && !it.neverSuggest }} active")
            }
            item {
                Button(modifier = Modifier.fillMaxWidth(), onClick = { menuExpanded = true }) {
                    Text("View: $categoryFilter")
                }
            }
            item {
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    (listOf("All categories") + poolCategories).forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = {
                            categoryFilter = option
                            menuExpanded = false
                        })
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New task") }
                )
                OutlinedTextField(
                    value = newTaskCategory,
                    onValueChange = { newTaskCategory = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Category") },
                    placeholder = { Text("e.g. Cleaning") }
                )
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Duration: 5, 10, or 15 minutes") }
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = description.isNotBlank() && newTaskCategory.isNotBlank() && duration.toIntOrNull() in setOf(5, 10, 15),
                    onClick = {
                        onTasksChanged(tasks + ManagedTask(
                            id = "custom-${System.currentTimeMillis()}",
                            description = description.trim(),
                            category = newTaskCategory.trim(),
                            durationMinutes = duration.toInt(),
                            builtIn = false
                        ))
                        description = ""
                        newTaskCategory = ""
                        duration = "5"
                    }
                ) {
                    Text("Add task")
                }
                Text("Tasks", style = MaterialTheme.typography.titleLarge)
            }
            items(visibleTasks, key = { it.id }) { task ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.description)
                            Text("${task.category} - ${task.durationMinutes} min", style = MaterialTheme.typography.bodySmall)
                            Text(
                                when {
                                    task.neverSuggest -> "Never suggest"
                                    task.temporarilyUnavailable -> "Temporarily unavailable"
                                    !task.enabled -> "Disabled"
                                    else -> if (task.builtIn) "Built-in task" else "Your task"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { editingTask = task }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit ${task.description}")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = task.enabled, onCheckedChange = { checked ->
                            onTasksChanged(tasks.map { if (it.id == task.id) it.copy(enabled = checked) else it })
                        })
                        Text("Enabled")
                        Checkbox(checked = task.temporarilyUnavailable, onCheckedChange = { checked ->
                            onTasksChanged(tasks.map { if (it.id == task.id) it.copy(temporarilyUnavailable = checked) else it })
                        })
                        Text("Unavailable")
                        Checkbox(checked = task.neverSuggest, onCheckedChange = { checked ->
                            onTasksChanged(tasks.map { if (it.id == task.id) it.copy(neverSuggest = checked) else it })
                        })
                        Text("Never")
                    }
                }
            }
        }
    }
    editingTask?.let { task ->
        TaskEditorDialog(
            task = task,
            onDismiss = { editingTask = null },
            onSave = { updatedTask ->
                onTasksChanged(tasks.map { if (it.id == updatedTask.id) updatedTask else it })
                editingTask = null
            }
        )
    }
}

@Composable
fun TaskEditorDialog(task: ManagedTask, onDismiss: () -> Unit, onSave: (ManagedTask) -> Unit) {
    var description by remember(task.id) { mutableStateOf(task.description) }
    var duration by remember(task.id) { mutableStateOf(task.durationMinutes.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Task") })
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it.filter(Char::isDigit) },
                    label = { Text("Duration: 5, 10, or 15 minutes") }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = description.isNotBlank() && duration.toIntOrNull() in setOf(5, 10, 15),
                onClick = { onSave(task.copy(description = description.trim(), durationMinutes = duration.toInt())) }
            ) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTasksScreen(
    tasks: List<UserTask>,
    existingCategories: List<String>,
    onBack: () -> Unit,
    onTasksChanged: (List<UserTask>) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(existingCategories.firstOrNull() ?: "") }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("My Tasks") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back to settings")
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add tasks you want MicroTasking to prompt you with.")
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("New task") }
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Category") },
                placeholder = { Text("e.g. Cleaning") },
                trailingIcon = if (existingCategories.isNotEmpty()) {
                    { IconButton(onClick = { categoryMenuExpanded = true }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Pick existing category") } }
                } else null
            )
            DropdownMenu(
                expanded = categoryMenuExpanded,
                onDismissRequest = { categoryMenuExpanded = false }
            ) {
                existingCategories.forEach { categoryOption ->
                    DropdownMenuItem(
                        text = { Text(categoryOption) },
                        onClick = {
                            category = categoryOption
                            categoryMenuExpanded = false
                        }
                    )
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = description.isNotBlank() && category.isNotBlank(),
                onClick = {
                    onTasksChanged(tasks + UserTask(description.trim(), category.trim(), true))
                    description = ""
                }
            ) {
                Text("Add task")
            }
            Text("Your tasks", style = MaterialTheme.typography.titleLarge)
            if (tasks.isEmpty()) {
                Text("No custom tasks yet.")
            }
            tasks.forEachIndexed { index, task ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = task.enabled,
                        onCheckedChange = { enabled ->
                            onTasksChanged(tasks.mapIndexed { taskIndex, existingTask ->
                                if (taskIndex == index) existingTask.copy(enabled = enabled) else existingTask
                            })
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.description)
                        Text(task.category, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        onTasksChanged(tasks.filterIndexed { taskIndex, _ -> taskIndex != index })
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete ${task.description}")
                    }
                }
            }
        }
    }
}

fun normalizeGoogleSheetCsvUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return trimmed

    return runCatching {
        val withoutHash = trimmed.substringBefore('#')
        val fragment = trimmed.substringAfter('#', "")
        val withoutUsp = withoutHash.replace("?usp=sharing", "")
        val parsed = URL(withoutUsp)
        val path = parsed.path
        val host = parsed.host ?: ""

        val queryParams = (parsed.query ?: "")
            .split("&")
            .filter { it.isNotBlank() }
            .associate { part ->
                val pieces = part.split("=", limit = 2)
                val key = pieces.firstOrNull() ?: ""
                val value = pieces.getOrNull(1) ?: ""
                key to value
            }

        val fragmentParams = fragment
            .split("&")
            .filter { it.isNotBlank() }
            .associate { part ->
                val pieces = part.split("=", limit = 2)
                val key = pieces.firstOrNull() ?: ""
                val value = pieces.getOrNull(1) ?: ""
                key to value
            }

        val gid = queryParams["gid"] ?: fragmentParams["gid"] ?: ""
        if (host.endsWith("docs.google.com") && path.contains("/spreadsheets/") && path.contains("/d/")) {
            val base = withoutUsp
                .replace("/edit", "")
                .replace("?usp=sharing", "")
                .replace("&usp=sharing", "")
                .trimEnd('?')
            if (gid.isNotBlank()) {
                "$base/export?format=csv&gid=$gid"
            } else {
                "$base/export?format=csv"
            }
        } else {
            trimmed
        }
    }.getOrDefault(trimmed)
}

fun parseExternalTaskCsv(csvText: String, categoryName: String): List<ManagedTask> {
    if (csvText.isBlank()) return emptyList()

    val rows = csvText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { row ->
            val cells = row.split(",").map { value ->
                value.trim().removeSurrounding("\"", "\"").trim()
            }
            cells
        }
        .toList()

    if (rows.isEmpty()) return emptyList()

    val header = rows.first().map { it.lowercase() }
    val descriptionIndex = header.indexOfFirst { it.contains("description") }
    val enabledIndex = header.indexOfFirst { it.contains("checkbox") || it.contains("enabled") }
    val linkIndex = header.indexOfFirst { it.contains("link") || it.contains("url") }

    if (descriptionIndex == -1) return emptyList()

    return rows.drop(1).mapNotNull { row ->
        if (row.size <= descriptionIndex) return@mapNotNull null
        val description = row[descriptionIndex].trim()
        if (description.isEmpty()) return@mapNotNull null
        val enabled = row.getOrNull(enabledIndex)?.equals("true", ignoreCase = true)
            ?: true
        val link = row.getOrNull(linkIndex).orEmpty().trim()
        ManagedTask(
            id = "external-${categoryName}-${description.hashCode()}-${System.currentTimeMillis()}",
            description = description,
            category = categoryName,
            durationMinutes = 5,
            builtIn = false,
            enabled = enabled,
            temporarilyUnavailable = false,
            neverSuggest = false
        )
    }
}

private fun extractGoogleSheetId(url: String): String? =
    Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)").find(url)?.groupValues?.getOrNull(1)

private fun unescapeXmlEntities(text: String): String = text
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")

/**
 * Lists the spreadsheet's tab names in order by downloading the full workbook as .xlsx (a
 * plain still-supported export, unlike the old GData worksheets feed below) and reading the
 * sheet names straight out of the zip's xl/workbook.xml entry - no need to parse actual cell
 * data out of the xlsx, since tab data is still fetched per-name via the gviz CSV export.
 */
private fun fetchSheetTabNamesViaXlsx(spreadsheetId: String): List<String> = runCatching {
    val url = URL("https://docs.google.com/spreadsheets/d/$spreadsheetId/export?format=xlsx")
    java.util.zip.ZipInputStream(url.openStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "xl/workbook.xml") {
                val xml = zip.readBytes().toString(Charsets.UTF_8)
                return@runCatching Regex("<sheet[^>]*\\sname=\"([^\"]+)\"").findAll(xml)
                    .map { unescapeXmlEntities(it.groupValues[1]) }
                    .toList()
            }
            entry = zip.nextEntry
        }
        emptyList()
    }
}.getOrDefault(emptyList())

/** Lists the spreadsheet's tab names via the legacy public worksheet feed - kept as a secondary attempt since Google has deprecated this GData API for many accounts. */
private fun fetchSheetTabNames(spreadsheetId: String): List<String> = runCatching {
    val feedUrl = "https://spreadsheets.google.com/feeds/worksheets/$spreadsheetId/public/basic?alt=json"
    val feed = JSONObject(URL(feedUrl).readText()).optJSONObject("feed") ?: return@runCatching emptyList()
    val entries = when (val entry = feed.opt("entry")) {
        is JSONArray -> entry
        is JSONObject -> JSONArray().put(entry)
        else -> JSONArray()
    }
    List(entries.length()) { index -> entries.getJSONObject(index).getJSONObject("title").getString("\$t") }
}.getOrDefault(emptyList())

/** Fetches one tab's rows as CSV, addressed by tab name rather than gid. */
private fun fetchSheetTabCsv(spreadsheetId: String, tabName: String): String = runCatching {
    val encodedName = URLEncoder.encode(tabName, "UTF-8")
    val url = "https://docs.google.com/spreadsheets/d/$spreadsheetId/gviz/tq?tqx=out:csv&sheet=$encodedName"
    URL(url).readText()
}.getOrDefault("")

data class SheetImportResult(val tasks: List<ManagedTask>, val tabNames: List<String>)

fun importExternalTasksFromSheet(url: String): SheetImportResult {
    val spreadsheetId = extractGoogleSheetId(url) ?: return SheetImportResult(emptyList(), emptyList())
    val tabNames = fetchSheetTabNamesViaXlsx(spreadsheetId)
        .ifEmpty { fetchSheetTabNames(spreadsheetId) }
        .filter { !it.equals("README", ignoreCase = true) }

    if (tabNames.isNotEmpty()) {
        val tasks = tabNames.flatMap { tabName -> parseExternalTaskCsv(fetchSheetTabCsv(spreadsheetId, tabName), tabName) }
        return SheetImportResult(tasks, tabNames)
    }

    // Tab enumeration unavailable (e.g. sharing settings blocked it) - fall back to a single
    // CSV export so import still works, just without per-tab categories.
    val fallbackTasks = runCatching {
        val csv = URL(normalizeGoogleSheetCsvUrl(url)).readText()
        parseExternalTaskCsv(csv, "Imported")
    }.getOrDefault(emptyList())
    return SheetImportResult(fallbackTasks, emptyList())
}


// A calm, low-contrast palette on purpose: a soft gray canvas (not stark white) so the app doesn't
// read as urgent, a green/teal primary for the everyday/positive state, and a muted coral - not
// Material's default alarm red - for the abandoned/timed-out states so a bad outcome still reads
// clearly without feeling punishing.
private val microTaskingColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D6B),
    onPrimary = Color.White,
    secondary = Color(0xFF5FA88F),
    onSecondary = Color.White,
    background = Color(0xFFEFF2F1),
    surface = Color.White,
    error = Color(0xFFE2725B),
    onError = Color.White
)

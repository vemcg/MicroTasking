package com.microtasking.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material3.lightColorScheme
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("microtasking_settings", MODE_PRIVATE)
        preferences.edit().putBoolean("app_in_foreground", true).apply()
        PromptScheduler.cancel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
        if (preferences.getBoolean("setup_complete", false)) {
            preferences.edit().putBoolean("background_prompts_enabled", true).apply()
            PromptScheduler.scheduleNext(this, preferences.getBoolean("rapid_test_mode", false))
        }
        setContent {
            MaterialTheme(colorScheme = microTaskingColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    MicroTaskingApp(
                        setupComplete = preferences.getBoolean("setup_complete", false),
                        selectedCategories = preferences.getStringSet(
                            "selected_categories",
                            setOf("Decluttering", "Cleaning")
                        ) ?: emptySet(),
                        startHour = preferences.getString("start_hour", "9") ?: "9",
                        endHour = preferences.getString("end_hour", "21") ?: "21",
                        promptsPerDay = preferences.getString("prompts_per_day", "6") ?: "6",
                        rapidTestMode = preferences.getBoolean("rapid_test_mode", false),
                        maxQueueSize = preferences.getInt("max_task_queue_size", 3),
                        externalSheetUrl = preferences.getString("external_sheet_url", "") ?: "",
                        managedTasks = readManagedTasks(
                            preferences.getString("managed_tasks", "[]") ?: "[]"
                        ),
                        seedTasks = loadSeedTasks(this),
                        declineCounts = readDeclineCounts(
                            preferences.getString("task_decline_counts", "{}") ?: "{}"
                        ),
                        userTasks = readUserTasks(preferences.getString("user_tasks", "[]") ?: "[]"),
                        onSettingsSaved = { categories, start, end, prompts, rapidMode, maxQueueSize, sheetUrl ->
                            preferences.edit()
                                .putBoolean("setup_complete", true)
                                .putStringSet("selected_categories", categories)
                                .putString("start_hour", start)
                                .putString("end_hour", end)
                                .putString("prompts_per_day", prompts)
                                .putBoolean("rapid_test_mode", rapidMode)
                                .putInt("max_task_queue_size", maxQueueSize)
                                .putString("external_sheet_url", sheetUrl)
                                .putBoolean("background_prompts_enabled", true)
                                .apply()
                            PromptScheduler.scheduleNext(this, rapidMode)
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
                        onBackgroundPromptsChanged = { enabled ->
                            preferences.edit().putBoolean("background_prompts_enabled", enabled).apply()
                            if (enabled) {
                                PromptScheduler.scheduleNext(
                                    this,
                                    preferences.getBoolean("rapid_test_mode", false)
                                )
                            } else {
                                PromptScheduler.cancel(this)
                            }
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
    }

    override fun onStop() {
        super.onStop()
        getSharedPreferences("microtasking_settings", MODE_PRIVATE)
            .edit()
            .putBoolean("app_in_foreground", false)
            .apply()
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
    rapidTestMode: Boolean,
    maxQueueSize: Int,
    externalSheetUrl: String,
    userTasks: List<UserTask>,
    managedTasks: List<ManagedTask>,
    seedTasks: List<ManagedTask>,
    declineCounts: Map<String, Int>,
    onSettingsSaved: (Set<String>, String, String, String, Boolean, Int, String) -> Unit,
    onUserTasksSaved: (List<UserTask>) -> Unit,
    onManagedTasksSaved: (List<ManagedTask>) -> Unit,
    onDeclineCountsSaved: (Map<String, Int>) -> Unit,
    onBackgroundPromptsChanged: (Boolean) -> Unit
) {
    var showingSettings by remember { mutableStateOf(!setupComplete) }
    var showingMyTasks by remember { mutableStateOf(false) }
    var showingTaskPool by remember { mutableStateOf(false) }
    var showingScore by remember { mutableStateOf(false) }
    var savedCategories by remember { mutableStateOf(selectedCategories) }
    var savedStartHour by remember { mutableStateOf(startHour) }
    var savedEndHour by remember { mutableStateOf(endHour) }
    var savedPromptsPerDay by remember { mutableStateOf(promptsPerDay) }
    var savedRapidTestMode by remember { mutableStateOf(rapidTestMode) }
    var savedMaxQueueSize by remember { mutableIntStateOf(maxQueueSize) }
    var savedSheetUrl by remember { mutableStateOf(externalSheetUrl) }
    var savedUserTasks by remember { mutableStateOf(userTasks) }
    var savedManagedTasks by remember {
        mutableStateOf(if (managedTasks.isEmpty()) seedTasks else managedTasks)
    }
    var savedDeclineCounts by remember { mutableStateOf(declineCounts) }
    var completedCount by remember { mutableIntStateOf(0) }
    var attemptedCount by remember { mutableIntStateOf(0) }
    var lastTaskCompleted by remember { mutableStateOf(true) }
    var backgroundPromptsRunning by remember { mutableStateOf(true) }
    val promptTasks = eligiblePromptTasks(savedManagedTasks, savedUserTasks, savedCategories)
    var taskQueue by remember(promptTasks, savedMaxQueueSize) {
        mutableStateOf(makeTaskStack(promptTasks, maxEntries = savedMaxQueueSize))
    }
    val actionableTasks = taskQueue.filter { it.isActionable() }
    val visibleTaskEntries = actionableTasks.ifEmpty {
        emptyList()
    }
    val shouldShowScoreInsteadOfTasks = showingScore || visibleTaskEntries.isEmpty()

    fun receiveQueuedTask(task: ManagedTask) {
        val activeTasks = taskQueue.filter { it.isActionable() }
        if (activeTasks.size >= savedMaxQueueSize) {
            attemptedCount++
            lastTaskCompleted = false
        }
        taskQueue = activeTasks.takeLast(savedMaxQueueSize - 1) + TaskStackEntry(task)
        showingScore = false
    }

    LaunchedEffect(savedRapidTestMode, backgroundPromptsRunning, promptTasks) {
        if (!savedRapidTestMode || !backgroundPromptsRunning || promptTasks.isEmpty()) {
            return@LaunchedEffect
        }
        while (true) {
            delay(15_000)
            val nextTask = chooseWeightedTask(
                tasks = promptTasks,
                declineCounts = savedDeclineCounts,
                previousTaskId = taskQueue.lastOrNull { it.isActionable() }?.task?.id
            )
            receiveQueuedTask(nextTask)
        }
    }

    if (showingTaskPool) {
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
            onBack = { showingMyTasks = false },
            onTasksChanged = { updatedTasks ->
                savedUserTasks = updatedTasks
                onUserTasksSaved(updatedTasks)
            }
        )
    } else if (showingSettings) {
        SettingsScreen(
            initialCategories = savedCategories,
            initialStartHour = savedStartHour,
            initialEndHour = savedEndHour,
            initialPromptsPerDay = savedPromptsPerDay,
            initialRapidTestMode = savedRapidTestMode,
            initialMaxQueueSize = savedMaxQueueSize,
            initialSheetUrl = savedSheetUrl,
            onOpenMyTasks = { showingMyTasks = true },
            onOpenTaskPool = { showingTaskPool = true },
            onSyncSheet = { url ->
                val importedTasks = importExternalTasksFromSheet(url)
                if (importedTasks.isNotEmpty()) {
                    savedManagedTasks = importedTasks + savedManagedTasks.filter { task ->
                        task.category !in importedTasks.map { it.category }
                    }
                    onManagedTasksSaved(savedManagedTasks)
                }
            },
            onSave = { categories, start, end, prompts, rapidMode, queueSize, sheetUrl ->
                savedCategories = categories
                savedStartHour = start
                savedEndHour = end
                savedPromptsPerDay = prompts
                savedRapidTestMode = rapidMode
                savedMaxQueueSize = queueSize
                savedSheetUrl = sheetUrl
                onSettingsSaved(categories, start, end, prompts, rapidMode, queueSize, sheetUrl)
                showingSettings = false
            }
        )
    } else if (showingScore || shouldShowScoreInsteadOfTasks) {
        ScoreScreen(
            completedCount = completedCount,
            attemptedCount = attemptedCount,
            taskCompleted = lastTaskCompleted,
            hasQueuedTasks = visibleTaskEntries.isNotEmpty(),
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
            completedCount = completedCount,
            rapidTestMode = savedRapidTestMode && backgroundPromptsRunning,
            onOpenSettings = { showingSettings = true },
            onStart = { taskId ->
                taskQueue = taskQueue.map {
                    if (it.task.id == taskId) it.start() else it
                }
            },
            onComplete = { taskId ->
                completedCount++
                attemptedCount++
                lastTaskCompleted = true
                taskQueue = taskQueue.map {
                    if (it.task.id == taskId) it.complete() else it
                }
                showingScore = true
            },
            onAbandon = { taskId ->
                attemptedCount++
                lastTaskCompleted = false
                taskQueue = taskQueue.map {
                    if (it.task.id == taskId) it.abandon() else it
                }
                showingScore = true
            },
            onDefer = { taskId, days ->
                taskQueue = taskQueue.map {
                    if (it.task.id == taskId) it.defer(days) else it
                }
                savedDeclineCounts = savedDeclineCounts + (
                    taskId to (savedDeclineCounts[taskId] ?: 0) + 1
                )
                onDeclineCountsSaved(savedDeclineCounts)
            },
            onChooseAlternative = { taskId ->
                savedDeclineCounts = savedDeclineCounts + (
                    taskId to (savedDeclineCounts[taskId] ?: 0) + 1
                )
                onDeclineCountsSaved(savedDeclineCounts)
                attemptedCount++
                lastTaskCompleted = false
                taskQueue = taskQueue.filter { it.task.id != taskId }
                showingScore = true
            },
            onNextPrompt = {
                taskQueue = makeTaskStack(promptTasks, maxEntries = savedMaxQueueSize)
                showingScore = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPromptScreen(
    taskEntries: List<TaskStackEntry>,
    completedCount: Int,
    rapidTestMode: Boolean,
    onOpenSettings: () -> Unit,
    onStart: (String) -> Unit,
    onComplete: (String) -> Unit,
    onAbandon: (String) -> Unit,
    onDefer: (String, Long) -> Unit,
    onChooseAlternative: (String) -> Unit,
    onNextPrompt: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("MicroTasking") },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Task queue: ${taskEntries.size} active", style = MaterialTheme.typography.labelLarge)
                if (rapidTestMode) {
                    Text(
                        text = "Rapid test mode is active",
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
                                Button(modifier = Modifier.weight(1f), onClick = { onDefer(task.id, 7) }) { Text("Defer 1w") }
                                Button(modifier = Modifier.weight(1f), onClick = { onChooseAlternative(task.id) }) { Text("Reject") }
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

            item {
                Text(
                    text = "Completed this session: $completedCount",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(
    completedCount: Int,
    attemptedCount: Int,
    taskCompleted: Boolean,
    hasQueuedTasks: Boolean,
    backgroundPromptsRunning: Boolean,
    onOpenSettings: () -> Unit,
    onBackgroundPromptsChanged: (Boolean) -> Unit,
    onReturnToTaskList: () -> Unit
) {
    val scorePercent = if (attemptedCount == 0) 0 else completedCount * 100 / attemptedCount
    val scoreColor = when {
        scorePercent >= 80 -> Color(0xFF2E7D32)
        scorePercent >= 50 -> Color(0xFF8A6000)
        else -> Color(0xFFC62828)
    }
    LaunchedEffect(attemptedCount, hasQueuedTasks) {
        if (hasQueuedTasks) {
            delay(5_000)
            onReturnToTaskList()
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("MicroTasking") },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (taskCompleted) "Task completed" else "Task abandoned",
                style = MaterialTheme.typography.headlineMedium,
                color = if (taskCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Text(
                text = "Score",
                modifier = Modifier.padding(top = 32.dp),
                style = MaterialTheme.typography.labelLarge
            )
            Text("Today: $scorePercent%", color = scoreColor)
            Text("This week: $scorePercent%", color = scoreColor)
            Text("This month: $scorePercent%", color = scoreColor)
            Text("All time: $scorePercent%", color = scoreColor)
            Text(
                text = "Completed this session: $completedCount",
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            if (!backgroundPromptsRunning) {
                Text(
                    text = "Background prompts are stopped.",
                    modifier = Modifier.padding(top = 24.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                onClick = onReturnToTaskList
            ) {
                Text("Back to task list")
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                onClick = { onBackgroundPromptsChanged(!backgroundPromptsRunning) }
            ) {
                Text(if (backgroundPromptsRunning) "Stop background prompts" else "Start background prompts")
            }
        }
    }
}

@Composable
fun SettingsScreen(
    initialCategories: Set<String>,
    initialStartHour: String,
    initialEndHour: String,
    initialPromptsPerDay: String,
    initialRapidTestMode: Boolean,
    initialMaxQueueSize: Int,
    initialSheetUrl: String = "",
    onOpenMyTasks: () -> Unit,
    onOpenTaskPool: () -> Unit,
    onSyncSheet: (String) -> Unit,
    onSave: (Set<String>, String, String, String, Boolean, Int, String) -> Unit
) {
    val categories = listOf("Decluttering", "Cleaning", "Paperwork", "Admin/Paperwork", "Finances", "Health", "Errands")
    var selectedCategories by remember { mutableStateOf(initialCategories) }
    var startHour by remember { mutableStateOf(initialStartHour) }
    var endHour by remember { mutableStateOf(initialEndHour) }
    var promptsPerDay by remember { mutableStateOf(initialPromptsPerDay) }
    var rapidTestMode by remember { mutableStateOf(initialRapidTestMode) }
    var maxQueueSize by remember { mutableStateOf(initialMaxQueueSize.toString()) }
    var sheetUrl by remember { mutableStateOf(initialSheetUrl) }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var qrEntry by remember { mutableStateOf(initialSheetUrl) }
    var externalImportMessage by remember { mutableStateOf<String?>(null) }
    var categoriesExpanded by remember { mutableStateOf(true) }
    var scheduleExpanded by remember { mutableStateOf(true) }
    var externalPoolExpanded by remember { mutableStateOf(true) }
    var localTasksExpanded by remember { mutableStateOf(true) }
    var testingExpanded by remember { mutableStateOf(false) }
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
                                "Paste your Google Sheet URL first. The onboarding page will generate its QR code, which you can scan here to load the URL.",
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
                                onClick = {
                                    qrEntry = sheetUrl
                                    showQrScannerDialog = true
                                }
                            ) {
                                Text("Scan QR Code to Load URL")
                            }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = sheetUrl.isNotBlank(),
                                onClick = {
                                    onSyncSheet(sheetUrl.trim())
                                    externalImportMessage = "Import started."
                                }
                            ) {
                                Text("Import / Synchronize")
                            }
                            if (externalImportMessage != null) {
                                Text(
                                    externalImportMessage!!,
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
                            categories.forEach { category ->
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
                            Text(
                                "Configure your active daily window and prompt frequency.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = startHour,
                                onValueChange = { startHour = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Start hour (0-23)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) })
                            )
                            OutlinedTextField(
                                value = endHour,
                                onValueChange = { endHour = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("End hour (0-23)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) })
                            )
                            OutlinedTextField(
                                value = promptsPerDay,
                                onValueChange = { promptsPerDay = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Prompts per day") },
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
                        sectionHeader("Testing & Development", testingExpanded) {
                            testingExpanded = !testingExpanded
                        }
                        if (testingExpanded) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Rapid test mode", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Show test prompts every 15 seconds for fast testing.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(
                                    checked = rapidTestMode,
                                    onCheckedChange = { rapidTestMode = it }
                                )
                            }
                        }
                    }
                }
            }
        }
        Surface(shadowElevation = 4.dp) {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding(),
                enabled = selectedCategories.isNotEmpty(),
                onClick = {
                    onSave(
                        selectedCategories,
                        startHour,
                        endHour,
                        promptsPerDay,
                        rapidTestMode,
                        maxQueueSize.toIntOrNull()?.coerceAtLeast(1) ?: 3,
                        sheetUrl
                    )
                }
            ) {
                Text("Save Settings")
            }
        }
    }

    if (showQrScannerDialog) {
        AlertDialog(
            onDismissRequest = { showQrScannerDialog = false },
            title = { Text("Scan Sheet QR Code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Paste the QR result or sheet URL here to populate the spreadsheet link.")
                    OutlinedTextField(
                        value = qrEntry,
                        onValueChange = { qrEntry = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Sheet URL") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    sheetUrl = qrEntry.trim()
                    showQrScannerDialog = false
                }) {
                    Text("Use This URL")
                }
            },
            dismissButton = {
                Button(onClick = { showQrScannerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPoolScreen(
    tasks: List<ManagedTask>,
    onBack: () -> Unit,
    onTasksChanged: (List<ManagedTask>) -> Unit
) {
    var categoryFilter by remember { mutableStateOf("All categories") }
    var description by remember { mutableStateOf("") }
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
            items(taskCategories) { category ->
                Text("$category: ${tasks.count { it.category == category && it.enabled && !it.temporarilyUnavailable && !it.neverSuggest }} active")
            }
            item {
                Button(modifier = Modifier.fillMaxWidth(), onClick = { menuExpanded = true }) {
                    Text("View: $categoryFilter")
                }
            }
            item {
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    (listOf("All categories") + taskCategories).forEach { option ->
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
                    value = duration,
                    onValueChange = { duration = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Duration: 5, 10, or 15 minutes") }
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = description.isNotBlank() && duration.toIntOrNull() in setOf(5, 10, 15),
                    onClick = {
                        val category = if (categoryFilter == "All categories") taskCategories.first() else categoryFilter
                        onTasksChanged(tasks + ManagedTask(
                            id = "custom-${System.currentTimeMillis()}",
                            description = description.trim(),
                            category = category,
                            durationMinutes = duration.toInt(),
                            builtIn = false
                        ))
                        description = ""
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
    onBack: () -> Unit,
    onTasksChanged: (List<UserTask>) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(taskCategories.first()) }
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
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { categoryMenuExpanded = true }
            ) {
                Text("Category: $category")
            }
            DropdownMenu(
                expanded = categoryMenuExpanded,
                onDismissRequest = { categoryMenuExpanded = false }
            ) {
                taskCategories.forEach { categoryOption ->
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
                enabled = description.isNotBlank(),
                onClick = {
                    onTasksChanged(tasks + UserTask(description.trim(), category, true))
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

private val fallbackPromptTasks = listOf(
    ManagedTask("fallback-surface", "Clear one small surface", "Decluttering", 5, true),
    ManagedTask("fallback-papers", "File three papers", "Admin/Paperwork", 10, true),
    ManagedTask("fallback-counter", "Wipe the kitchen counter", "Cleaning", 5, true),
    ManagedTask("fallback-message", "Reply to one message", "Admin/Paperwork", 15, true)
)

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

fun importExternalTasksFromSheet(url: String): List<ManagedTask> {
    val normalizedUrl = normalizeGoogleSheetCsvUrl(url)
    if (normalizedUrl.isBlank()) return emptyList()

    return runCatching {
        val csv = URL(normalizedUrl).readText()
        val rows = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (rows.isEmpty()) return emptyList()

        val categoryName = "Imported"
        parseExternalTaskCsv(csv, categoryName)
    }.getOrDefault(emptyList())
}

private val taskCategories = listOf(
    "Decluttering", "Cleaning", "Admin/Paperwork", "Finances", "Health", "Errands"
)

data class UserTask(val description: String, val category: String, val enabled: Boolean)

private fun eligiblePromptTasks(
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
    return (managedEligibleTasks + legacyEligibleTasks).ifEmpty { fallbackPromptTasks }
}

private fun readUserTasks(json: String): List<UserTask> = runCatching {
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

private fun writeUserTasks(tasks: List<UserTask>): String = JSONArray().apply {
    tasks.forEach { task ->
        put(JSONObject().apply {
            put("description", task.description)
            put("category", task.category)
            put("enabled", task.enabled)
        })
    }
}.toString()

private val microTaskingColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    secondary = Color(0xFF558B2F),
    background = Color.White,
    surface = Color.White
)

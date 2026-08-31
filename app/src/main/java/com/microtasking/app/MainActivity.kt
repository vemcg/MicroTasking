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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.material3.lightColorScheme
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("microtasking_settings", MODE_PRIVATE)
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
                        managedTasks = readManagedTasks(
                            preferences.getString("managed_tasks", "[]") ?: "[]"
                        ),
                        seedTasks = loadSeedTasks(this),
                        declineCounts = readDeclineCounts(
                            preferences.getString("task_decline_counts", "{}") ?: "{}"
                        ),
                        userTasks = readUserTasks(preferences.getString("user_tasks", "[]") ?: "[]"),
                        onSettingsSaved = { categories, start, end, prompts, rapidMode ->
                            preferences.edit()
                                .putBoolean("setup_complete", true)
                                .putStringSet("selected_categories", categories)
                                .putString("start_hour", start)
                                .putString("end_hour", end)
                                .putString("prompts_per_day", prompts)
                                .putBoolean("rapid_test_mode", rapidMode)
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
    userTasks: List<UserTask>,
    managedTasks: List<ManagedTask>,
    seedTasks: List<ManagedTask>,
    declineCounts: Map<String, Int>,
    onSettingsSaved: (Set<String>, String, String, String, Boolean) -> Unit,
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
    var savedUserTasks by remember { mutableStateOf(userTasks) }
    var savedManagedTasks by remember {
        mutableStateOf(if (managedTasks.isEmpty()) seedTasks else managedTasks)
    }
    var savedDeclineCounts by remember { mutableStateOf(declineCounts) }
    var currentTask by remember { mutableStateOf<ManagedTask?>(null) }
    var completedCount by remember { mutableIntStateOf(0) }
    var attemptedCount by remember { mutableIntStateOf(0) }
    var lastTaskCompleted by remember { mutableStateOf(true) }
    var backgroundPromptsRunning by remember { mutableStateOf(true) }
    val promptTasks = eligiblePromptTasks(savedManagedTasks, savedUserTasks, savedCategories)
    if (currentTask == null) {
        currentTask = chooseWeightedTask(promptTasks, savedDeclineCounts, null)
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
            onOpenMyTasks = { showingMyTasks = true },
            onOpenTaskPool = { showingTaskPool = true },
            onSave = { categories, start, end, prompts, rapidMode ->
                savedCategories = categories
                savedStartHour = start
                savedEndHour = end
                savedPromptsPerDay = prompts
                savedRapidTestMode = rapidMode
                onSettingsSaved(categories, start, end, prompts, rapidMode)
                showingSettings = false
            }
        )
    } else if (showingScore) {
        ScoreScreen(
            completedCount = completedCount,
            attemptedCount = attemptedCount,
            taskCompleted = lastTaskCompleted,
            rapidTestMode = savedRapidTestMode && backgroundPromptsRunning,
            backgroundPromptsRunning = backgroundPromptsRunning,
            onBackgroundPromptsChanged = { enabled ->
                backgroundPromptsRunning = enabled
                onBackgroundPromptsChanged(enabled)
            },
            onNextPrompt = {
                currentTask = chooseWeightedTask(promptTasks, savedDeclineCounts, currentTask?.id)
                showingScore = false
            }
        )
    } else {
        TaskPromptScreen(
            task = currentTask ?: promptTasks.first(),
            completedCount = completedCount,
            rapidTestMode = savedRapidTestMode && backgroundPromptsRunning,
            onOpenSettings = { showingSettings = true },
            onComplete = {
                completedCount++
                attemptedCount++
                lastTaskCompleted = true
                showingScore = true
            },
            onChooseAlternative = {
                val declinedTaskId = currentTask?.id ?: return@TaskPromptScreen
                savedDeclineCounts = savedDeclineCounts + (
                    declinedTaskId to (savedDeclineCounts[declinedTaskId] ?: 0) + 1
                )
                onDeclineCountsSaved(savedDeclineCounts)
                currentTask = chooseWeightedTask(promptTasks, savedDeclineCounts, declinedTaskId)
            },
            onTimeout = {
                attemptedCount++
                lastTaskCompleted = false
                showingScore = true
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPromptScreen(
    task: ManagedTask,
    completedCount: Int,
    rapidTestMode: Boolean,
    onOpenSettings: () -> Unit,
    onComplete: () -> Unit,
    onChooseAlternative: () -> Unit,
    onTimeout: () -> Unit
) {
    val context = LocalContext.current
    val timeoutSeconds = task.durationMinutes * 2
    var secondsRemaining by remember(task.id) { mutableIntStateOf(timeoutSeconds) }
    LaunchedEffect(task.id) {
        PromptNotifier.show(context)
    }
    if (rapidTestMode) {
        LaunchedEffect(task.id) {
            repeat(timeoutSeconds) {
                delay(1_000)
                secondsRemaining--
            }
            onTimeout()
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
            Text("Your next ${task.durationMinutes}-minute task", style = MaterialTheme.typography.labelLarge)
            Text(
                text = task.description,
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.headlineSmall
            )
            if (rapidTestMode) {
                Text(
                    text = "Rapid test: $secondsRemaining seconds remaining",
                    modifier = Modifier.padding(bottom = 12.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onComplete
            ) {
                Text("Mark complete")
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                onClick = onChooseAlternative
            ) {
                Text("Show another task")
            }
            Text(
                text = "Completed this session: $completedCount",
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun ScoreScreen(
    completedCount: Int,
    attemptedCount: Int,
    taskCompleted: Boolean,
    rapidTestMode: Boolean,
    backgroundPromptsRunning: Boolean,
    onBackgroundPromptsChanged: (Boolean) -> Unit,
    onNextPrompt: () -> Unit
) {
    val scorePercent = if (attemptedCount == 0) 0 else completedCount * 100 / attemptedCount
    val scoreColor = when {
        scorePercent >= 80 -> Color(0xFF2E7D32)
        scorePercent >= 50 -> Color(0xFF8A6000)
        else -> Color(0xFFC62828)
    }
    var secondsUntilNextPrompt by remember(attemptedCount) { mutableIntStateOf(15) }
    if (rapidTestMode) {
        LaunchedEffect(attemptedCount) {
            repeat(15) {
                delay(1_000)
                secondsUntilNextPrompt--
            }
            onNextPrompt()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (taskCompleted) "Task completed" else "Task timed out",
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
        if (rapidTestMode) {
            Text(
                text = "Next test prompt in $secondsUntilNextPrompt seconds...",
                modifier = Modifier.padding(top = 24.dp)
            )
        }
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
            onClick = onNextPrompt
        ) {
            Text(if (rapidTestMode) "Show next prompt now" else "Continue")
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

@Composable
fun SettingsScreen(
    initialCategories: Set<String>,
    initialStartHour: String,
    initialEndHour: String,
    initialPromptsPerDay: String,
    initialRapidTestMode: Boolean,
    onOpenMyTasks: () -> Unit,
    onOpenTaskPool: () -> Unit,
    onSave: (Set<String>, String, String, String, Boolean) -> Unit
) {
    val categories = listOf("Decluttering", "Cleaning", "Admin/Paperwork", "Finances", "Health", "Errands")
    var selectedCategories by remember { mutableStateOf(initialCategories) }
    var startHour by remember { mutableStateOf(initialStartHour) }
    var endHour by remember { mutableStateOf(initialEndHour) }
    var promptsPerDay by remember { mutableStateOf(initialPromptsPerDay) }
    var rapidTestMode by remember { mutableStateOf(initialRapidTestMode) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Settings",
                    modifier = Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            item { Text("Choose the categories eligible for your task prompts.") }
            items(categories, key = { it }) { category ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = category in selectedCategories,
                        onCheckedChange = { checked ->
                            selectedCategories = if (checked) selectedCategories + category else selectedCategories - category
                        }
                    )
                    Text(category)
                }
            }
            item { Text("Manage your task pool", style = MaterialTheme.typography.titleMedium) }
            item {
                Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenMyTasks) {
                    Text("My Tasks")
                }
            }
            item {
                Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenTaskPool) {
                    Text("Task pool")
                }
            }
            item { Text("Prompt schedule", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(
                    value = startHour,
                    onValueChange = { startHour = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Start hour (0-23)") }
                )
            }
            item {
                OutlinedTextField(
                    value = endHour,
                    onValueChange = { endHour = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("End hour (0-23)") }
                )
            }
            item {
                OutlinedTextField(
                    value = promptsPerDay,
                    onValueChange = { promptsPerDay = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Prompts per day") }
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Rapid test mode", style = MaterialTheme.typography.titleMedium)
                        Text("After a completion, show another test prompt in a few seconds.")
                    }
                    Checkbox(
                        checked = rapidTestMode,
                        onCheckedChange = { rapidTestMode = it }
                    )
                }
            }
        }
        Surface(shadowElevation = 4.dp) {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .navigationBarsPadding(),
                enabled = selectedCategories.isNotEmpty(),
                onClick = { onSave(selectedCategories, startHour, endHour, promptsPerDay, rapidTestMode) }
            ) {
                Text("Save settings")
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

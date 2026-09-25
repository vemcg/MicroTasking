// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-24, after version v0.2.0-82 synchronization-improvements 2026-09-25
package com.microtasking.app

import android.Manifest
import android.content.SharedPreferences
import java.util.UUID
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
        DiagnosticLog.log(this, "APP_START")
        val preferences = getSharedPreferences("microtasking_settings", MODE_PRIVATE)
        preferences.edit().putBoolean("app_in_foreground", true).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
        if (preferences.getBoolean("setup_complete", false)) {
            // Catches up on any day/window rollover that happened while the app was closed,
            // before the streak/queue below get read into UI state - otherwise a stale streak
            // could show until whichever delivery tick happens to run first. Deliberately does
            // NOT force background_prompts_enabled back on here - a manual pause must survive a
            // relaunch (the app can be killed and reopened between glances); only a manual
            // Resume or the window's own open transition (handled inside reconcile) may clear
            // it. See DEFECTS.md item 4.
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
                        // Coerced defensively - this feeds List.take() in makeTaskStack below,
                        // which throws on a negative count. The Settings Save button already
                        // clamps to >= 1 before persisting, but an older build or a value poked
                        // directly into SharedPreferences (e.g. over adb) could still be <= 0.
                        maxQueueSize = preferences.getInt("max_task_queue_size", 3).coerceAtLeast(1),
                        externalSheetUrl = preferences.getString("external_sheet_url", "") ?: "",
                        webAppUrl = preferences.getString("web_app_url", "") ?: "",
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
                        initialCleanDayStreak = preferences.getInt("clean_day_streak", 0),
                        initialCleanDayStreakLongest = preferences.getInt("clean_day_streak_longest", 0),
                        initialLastOutcome = runCatching {
                            TaskLifecycleState.valueOf(preferences.getString("last_outcome", TaskLifecycleState.COMPLETED.name)!!)
                        }.getOrDefault(TaskLifecycleState.COMPLETED),
                        initialCompletionLog = readCompletionLog(preferences.getString("completion_log", "[]") ?: "[]"),
                        initialVacationMode = preferences.getBoolean("vacation_mode", false),
                        onSettingsSaved = { categories, start, end, prompts, maxQueueSize, sheetUrl ->
                            // Only a first-time setup completion should force delivery on - an
                            // ordinary settings edit later must not silently clobber a manual
                            // pause (see DEFECTS.md item 4).
                            val wasSetupComplete = preferences.getBoolean("setup_complete", false)
                            val oldStartHour = preferences.getString("start_hour", "9") ?: "9"
                            val oldEndHour = preferences.getString("end_hour", "21") ?: "21"
                            val oldPromptsPerDay = preferences.getString("prompts_per_day", "6") ?: "6"
                            val oldMaxQueueSize = preferences.getInt("max_task_queue_size", 3)
                            val before = "window=$oldStartHour-$oldEndHour prompts=$oldPromptsPerDay " +
                                "queueSize=$oldMaxQueueSize " +
                                "categories=${preferences.getStringSet("selected_categories", emptySet())?.sorted()}"
                            val after = "window=$start-$end prompts=$prompts queueSize=$maxQueueSize " +
                                "categories=${categories.sorted()}"
                            if (wasSetupComplete && before != after) {
                                DiagnosticLog.log(this, "SETTINGS_CHANGED", "before=[$before] after=[$after]")
                            }
                            val editor = preferences.edit()
                                .putBoolean("setup_complete", true)
                                .putStringSet("selected_categories", categories)
                                .putString("start_hour", start)
                                .putString("end_hour", end)
                                .putString("prompts_per_day", prompts)
                                .putInt("max_task_queue_size", maxQueueSize)
                                .putString("external_sheet_url", sheetUrl)
                            if (!wasSetupComplete) editor.putBoolean("background_prompts_enabled", true)
                            // See pacingSettingsChanged: a window/prompts/queue-size change leaves an
                            // already-armed next_dispatch_epoch_ms stale (computed from the values just
                            // replaced) - clear it so the tick that follows this save (MicroTaskingApp's
                            // LaunchedEffect, keyed on these same fields) recomputes fresh instead of the
                            // "already armed" gate in TaskDelivery.tick treating it as nothing to do.
                            if (wasSetupComplete && pacingSettingsChanged(
                                    oldStartHour, oldEndHour, oldPromptsPerDay, oldMaxQueueSize,
                                    start, end, prompts, maxQueueSize
                                )
                            ) {
                                editor.remove("next_dispatch_epoch_ms")
                            }
                            editor.apply()
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
                        onWebAppUrlSaved = { url ->
                            preferences.edit()
                                .putString("web_app_url", url)
                                .apply()
                        },
                        onBackgroundPromptsChanged = { enabled ->
                            // Only persisted here - while the app is foregrounded the alarm is
                            // cancelled anyway (onStart/onStop own it) and the live pacing loop
                            // reads this flag directly on every tick.
                            preferences.edit().putBoolean("background_prompts_enabled", enabled).apply()
                        },
                        onVacationModeChanged = { enabled ->
                            preferences.edit().putBoolean("vacation_mode", enabled).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiagnosticLog.log(this, "APP_FOREGROUND")
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
        DiagnosticLog.log(this, "APP_BACKGROUND")
        val preferences = getSharedPreferences("microtasking_settings", MODE_PRIVATE)
        preferences.edit().putBoolean("app_in_foreground", false).apply()
        if (preferences.getBoolean("setup_complete", false)) {
            PromptScheduler.scheduleNext(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            DiagnosticLog.log(this, "PERMISSION", "POST_NOTIFICATIONS ${if (granted) "granted" else "denied"}")
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
    webAppUrl: String,
    userTasks: List<UserTask>,
    managedTasks: List<ManagedTask>,
    declineCounts: Map<String, Int>,
    initialTaskQueue: List<TaskStackEntry>,
    initialStreak: Int,
    initialLongestStreak: Int,
    initialTimeoutStreak: Int,
    initialCleanDayStreak: Int,
    initialCleanDayStreakLongest: Int,
    initialLastOutcome: TaskLifecycleState,
    initialCompletionLog: List<CompletionRecord>,
    initialVacationMode: Boolean,
    onSettingsSaved: (Set<String>, String, String, String, Int, String) -> Unit,
    onUserTasksSaved: (List<UserTask>) -> Unit,
    onManagedTasksSaved: (List<ManagedTask>) -> Unit,
    onDeclineCountsSaved: (Map<String, Int>) -> Unit,
    onSheetUrlSaved: (String) -> Unit,
    onWebAppUrlSaved: (String) -> Unit,
    onBackgroundPromptsChanged: (Boolean) -> Unit,
    onVacationModeChanged: (Boolean) -> Unit
) {
    var showingSettings by remember {
        mutableStateOf(!setupComplete || (managedTasks.isEmpty() && userTasks.isEmpty()))
    }
    var showingMyTasks by remember { mutableStateOf(false) }
    var showingTaskPool by remember { mutableStateOf(false) }
    var showingScore by remember { mutableStateOf(false) }
    var savedCategories by remember { mutableStateOf(selectedCategories) }
    var savedStartHour by remember { mutableStateOf(startHour) }
    var savedEndHour by remember { mutableStateOf(endHour) }
    var savedPromptsPerDay by remember { mutableStateOf(promptsPerDay) }
    var savedMaxQueueSize by remember { mutableIntStateOf(maxQueueSize) }
    var savedSheetUrl by remember { mutableStateOf(externalSheetUrl) }
    var savedWebAppUrl by remember { mutableStateOf(webAppUrl) }
    // Hoisted out of SettingsScreen so a failed Save-triggered sync can force the connection
    // section open to show its error. Smart default: open "Google Sheet Connection" only for a
    // not-yet-configured install.
    var settingsOpenSection by remember { mutableStateOf(if (savedSheetUrl.isBlank()) "Google Sheet Connection" else "") }
    // True from a Save that changed the connection until the sync it started finishes - Settings
    // shows "Syncing…" and stays open meanwhile, then closes on success or shows the error.
    var savingSync by remember { mutableStateOf(false) }
    // Bumped when the app is pointed at a different Sheet: a sync already running for the old one
    // must not merge its (now foreign) result in after the old Sheet's state was discarded.
    var sheetGeneration by remember { mutableIntStateOf(0) }
    // Callers waiting on the result of the sync that finally settles (see runSheetImport).
    val syncWaiters = remember { mutableListOf<(Boolean) -> Unit>() }
    var referralErrorMessage by remember { mutableStateOf<String?>(null) }
    var showingReferralMatrixFor by remember { mutableStateOf<String?>(null) }
    var savedUserTasks by remember { mutableStateOf(userTasks) }
    var savedManagedTasks by remember { mutableStateOf(managedTasks) }
    var savedDeclineCounts by remember { mutableStateOf(declineCounts) }
    var streak by remember { mutableIntStateOf(initialStreak) }
    var longestStreak by remember { mutableIntStateOf(initialLongestStreak) }
    var timeoutStreak by remember { mutableIntStateOf(initialTimeoutStreak) }
    var cleanDayStreak by remember { mutableIntStateOf(initialCleanDayStreak) }
    var cleanDayStreakLongest by remember { mutableIntStateOf(initialCleanDayStreakLongest) }
    var lastOutcome by remember { mutableStateOf(initialLastOutcome) }
    var completionLog by remember { mutableStateOf(initialCompletionLog) }
    var scoreEntryToken by remember { mutableIntStateOf(0) }
    var backgroundPromptsRunning by remember { mutableStateOf(true) }
    var vacationMode by remember { mutableStateOf(initialVacationMode) }
    var nextDispatchEpoch by remember { mutableStateOf<Long?>(null) }
    var clockMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var queueFullFlashUntil by remember { mutableLongStateOf(0L) }
    var isImportingSheet by remember { mutableStateOf(false) }
    var sheetImportMessage by remember { mutableStateOf<String?>(null) }
    // A sync already in flight was fetched before whatever change just happened, so its result is
    // stale: [resyncPending] queues one more sync behind it, and [referredDuringSync] keeps the
    // stale result from un-referring a task referred while it was running.
    var resyncPending by remember { mutableStateOf(false) }
    val referredDuringSync = remember { mutableSetOf<String>() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    // How many Sheet writes are still queued (PendingChanges) - drives the quiet "N changes waiting
    // to reach your Sheet" line on the main screen; 0 hides it.
    var pendingChangeCount by remember { mutableIntStateOf(PendingChanges.count(context)) }
    // Flushes started from this screen that haven't finished - the "N changes waiting" line stays
    // hidden while one runs, so a referral doesn't flash it for the second the write takes.
    var flushesRunning by remember { mutableIntStateOf(0) }

    // Two things can change persisted state behind this composable's back: ActiveTasks's messages
    // (TaskEventReceiver rewrites the saved task list, even while this screen is showing) and the
    // background flush job (shrinks the pending queue). Watching the prefs updates the screen in
    // place for both. Our own writes trip it too, but they always land in state first, so the
    // equality check makes those a no-op.
    DisposableEffect(context) {
        val prefs = TaskDelivery.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PendingChanges.PREFS_KEY -> pendingChangeCount = PendingChanges.count(context)
                "managed_tasks" -> {
                    val latest = readManagedTasks(prefs.getString("managed_tasks", "[]") ?: "[]")
                    if (latest != savedManagedTasks) savedManagedTasks = latest
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
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

    fun persistManagedTasks(newTasks: List<ManagedTask>) {
        savedManagedTasks = newTasks
        onManagedTasksSaved(newTasks)
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
    // still needs, regardless of whether rapid testing mode is currently active.
    fun persistCompletionLog(newLog: List<CompletionRecord>) {
        val cutoff = System.currentTimeMillis() - 45L * 24 * 60 * 60 * 1000
        val trimmed = newLog.filter { it.epochMs >= cutoff }
        completionLog = trimmed
        TaskDelivery.prefs(context).edit().putString("completion_log", writeCompletionLog(trimmed)).apply()
    }

    // The clean-day streak (Score screen) counts days with at least one completion and no
    // abandon/timeout. These per-day flags are read back and folded into the streak by
    // TaskDelivery.reconcileState when the calendar day rolls over.
    fun markDayHadCompletion() {
        TaskDelivery.prefs(context).edit().putBoolean("day_had_completion", true).apply()
    }

    fun markDayHadFailure() {
        cleanDayStreak = 0
        TaskDelivery.prefs(context).edit()
            .putBoolean("day_had_failure", true)
            .putInt("clean_day_streak", 0)
            .apply()
    }

    // Pulls the queue/streak/window state TaskDelivery just persisted back into UI state.
    fun refreshFromPrefs() {
        val prefs = TaskDelivery.prefs(context)
        taskQueue = readTaskQueue(prefs.getString("task_queue", "[]") ?: "[]")
        streak = prefs.getInt("streak", 0)
        longestStreak = prefs.getInt("longest_streak", 0)
        timeoutStreak = prefs.getInt("timeout_streak", 0)
        cleanDayStreak = prefs.getInt("clean_day_streak", 0)
        cleanDayStreakLongest = prefs.getInt("clean_day_streak_longest", 0)
        lastOutcome = runCatching {
            TaskLifecycleState.valueOf(prefs.getString("last_outcome", TaskLifecycleState.COMPLETED.name)!!)
        }.getOrDefault(TaskLifecycleState.COMPLETED)
        backgroundPromptsRunning = prefs.getBoolean("background_prompts_enabled", true)
        vacationMode = prefs.getBoolean("vacation_mode", false)
        nextDispatchEpoch = prefs.getLong("next_dispatch_epoch_ms", 0L).takeIf { it > 0L }
    }

    /**
     * One sync (SPEC.md "Synchronization"). [onFinished], if given, is told whether the Sheet was
     * read and merged in (true) or not (false: the read failed, the Sheet needs setting up, or it has
     * no rows) - once the sync that actually settles has finished, which is a later one if this
     * call arrived while another was running (it queues exactly one follow-up).
     */
    fun runSheetImport(url: String, onFinished: ((Boolean) -> Unit)? = null) {
        if (onFinished != null) syncWaiters += onFinished
        if (isImportingSheet) {
            resyncPending = true
            return
        }
        isImportingSheet = true
        sheetImportMessage = null
        referredDuringSync.clear()
        val webAppUrlAtStart = savedWebAppUrl
        val generationAtStart = sheetGeneration
        coroutineScope.launch {
            // All the network work happens here, off the main thread, touching no UI state.
            // Step 1: flush the pending-changes queue, so our own unsent writes reach the Sheet
            // before we read it back. Step 2: read the whole Sheet - every tab's rows, and (through
            // the Web App) which rows are referred. Nothing is applied until both reads are in.
            val fetch = withContext(Dispatchers.IO) {
                val stillQueued = PendingChanges.flushToSheet(context)
                val sheet = importExternalTasksFromSheet(url)
                val referred = if (webAppUrlAtStart.isNotBlank() && !sheet.failed) {
                    WebAppClient.getReferredRowKeys(webAppUrlAtStart)
                } else null
                FetchedSheet(stillQueued, sheet, referred)
            }
            pendingChangeCount = fetch.stillQueued
            if (fetch.stillQueued > 0 && webAppUrlAtStart.isNotBlank()) PendingFlushScheduler.schedule(context)
            isImportingSheet = false
            val result = fetch.sheet
            // All or nothing (SPEC.md "Synchronization"): if any part of the read failed - a tab, the
            // tab list, or the referred-rows call - change nothing locally. A partial read looks
            // exactly like "those tabs are empty / nothing is referred" and would wipe tasks.
            val syncFailed = result.failed || fetch.referred?.isFailure == true
            // The app was pointed at a different Sheet while this read was in flight: what came back
            // belongs to the old one, so it's dropped (a follow-up sync of the new Sheet is queued).
            val superseded = generationAtStart != sheetGeneration
            var applied = false
            val importedTasks = result.tasks
            // A sheet that still only has the Apps Script's default "Sheet1" hasn't been set up yet.
            val blankDefaultSheet = importedTasks.isEmpty() &&
                result.tabNames.size == 1 && result.tabNames.single().equals("Sheet1", ignoreCase = true)
            // The sheet's tab names ARE the category list - authoritative even for a tab that
            // currently has zero task rows. Fall back to task categories only when tab enumeration
            // failed and we came in through the single-CSV path.
            val authoritativeCategories = when {
                result.tabNames.isNotEmpty() && !blankDefaultSheet -> result.tabNames.toSet()
                else -> importedTasks.map { it.category }.toSet()
            }

            if (superseded) {
                resyncPending = true
            } else if (syncFailed) {
                sheetImportMessage = "Couldn't read the whole Sheet, so nothing was changed. Check your " +
                    "connection - your tasks stay as they were and the next sync tries again."
            } else if (authoritativeCategories.isNotEmpty()) {
                applied = true
                val before = savedManagedTasks.map { it.category }.toSet() + savedUserTasks.map { it.category }.toSet()
                // Merged against the task list as it is NOW (not as it was when the read started),
                // so a message from ActiveTasks that landed mid-sync isn't overwritten.
                var merged = mergeImportedManagedTasks(importedTasks, savedManagedTasks, authoritativeCategories)
                // Importance/Urgency never come from this CSV path (see SPEC.md "Sheet
                // write-back": hiding a column doesn't exclude it from CSV/gviz export, so those
                // two columns are read via the Web App only) - fold in the referral state that
                // was read at this same sync boundary, if a Web App URL is configured.
                fetch.referred?.getOrNull()?.let { referredKeys ->
                    merged = refreshReferralState(merged, referredKeys + referredDuringSync)
                }
                // Lay our own unsent writes over what the Sheet just said: a referral still waiting
                // to reach the Sheet stays referred, instead of being "corrected" back into the pool.
                merged = applyPendingOverlay(merged, PendingChanges.all(context))
                persistManagedTasks(merged)
                val prunedUserTasks = savedUserTasks.filter { it.category in authoritativeCategories }
                if (prunedUserTasks.size != savedUserTasks.size) {
                    savedUserTasks = prunedUserTasks
                    onUserTasksSaved(prunedUserTasks)
                }
                val removed = (before - authoritativeCategories).sorted()
                val enabledCount = importedTasks.count { it.enabled }
                sheetImportMessage = buildString {
                    append("Imported ${importedTasks.size} tasks across ${authoritativeCategories.size} categories ($enabledCount checked and active).")
                    if (removed.isNotEmpty()) append(" Removed categories not in the sheet: ${removed.joinToString(", ")}.")
                }
            } else if (blankDefaultSheet) {
                sheetImportMessage = "This Sheet only has an empty default \"Sheet1\" tab - run the MicroTasking " +
                    "setup script on it first (Extensions → Apps Script → setupMicroTaskingSheet), then Save Settings again."
            } else if (result.tabNames.isNotEmpty()) {
                sheetImportMessage = "Found tabs (${result.tabNames.joinToString(", ")}) but no task rows in them. " +
                    "Check that row 1 of each tab has a \"description\" column header."
            } else {
                sheetImportMessage = "Couldn't read any tabs from this Sheet. Check the URL and that sharing is " +
                    "\"Anyone with the link can view\"."
            }
            if (resyncPending) {
                // Anyone waiting stays queued: they get the follow-up's result, not this stale one.
                resyncPending = false
                runSheetImport(savedSheetUrl)
            } else {
                val waiters = syncWaiters.toList()
                syncWaiters.clear()
                waiters.forEach { it(applied) }
            }
        }
    }

    // Flushes the pending-changes queue now, in the background; if anything is left behind (offline,
    // or the Web App had trouble) asks WorkManager to try again once the network is back. While it
    // runs the "N changes waiting" line stays hidden - it only means something after a flush FAILED.
    fun startFlush() {
        if (savedWebAppUrl.isBlank()) return
        flushesRunning++
        coroutineScope.launch {
            val stillQueued = withContext(Dispatchers.IO) { PendingChanges.flushToSheet(context) }
            flushesRunning--
            pendingChangeCount = stillQueued
            if (stillQueued > 0) PendingFlushScheduler.schedule(context)
        }
    }

    // Pointing the app at a different Sheet throws away everything local that came from the old one
    // (SPEC.md "Synchronization"): the writes still waiting for it (they describe rows that don't
    // exist in the new Sheet), its imported tasks, and the on-screen queue built from them. Nothing
    // is carried over - the new Sheet's sync rebuilds it all. Built-in and hand-added tasks stay;
    // the sync prunes those by the new Sheet's tabs as it always has.
    fun discardOldSheetState() {
        sheetGeneration++
        PendingChanges.clear(context)
        pendingChangeCount = 0
        persistManagedTasks(savedManagedTasks.filterNot { it.id.startsWith("external-") })
        persistTaskQueue(emptyList())
    }

    /**
     * "Refer to ActiveTasks" (see SPEC.md "Task referral to ActiveTasks" and "Synchronization"): the
     * referral takes effect locally at once - the task is stamped referred and leaves the queue with
     * a replacement backfilled in its slot, a neutral outcome like Substitute whether or not it had
     * been Started - and its `setPriority` write is queued (PendingChanges), so an offline or failed
     * write is retried rather than lost. A flush starts immediately; when the Sheet has accepted the
     * write, the "referred" message goes out to ActiveTasks (never before). Then a sync re-reads the
     * Sheet.
     */
    fun submitReferral(taskId: String, importance: Double, urgency: Double) {
        val task = taskQueue.find { it.task.id == taskId }?.task ?: return
        if (savedWebAppUrl.isBlank()) {
            // Nothing to write to yet, so nothing to queue - say what to do rather than queue forever.
            referralErrorMessage = "No Web App URL is set yet. Add it in Settings (the \"Apps Script Web App URL\" field), then try again."
            return
        }
        referralErrorMessage = null
        val now = System.currentTimeMillis()
        DiagnosticLog.log(context, "REFERRED", "task=$taskId category=${task.category} importance=$importance urgency=$urgency")
        PendingChanges.enqueue(
            context,
            PendingChange(
                id = UUID.randomUUID().toString(),
                taskId = task.taskId,
                category = task.category,
                description = task.description,
                link = task.link,
                importance = importance.coerceIn(0.0, 1.0),
                urgency = urgency.coerceIn(0.0, 1.0),
                queuedAtEpochMs = now
            )
        )
        // Keeps a sync that is already in flight (its read predates this write) from un-referring it.
        referredDuringSync += referralKeyOf(task)
        val updatedTasks = savedManagedTasks.map { if (it.id == taskId) it.copy(referredAt = now) else it }
        persistManagedTasks(updatedTasks)
        val freshPromptTasks = eligiblePromptTasks(updatedTasks, savedUserTasks, savedCategories)
        val queuedTaskIds = taskQueue.map { it.task.id }.toSet()
        val candidates = freshPromptTasks.filter { it.id !in queuedTaskIds }.ifEmpty { freshPromptTasks }
        val newQueue = if (candidates.isEmpty()) {
            taskQueue.filterNot { it.task.id == taskId }
        } else {
            val replacement = chooseWeightedTask(
                tasks = candidates,
                activeCategoryOrder = activeCategoryOrder,
                previousTaskId = taskId
            )
            taskQueue.map { if (it.task.id == taskId) TaskStackEntry(replacement) else it }
        }
        persistTaskQueue(newQueue)
        showingReferralMatrixFor = null
        startFlush()
        if (savedSheetUrl.isNotBlank()) runSheetImport(savedSheetUrl)
    }

    // Sync on every foreground entry - a cold launch, or coming back from ActiveTasks after it
    // completed or released a row - so the task pool never waits on a manual "Update Tasks".
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSyncOnStart by rememberUpdatedState {
        if (savedSheetUrl.isNotBlank()) runSheetImport(savedSheetUrl)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) latestSyncOnStart()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        promptTasks,
        savedStartHour,
        savedEndHour,
        savedPromptsPerDay,
        savedMaxQueueSize
    ) {
        if (promptTasks.isEmpty()) {
            return@LaunchedEffect
        }
        // Tick once on entry so a window opening (or a settings change) dispatches immediately
        // rather than waiting out a whole interval first. Harmless to call redundantly (e.g. every
        // time this activity is reopened) - tick() itself no-ops if the last real dispatch already
        // armed a still-future next_dispatch_epoch_ms, so this can't double-queue a task on top of
        // one the background alarm just delivered. See DEFECTS.md item 5.
        withContext(Dispatchers.IO) { TaskDelivery.tick(context) }
        refreshFromPrefs()
        // 1s poll: cheap while foregrounded (screen is on), doubles as the countdown clock, and
        // lets a force-dispatch tap take effect within a second by just rewriting the epoch.
        while (true) {
            delay(1_000L)
            clockMillis = System.currentTimeMillis()
            val epoch = TaskDelivery.prefs(context).getLong("next_dispatch_epoch_ms", 0L)
            if (epoch in 1..clockMillis) {
                // Same shared function the background alarm uses, so foreground and background
                // read and write the exact same persisted queue/streak/window state.
                val result = withContext(Dispatchers.IO) { TaskDelivery.tick(context) }
                refreshFromPrefs()
                if (result.dispatched) {
                    showingScore = false
                }
            }
        }
    }

    fun forceDispatchNow() {
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { TaskDelivery.tick(context, force = true) }
            refreshFromPrefs()
            clockMillis = System.currentTimeMillis()
            when {
                result.queueFull -> queueFullFlashUntil = System.currentTimeMillis() + 2_000L
                result.dispatched -> showingScore = false
            }
        }
    }

    // Pause/Resume from any screen. Resuming immediately re-ticks so a task is (re-)paced right
    // away instead of waiting out whatever interval was already committed - this is also the fix
    // for the "manual Resume doesn't deliver promptly" defect.
    fun setBackgroundPrompts(enabled: Boolean) {
        backgroundPromptsRunning = enabled
        onBackgroundPromptsChanged(enabled)
        coroutineScope.launch {
            withContext(Dispatchers.IO) { TaskDelivery.tick(context) }
            refreshFromPrefs()
            clockMillis = System.currentTimeMillis()
        }
    }

    // Vacation mode is a hard override on top of Pause/Resume: while checked, TaskDelivery.tick
    // refuses to dispatch or schedule anything - not even a forced tap - until this is unchecked
    // again. Re-ticking here (both on check and uncheck) clears any stale countdown immediately
    // and, on uncheck, re-paces right away instead of waiting out whatever was left.
    fun setVacationMode(enabled: Boolean) {
        vacationMode = enabled
        onVacationModeChanged(enabled)
        coroutineScope.launch {
            withContext(Dispatchers.IO) { TaskDelivery.tick(context) }
            refreshFromPrefs()
            clockMillis = System.currentTimeMillis()
        }
    }

    // (The QR scanner is not a screen of its own any more: SettingsScreen shows it from inside
    // itself, so a scan only fills the Settings drafts - see SettingsScreen.)
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
            initialWebAppUrl = savedWebAppUrl,
            isImportingSheet = isImportingSheet,
            saving = savingSync,
            importMessage = sheetImportMessage,
            backgroundPromptsRunning = backgroundPromptsRunning,
            vacationMode = vacationMode,
            openSection = settingsOpenSection,
            onOpenSectionChanged = { settingsOpenSection = it },
            onOpenMyTasks = { showingMyTasks = true },
            onOpenTaskPool = { showingTaskPool = true },
            onCancel = { if (setupComplete) showingSettings = false },
            onBackgroundPromptsChanged = { setBackgroundPrompts(it) },
            onVacationModeChanged = { setVacationMode(it) },
            // Saves everything. A changed Sheet or Web App URL also syncs - the one manual way to
            // sync now; a different Sheet first discards everything local to the old one - and
            // Settings stays open ("Syncing…") until the sync succeeds, or shows why it didn't.
            // Saving with the connection unchanged doesn't sync.
            onSave = { categories, start, end, prompts, queueSize, sheetUrl, newWebAppUrl ->
                val decision = decideSettingsSave(savedSheetUrl, sheetUrl, savedWebAppUrl, newWebAppUrl)
                if (decision.sheetSwitched) discardOldSheetState()
                savedCategories = categories
                savedStartHour = start
                savedEndHour = end
                savedPromptsPerDay = prompts
                savedMaxQueueSize = queueSize
                savedSheetUrl = sheetUrl
                savedWebAppUrl = newWebAppUrl
                onSettingsSaved(categories, start, end, prompts, queueSize, sheetUrl)
                onWebAppUrlSaved(newWebAppUrl)
                if (!decision.shouldSync) {
                    showingSettings = false
                } else {
                    savingSync = true
                    sheetImportMessage = null
                    runSheetImport(sheetUrl) { succeeded ->
                        savingSync = false
                        if (succeeded) {
                            showingSettings = false
                        } else {
                            // Stay on Settings with the reason visible; the settings themselves are saved,
                            // so the next foreground sync retries.
                            settingsOpenSection = "Google Sheet Connection"
                        }
                    }
                }
            }
        )
    } else if (showingScore) {
        ScoreScreen(
            streak = streak,
            longestStreak = longestStreak,
            cleanDayStreak = cleanDayStreak,
            cleanDayStreakLongest = cleanDayStreakLongest,
            completionLog = completionLog,
            promptsPerDay = savedPromptsPerDay.toIntOrNull() ?: 0,
            outcome = lastOutcome,
            timeoutStreak = timeoutStreak,
            entryToken = scoreEntryToken,
            backgroundPromptsRunning = backgroundPromptsRunning,
            onOpenSettings = { showingSettings = true },
            onBackgroundPromptsChanged = { setBackgroundPrompts(it) },
            onReturnToTaskList = {
                showingScore = false
            }
        )
    } else if (showingReferralMatrixFor != null) {
        val referredTask = taskQueue.find { it.task.id == showingReferralMatrixFor }?.task
        if (referredTask == null) {
            // The task left the queue some other way (e.g. Abandon tapped in a race) while the
            // matrix was open - nothing sensible to refer any more, just back out.
            showingReferralMatrixFor = null
        } else {
            EisenhowerReferralScreen(
                task = referredTask,
                // The referral applies locally and is queued at once - there's no in-flight state
                // to wait on any more (see submitReferral).
                submitting = false,
                errorMessage = referralErrorMessage,
                onConfirm = { importance, urgency -> submitReferral(referredTask.id, importance, urgency) },
                onCancel = {
                    showingReferralMatrixFor = null
                    referralErrorMessage = null
                }
            )
        }
    } else {
        TaskPromptScreen(
            taskEntries = visibleTaskEntries,
            streak = streak,
            maxQueueSize = savedMaxQueueSize,
            promptsPerDay = savedPromptsPerDay.toIntOrNull() ?: 0,
            nextDispatchEpoch = nextDispatchEpoch,
            clockMillis = clockMillis,
            queueFullFlash = clockMillis < queueFullFlashUntil,
            withinWindow = isWithinActiveWindow(
                LocalDateTime.now(),
                (savedStartHour.toIntOrNull() ?: 9).coerceIn(0, 24),
                (savedEndHour.toIntOrNull() ?: 21).coerceIn(0, 24)
            ),
            vacationMode = vacationMode,
            onForceDispatch = { forceDispatchNow() },
            onOpenSettings = { showingSettings = true },
            onOpenScore = {
                scoreEntryToken++
                showingScore = true
            },
            onStart = { taskId ->
                DiagnosticLog.log(context, "STARTED", "task=$taskId")
                persistTaskQueue(taskQueue.map { if (it.task.id == taskId) it.start() else it })
            },
            onComplete = { taskId ->
                val newStreak = streak + 1
                val started = taskQueue.find { it.task.id == taskId }?.startedAtEpochMs
                val elapsedSeconds = started?.let { (System.currentTimeMillis() - it) / 1000 }
                DiagnosticLog.log(context, "COMPLETED", "task=$taskId elapsed=${elapsedSeconds ?: "?"}s")
                persistLastOutcome(TaskLifecycleState.COMPLETED)
                persistStreak(newStreak)
                markDayHadCompletion()
                persistCompletionLog(completionLog + CompletionRecord(System.currentTimeMillis(), newStreak))
                persistTaskQueue(taskQueue.map { if (it.task.id == taskId) it.complete() else it })
                scoreEntryToken++
                showingScore = true
            },
            onAbandon = { taskId ->
                DiagnosticLog.log(context, "ABANDONED", "task=$taskId")
                persistLastOutcome(TaskLifecycleState.ABANDONED)
                persistStreak(0)
                markDayHadFailure()
                persistTaskQueue(taskQueue.map { if (it.task.id == taskId) it.abandon() else it })
                scoreEntryToken++
                showingScore = true
            },
            onSubstitute = { taskId ->
                // Not a decline and not scored - the point is that nothing about this task's
                // outcome is being decided, it's simply being swapped for a different one in the
                // same queue slot. Guard against an empty pool: promptTasks is live (recomputed
                // from current categories/tasks), so a still-queued task's Substitute button can
                // be tapped after the user unchecked every category out from under it - with
                // nothing to substitute in, chooseWeightedTask would crash on an empty list, so
                // just no-op instead.
                if (promptTasks.isNotEmpty()) {
                    val queuedTaskIds = taskQueue.map { it.task.id }.toSet()
                    val candidates = promptTasks.filter { it.id !in queuedTaskIds }.ifEmpty { promptTasks }
                    val replacement = chooseWeightedTask(
                        tasks = candidates,
                        activeCategoryOrder = activeCategoryOrder,
                        previousTaskId = taskId
                    )
                    DiagnosticLog.log(context, "SUBSTITUTED", "task=$taskId replacement=${replacement.id}")
                    persistTaskQueue(taskQueue.map { if (it.task.id == taskId) TaskStackEntry(replacement) else it })
                }
            },
            onRefer = { taskId ->
                referralErrorMessage = null
                showingReferralMatrixFor = taskId
            },
            onNextPrompt = {
                val freshQueue = makeTaskStack(promptTasks, maxEntries = savedMaxQueueSize)
                freshQueue.forEach {
                    DiagnosticLog.log(
                        context, "QUEUED",
                        "task=${it.task.id} category=${it.task.category} duration=${it.task.durationMinutes}m"
                    )
                }
                persistTaskQueue(freshQueue)
                showingScore = false
            },
            backgroundPromptsRunning = backgroundPromptsRunning,
            onBackgroundPromptsChanged = { setBackgroundPrompts(it) },
            // Only after a flush FAILED does a queued change mean anything to the user: hidden
            // while a flush or sync is running, so a referral doesn't flash it for a second.
            pendingChangeCount = if (flushesRunning == 0 && !isImportingSheet) pendingChangeCount else 0
        )
    }
}

/** One sync's network results, gathered together so nothing is applied until all of it is in. */
private class FetchedSheet(
    val stillQueued: Int,
    val sheet: SheetImportResult,
    /** The referred-row keys from the Web App; null when no Web App is configured (or the Sheet read itself failed). */
    val referred: Result<Set<String>>?
)

/** A rough H:MM:SS for a non-negative countdown; clamps negatives to 0:00:00. */
fun formatCountdown(millis: Long): String {
    val total = (millis / 1000L).coerceAtLeast(0L)
    return "%d:%02d:%02d".format(total / 3600L, (total % 3600L) / 60L, total % 60L)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPromptScreen(
    taskEntries: List<TaskStackEntry>,
    streak: Int,
    maxQueueSize: Int,
    promptsPerDay: Int,
    nextDispatchEpoch: Long?,
    clockMillis: Long,
    queueFullFlash: Boolean,
    withinWindow: Boolean,
    vacationMode: Boolean,
    backgroundPromptsRunning: Boolean,
    onForceDispatch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScore: () -> Unit,
    onBackgroundPromptsChanged: (Boolean) -> Unit,
    onStart: (String) -> Unit,
    onComplete: (String) -> Unit,
    onAbandon: (String) -> Unit,
    onSubstitute: (String) -> Unit,
    onRefer: (String) -> Unit,
    onNextPrompt: () -> Unit,
    // Sheet writes still waiting to go through after a failed flush (PendingChanges); 0 hides the line.
    pendingChangeCount: Int = 0
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
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Task queue: ${taskEntries.size} active of $maxQueueSize",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (pendingChangeCount > 0) {
                item {
                    Text(
                        if (pendingChangeCount == 1) "1 change waiting to reach your Sheet"
                        else "$pendingChangeCount changes waiting to reach your Sheet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Key by id + position: ids are unique in a healthy queue, but a duplicate key is a
            // hard crash, so the index keeps it safe even if a stale/corrupt queue slips one in.
            itemsIndexed(taskEntries, key = { index, entry -> "${entry.task.id}#$index" }) { _, entry ->
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
                        text = if (entry.state == TaskLifecycleState.READY) {
                            "${task.category} • ${task.durationMinutes} min"
                        } else {
                            "${task.category} • ${task.durationMinutes} min • ${taskStateLabel(entry.state)}"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Queued ${formatQueuedAt(entry.queuedAtEpochMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    when (entry.state) {
                        TaskLifecycleState.READY -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Button(modifier = Modifier.weight(1f), onClick = { onStart(task.id) }) { Text("Start") }
                                ReferButton { onRefer(task.id) }
                                Button(modifier = Modifier.weight(1f), onClick = { onSubstitute(task.id) }) { Text("Substitute") }
                            }
                        }
                        TaskLifecycleState.STARTED -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Button(modifier = Modifier.weight(1f), onClick = { onComplete(task.id) }) { Text("Done") }
                                ReferButton { onRefer(task.id) }
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

        val countdownText = when {
            queueFullFlash -> "Queue full — finish one first"
            vacationMode -> "Hard paused — uncheck it in Settings to resume"
            promptsPerDay <= 0 -> "Automatic prompts off — set \"prompts per day\""
            !backgroundPromptsRunning && withinWindow -> "Paused — tap for a task now"
            !withinWindow && nextDispatchEpoch != null ->
                "Window opens in ${formatCountdown(nextDispatchEpoch - clockMillis)} — tap for one now"
            nextDispatchEpoch != null ->
                "Next task in ${formatCountdown(nextDispatchEpoch - clockMillis)} — tap for it now"
            else -> "Tap for a task now"
        }
        Surface(shadowElevation = 4.dp) {
            Text(
                text = countdownText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onForceDispatch() }
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                color = if (queueFullFlash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * The "Refer to ActiveTasks" hand-off button (see SPEC.md "Task referral to ActiveTasks"): a plain filled
 * Button, so it matches Start/Substitute/Done/Abandon, labeled "Activate" rather than an unlabeled
 * arrow icon. Sits in the middle of the Ready (Start / Substitute) and Started (Done / Abandon)
 * rows, which are exactly the two states where referral is available; referring a Started task
 * discards its timer.
 */
@Composable
private fun RowScope.ReferButton(onClick: () -> Unit) {
    Button(modifier = Modifier.weight(1f), onClick = onClick) {
        Text("Activate")
    }
}

/**
 * Full-screen Eisenhower-matrix touch capture for "Refer to ActiveTasks" (see SPEC.md "Task referral
 * to ActiveTasks"). Records the *precise* touch position, not just which quadrant it lands in: the
 * matrix box's horizontal position maps to urgency (left edge = 1.0, right edge = 0.0) and
 * vertical position maps to importance (top edge = 1.0, bottom edge = 0.0) - two continuous
 * floats, matching the quadrant layout ActiveTasks's own triage widget uses (Important/Not important
 * rows, Urgent/Not urgent columns; top-left = "Do First"). Two-step: tap/drag to place the
 * marker, then a separate Confirm button actually submits - a single accidental tap shouldn't
 * commit a referral.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EisenhowerReferralScreen(
    task: ManagedTask,
    submitting: Boolean,
    errorMessage: String?,
    onConfirm: (importance: Double, urgency: Double) -> Unit,
    onCancel: () -> Unit
) {
    var markerFraction by remember(task.id) { mutableStateOf<Offset?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Refer to ActiveTasks") },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel")
                }
            }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(task.description, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tap where this task falls on the matrix, then Confirm. Exact position matters, " +
                    "not just the quadrant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // The matrix is a square (the largest one that fits the space left over), centered.
            // The touch handlers live on the square itself so the fractions are relative to it.
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(minOf(maxWidth, maxHeight))
                        .pointerInput(task.id) {
                            detectTapGestures { offset ->
                                markerFraction = Offset(
                                    (offset.x / size.width).coerceIn(0f, 1f),
                                    (offset.y / size.height).coerceIn(0f, 1f)
                                )
                            }
                        }
                        .pointerInput(task.id) {
                            detectDragGestures { change, _ ->
                                markerFraction = Offset(
                                    (change.position.x / size.width).coerceIn(0f, 1f),
                                    (change.position.y / size.height).coerceIn(0f, 1f)
                                )
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val midX = size.width / 2f
                        val midY = size.height / 2f
                        val gridColor = Color.Gray
                        drawRect(color = gridColor, size = size, style = Stroke(width = 2f))
                        drawLine(gridColor, Offset(midX, 0f), Offset(midX, size.height), strokeWidth = 2f)
                        drawLine(gridColor, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 2f)
                        markerFraction?.let { fraction ->
                            drawCircle(
                                color = Color.Red,
                                radius = 18f,
                                center = Offset(fraction.x * size.width, fraction.y * size.height)
                            )
                        }
                    }
                    // Urgency labels sit at the vertical middle of the left/right edges so they
                    // don't crowd the "Important" label at the top center.
                    Text("Urgent", modifier = Modifier.align(Alignment.CenterStart).padding(6.dp), style = MaterialTheme.typography.labelMedium)
                    Text("Not urgent", modifier = Modifier.align(Alignment.CenterEnd).padding(6.dp), style = MaterialTheme.typography.labelMedium)
                    Text("Important", modifier = Modifier.align(Alignment.TopCenter).padding(6.dp), style = MaterialTheme.typography.labelMedium)
                    Text("Not important", modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp), style = MaterialTheme.typography.labelMedium)
                }
            }

            if (errorMessage != null) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onCancel, enabled = !submitting) {
                    Text("Cancel")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = markerFraction != null && !submitting,
                    onClick = {
                        val fraction = markerFraction ?: return@Button
                        val importance = (1.0 - fraction.y).toDouble()
                        val urgency = (1.0 - fraction.x).toDouble()
                        onConfirm(importance, urgency)
                    }
                ) {
                    Text(if (submitting) "Referring…" else "Confirm")
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
    cleanDayStreak: Int,
    cleanDayStreakLongest: Int,
    completionLog: List<CompletionRecord>,
    promptsPerDay: Int,
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
            val weekLongest = longestStreakSince(completionLog, nowMs - weekScoreWindowMillis(promptsPerDay))
            val monthLongest = longestStreakSince(completionLog, nowMs - monthScoreWindowMillis(now, promptsPerDay))
            Text(
                text = "Longest streak",
                modifier = Modifier.padding(top = 32.dp),
                style = MaterialTheme.typography.labelLarge
            )
            Text("Today: $todayLongest")
            Text("This week: $weekLongest")
            Text("This month: $monthLongest")
            Text("All time: $longestStreak")
            Text(
                text = "Clean days: $cleanDayStreak (best $cleanDayStreakLongest)",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
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
    initialWebAppUrl: String = "",
    /** A sync is running right now (any trigger) - shown as "Syncing…" under the connection fields. */
    isImportingSheet: Boolean = false,
    /** True from a Save that changed the connection until the sync it started finishes: Save shows "Syncing…" and is disabled. */
    saving: Boolean = false,
    /** The last sync's outcome (or why it couldn't run), shown under the connection fields. */
    importMessage: String? = null,
    backgroundPromptsRunning: Boolean,
    vacationMode: Boolean,
    // Accordion: at most one section open at a time. "" means all collapsed. Hoisted by the caller
    // (not a local `remember` here) so a failed Save-triggered sync can force the connection
    // section open to show its error.
    openSection: String,
    onOpenSectionChanged: (String) -> Unit,
    onOpenMyTasks: () -> Unit,
    onOpenTaskPool: () -> Unit,
    onCancel: () -> Unit,
    onBackgroundPromptsChanged: (Boolean) -> Unit,
    onVacationModeChanged: (Boolean) -> Unit,
    /** (categories, start, end, prompts, queueSize, sheetUrl, webAppUrl) - nothing is saved or synced until this is called. */
    onSave: (Set<String>, String, String, String, Int, String, String) -> Unit
) {
    // Every field below is a draft: nothing is saved until Save Settings (Cancel just discards it),
    // and a QR scan fills the same drafts. The scanner is shown from inside this composable rather
    // than as a separate top-level screen precisely so that going to scan never unmounts it and
    // loses whatever else was being edited.
    var selectedCategories by remember { mutableStateOf(initialCategories) }
    var startHour by remember { mutableStateOf(initialStartHour) }
    var endHour by remember { mutableStateOf(initialEndHour) }
    var promptsPerDay by remember { mutableStateOf(initialPromptsPerDay) }
    var maxQueueSize by remember { mutableStateOf(initialMaxQueueSize.toString()) }
    var sheetUrl by remember { mutableStateOf(initialSheetUrl) }
    var webAppUrl by remember { mutableStateOf(initialWebAppUrl) }
    var scanning by remember { mutableStateOf(false) }
    // Set by a scan ("Scanned - press Save Settings to connect"); replaces the last sync's message
    // until the next Save, so the user isn't left thinking the scan already did something.
    var scanNote by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    if (scanning) {
        QrScannerScreen(
            onResult = { scanned ->
                scanning = false
                // Each scanned line is classified by what it looks like (see parseSetupQr); a field
                // the scan didn't carry is left exactly as it was. Fills the drafts only.
                val payload = parseSetupQr(scanned)
                payload.sheetUrl?.let { sheetUrl = it }
                payload.webAppUrl?.let { webAppUrl = it }
                scanNote = if (payload.sheetUrl == null && payload.webAppUrl == null) {
                    "That QR code was empty - nothing was changed."
                } else {
                    "Scanned. Press Save Settings to connect."
                }
            },
            onCancel = { scanning = false }
        )
        return
    }

    @Composable
    fun sectionHeader(title: String) {
        val expanded = openSection == title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenSectionChanged(if (expanded) "" else title) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand"
            )
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
                        sectionHeader("Task Categories")
                        if (openSection == "Task Categories") {
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
                            // Bounded + independently scrollable so a large category list never
                            // pushes the rest of the section off-screen.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
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
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        sectionHeader("Prompting Schedule")
                        if (openSection == "Prompting Schedule") {
                            // Pause/Resume lives on the main task-list screen (and the score
                            // screen) - a second copy here was redundant, so it's not repeated.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = vacationMode,
                                    onCheckedChange = onVacationModeChanged
                                )
                                Text("Hard pause — stop everything until I uncheck this")
                            }
                            Text(
                                "Overrides Pause/Resume and the active window entirely - while checked, nothing " +
                                    "dispatches and nothing wakes the queue back up, not even at the start of the " +
                                    "window. Uncheck it here to resume.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                                "Start 0 and end 24 means the active window never ends. A task is only ever lost by " +
                                    "being pushed off the top of a full queue before you get to it - the window closing " +
                                    "no longer clears the queue.",
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
                        sectionHeader("Local Task Management")
                        if (openSection == "Local Task Management") {
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
                        // Same section, wording and behavior as ActiveTasks' Settings: why a Sheet URL ->
                        // box -> Scan; why a Web App URL -> box -> Scan. No sync button: scanning and
                        // typing only edit the draft, and Save Settings is the one place a changed
                        // connection saves and syncs. Placed right above About - Task
                        // Categories/Prompting Schedule above are more likely to be revisited than
                        // this one-time setup section.
                        sectionHeader("Google Sheet Connection")
                        if (openSection == "Google Sheet Connection") {
                            Text(
                                "Your tasks live in a Google Sheet you own. Paste its URL, or scan the Sheet QR code from the onboarding page, so the app can read it - each tab (except one named \"README\") becomes a task category.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = sheetUrl,
                                onValueChange = { sheetUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Google Sheet URL") },
                                placeholder = { Text("https://docs.google.com/spreadsheets/d/...") },
                                singleLine = true
                            )
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { scanning = true }
                            ) {
                                Text("Scan Sheet QR Code")
                            }
                            Text(
                                "The Web App is a small script inside your Sheet that lets the app write back to it - " +
                                    "referring a task to ActiveTasks. Deploy it once from your Sheet (Extensions → " +
                                    "Apps Script → Deploy → New deployment → Web app), then paste its URL or scan " +
                                    "its QR code from the onboarding page.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = webAppUrl,
                                onValueChange = { webAppUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Apps Script Web App URL") },
                                placeholder = { Text("https://script.google.com/macros/s/.../exec") },
                                singleLine = true
                            )
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { scanning = true }
                            ) {
                                Text("Scan Web App QR Code")
                            }
                            val statusMessage = when {
                                saving || isImportingSheet -> "Syncing…"
                                scanNote != null -> scanNote
                                else -> importMessage
                            }
                            if (!statusMessage.isNullOrBlank()) {
                                Text(
                                    statusMessage,
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
                        sectionHeader("About")
                        if (openSection == "About") {
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
                    enabled = !saving && (selectedCategories.isNotEmpty() || availableCategories.isEmpty()),
                    onClick = {
                        scanNote = null
                        onSave(
                            selectedCategories,
                            (startHour.toIntOrNull() ?: 0).coerceIn(0, 24).toString(),
                            (endHour.toIntOrNull() ?: 24).coerceIn(0, 24).toString(),
                            (promptsPerDay.toIntOrNull() ?: 0).coerceAtLeast(0).toString(),
                            maxQueueSize.toIntOrNull()?.coerceAtLeast(1) ?: 3,
                            sheetUrl.trim(),
                            webAppUrl.trim()
                        )
                    }
                ) {
                    Text(if (saving) "Syncing…" else "Save Settings")
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
        DiagnosticLog.log(context, "PERMISSION", "CAMERA ${if (granted) "granted" else "denied"}")
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

/**
 * Splits one CSV line into fields, honoring `"`-quoted fields: a comma inside quotes is literal
 * and `""` is an escaped quote. Doesn't span newlines (callers work line by line), so a sheet
 * cell containing a literal newline won't round-trip - every description the app uses is a single
 * line, so that's fine.
 */
fun splitCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                field.append('"')
                i++
            }
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> {
                fields.add(field.toString())
                field.setLength(0)
            }
            else -> field.append(c)
        }
        i++
    }
    fields.add(field.toString())
    return fields.map { it.trim() }
}

fun parseExternalTaskCsv(csvText: String, categoryName: String): List<ManagedTask> {
    if (csvText.isBlank()) return emptyList()

    val rows = csvText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { splitCsvLine(it) }
        .toList()

    if (rows.isEmpty()) return emptyList()

    // Column A is always the per-row enabled toggle - a bare checkbox with no header text. The
    // description and link columns are still found by header name so extra columns or a reordering
    // don't break the import.
    val header = rows.first().map { it.lowercase() }
    val descriptionIndex = header.indexOfFirst { it.contains("description") }
    val linkIndex = header.indexOfFirst { it.contains("link") || it.contains("url") }
    // DEV (sheet-surrogate-keys): matched by header text like description/link, so column
    // reordering stays safe - see ensureTaskIds_ in populate_google_sheet.js. Hidden columns
    // still ride along in the CSV/gviz export regardless of Sheets-UI hidden state (the same
    // property Importance/Urgency deliberately avoid relying on, since those need to stay
    // invisible even to someone inspecting the export - a task id isn't sensitive, so reading it
    // straight off the CSV needs no Web App call). Absent (a sheet not yet repaired to have the
    // column) just means every task on that tab falls back to the legacy text-based id below.
    val taskIdIndex = header.indexOfFirst { it == "task id" || it == "taskid" }
    if (descriptionIndex == -1) return emptyList()

    val dataRows = rows.drop(1)
    // A Google Sheets checkbox exports as "TRUE"/"FALSE"; a cell with no checkbox exports blank.
    // If any data row has a checkbox in column A, column A is authoritative and a blank there means
    // disabled. If the tab has no checkboxes at all (a sheet from before this convention), import
    // everything as enabled so nobody silently loses their pool.
    val columnA = dataRows.map { it.firstOrNull()?.lowercase().orEmpty() }
    val tabUsesCheckboxes = columnA.any { it == "true" || it == "false" }

    return dataRows.mapIndexedNotNull { index, row ->
        if (row.size <= descriptionIndex) return@mapIndexedNotNull null
        val description = row[descriptionIndex].trim()
        if (description.isEmpty()) return@mapIndexedNotNull null
        val enabled = if (tabUsesCheckboxes) columnA[index] == "true" else true
        val link = row.getOrNull(linkIndex).orEmpty().trim()
        val taskId = row.getOrNull(taskIdIndex).orEmpty().trim().ifEmpty { null }
        ManagedTask(
            // DEV (sheet-surrogate-keys): taskId-based when the sheet has one - stable across a
            // description edit, so a re-sync recognizes it as the same task (mergeImportedManagedTasks
            // merges by id) and its in-app flags survive the rename instead of being lost. Falls
            // back to the legacy text-based id for a sheet not yet repaired to have task ids -
            // deterministic there too, but a description edit still reads as remove-old + add-new.
            id = taskId?.let { "external-$it" } ?: "external-$categoryName-$description",
            description = description,
            category = categoryName,
            durationMinutes = 5,
            builtIn = false,
            enabled = enabled,
            temporarilyUnavailable = false,
            neverSuggest = false,
            taskId = taskId,
            link = link
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

/**
 * Fetches one tab's rows as CSV, addressed by tab name rather than gid. Null means the fetch
 * FAILED - deliberately distinct from `""`, a tab that was read fine and simply has no rows, since
 * treating a failed fetch as an empty tab would silently drop that tab's tasks on the next sync.
 */
private fun fetchSheetTabCsv(spreadsheetId: String, tabName: String): String? = runCatching {
    val encodedName = URLEncoder.encode(tabName, "UTF-8")
    val url = "https://docs.google.com/spreadsheets/d/$spreadsheetId/gviz/tq?tqx=out:csv&sheet=$encodedName"
    URL(url).readText()
}.getOrNull()

/**
 * [failed] means the Sheet could not be read completely (see [assembleSheetImport]) - the caller
 * must then change nothing locally, never treat what did come back as "the whole Sheet".
 */
data class SheetImportResult(val tasks: List<ManagedTask>, val tabNames: List<String>, val failed: Boolean = false)

/**
 * Reads every tab in [tabNames] via [fetchTabCsv] and parses it - or fails as a whole. A sync is
 * all-or-nothing (SPEC.md "Synchronization"): if even one tab's fetch fails the result is [failed]
 * with no tasks, because a partial read is indistinguishable from "those tabs are empty" and would
 * make the merge delete their tasks.
 */
fun assembleSheetImport(tabNames: List<String>, fetchTabCsv: (String) -> String?): SheetImportResult {
    val tasks = mutableListOf<ManagedTask>()
    for (tabName in tabNames) {
        val csv = fetchTabCsv(tabName) ?: return SheetImportResult(emptyList(), tabNames, failed = true)
        tasks += parseExternalTaskCsv(csv, tabName)
    }
    return SheetImportResult(tasks, tabNames)
}

/**
 * What pressing Save Settings has to do about the Sheet connection (SPEC.md "Synchronization").
 * [sheetSwitched]: the spreadsheet id changed, so everything local to the old Sheet is discarded
 * first - decided by id, not text, so writing the same Sheet's URL another way (`/edit#gid=`, a bare
 * `/d/<id>` from a QR code) isn't a switch, and a blank or unparseable URL never is (a typo must not
 * throw away the task pool). [shouldSync]: the URL or the Web App URL actually changed and there is a
 * Sheet to read; saving with the connection untouched doesn't sync.
 */
data class SettingsSaveDecision(val sheetSwitched: Boolean, val shouldSync: Boolean)

fun decideSettingsSave(
    savedSheetUrl: String, newSheetUrl: String,
    savedWebAppUrl: String, newWebAppUrl: String
): SettingsSaveDecision {
    val oldId = extractGoogleSheetId(savedSheetUrl)
    val newId = extractGoogleSheetId(newSheetUrl)
    val connectionChanged = newSheetUrl.trim() != savedSheetUrl.trim() || newWebAppUrl.trim() != savedWebAppUrl.trim()
    return SettingsSaveDecision(
        sheetSwitched = oldId != null && newId != null && oldId != newId,
        shouldSync = connectionChanged && newSheetUrl.isNotBlank()
    )
}

fun importExternalTasksFromSheet(url: String): SheetImportResult {
    val spreadsheetId = extractGoogleSheetId(url) ?: return SheetImportResult(emptyList(), emptyList())
    val tabNames = fetchSheetTabNamesViaXlsx(spreadsheetId)
        .ifEmpty { fetchSheetTabNames(spreadsheetId) }
        .filter { !it.equals("README", ignoreCase = true) }

    if (tabNames.isNotEmpty()) {
        return assembleSheetImport(tabNames) { tabName -> fetchSheetTabCsv(spreadsheetId, tabName) }
    }

    // Tab enumeration unavailable (e.g. sharing settings blocked it) - fall back to a single
    // CSV export so import still works, just without per-tab categories. A failed fetch here is a
    // failed read, not an empty Sheet.
    val fallback = runCatching {
        val csv = URL(normalizeGoogleSheetCsvUrl(url)).readText()
        parseExternalTaskCsv(csv, "Imported")
    }
    return if (fallback.isSuccess) SheetImportResult(fallback.getOrThrow(), emptyList())
    else SheetImportResult(emptyList(), emptyList(), failed = true)
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

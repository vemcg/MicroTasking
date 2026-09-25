// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-25, after version v0.2.0-84 main 2026-09-25
package com.microtasking.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for DEFECTS.md item 8 (jointly diagnosed with ActiveTasks, 2026-09-25): a
 * "fullyCompleted" broadcast that actually removes a task must also mark it in
 * [RecentlyRemovedTasks], so the very next Sheet sync can't resurrect it from a stale CSV cache read.
 */
@RunWith(RobolectricTestRunner::class)
class TaskEventReceiverTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TaskDelivery.prefs(context).edit().clear().commit()
    }

    private fun fullyCompletedIntent(taskId: String? = "row-42", category: String = "Decluttering", description: String = "Empty one trash bin") =
        Intent(TaskEventContract.ACTION)
            .putExtra(TaskEventContract.EXTRA_SCHEMA_VERSION, TaskEventContract.SCHEMA_VERSION)
            .putExtra(TaskEventContract.EXTRA_EVENT, TaskEventContract.EVENT_FULLY_COMPLETED)
            .putExtra(TaskEventContract.EXTRA_EVENT_AT, 5_000L)
            .apply { if (taskId != null) putExtra(TaskEventContract.EXTRA_TASK_ID, taskId) }
            .putExtra(TaskEventContract.EXTRA_CATEGORY, category)
            .putExtra(TaskEventContract.EXTRA_DESCRIPTION, description)

    @Test
    fun onReceive_marksARemovedTaskId_whenFullyCompletedActuallyRemovesIt() {
        val tasks = listOf(ManagedTask(id = "external-row-42", taskId = "row-42", description = "Empty one trash bin", category = "Decluttering", durationMinutes = 5, builtIn = false))
        TaskDelivery.prefs(context).edit().putString("managed_tasks", writeManagedTasks(tasks)).commit()

        TaskEventReceiver().onReceive(context, fullyCompletedIntent())

        val removed = RecentlyRemovedTasks.current(context)
        assertEquals(1, removed.size)
        assertEquals("row-42", removed.single().key)
        assertTrue(
            "the task itself must actually be gone from managed_tasks",
            readManagedTasks(TaskDelivery.prefs(context).getString("managed_tasks", "[]") ?: "[]").isEmpty()
        )
    }

    @Test
    fun onReceive_marksNothing_whenTheEventDidntActuallyChangeAnything() {
        // No matching task in managed_tasks to begin with - applyIncomingEvent is a no-op.
        TaskEventReceiver().onReceive(context, fullyCompletedIntent())

        assertTrue(RecentlyRemovedTasks.current(context).isEmpty())
    }

    @Test
    fun onReceive_fallsBackToTheLegacyKey_whenTheRowHasNoTaskId() {
        val tasks = listOf(ManagedTask(id = "external-Decluttering-Empty one trash bin", taskId = null, description = "Empty one trash bin", category = "Decluttering", durationMinutes = 5, builtIn = false))
        TaskDelivery.prefs(context).edit().putString("managed_tasks", writeManagedTasks(tasks)).commit()

        TaskEventReceiver().onReceive(context, fullyCompletedIntent(taskId = null))

        assertEquals("Decluttering|Empty one trash bin", RecentlyRemovedTasks.current(context).single().key)
    }

    @Test
    fun onReceive_completedForNow_neverMarksAnythingRemoved() {
        val tasks = listOf(
            ManagedTask(id = "external-row-42", taskId = "row-42", description = "Empty one trash bin", category = "Decluttering", durationMinutes = 5, builtIn = false, referredAt = 1_000L)
        )
        TaskDelivery.prefs(context).edit().putString("managed_tasks", writeManagedTasks(tasks)).commit()

        TaskEventReceiver().onReceive(
            context,
            Intent(TaskEventContract.ACTION)
                .putExtra(TaskEventContract.EXTRA_SCHEMA_VERSION, TaskEventContract.SCHEMA_VERSION)
                .putExtra(TaskEventContract.EXTRA_EVENT, TaskEventContract.EVENT_COMPLETED_FOR_NOW)
                .putExtra(TaskEventContract.EXTRA_EVENT_AT, 5_000L)
                .putExtra(TaskEventContract.EXTRA_TASK_ID, "row-42")
                .putExtra(TaskEventContract.EXTRA_CATEGORY, "Decluttering")
                .putExtra(TaskEventContract.EXTRA_DESCRIPTION, "Empty one trash bin")
        )

        assertTrue("the task is back in the pool, not deleted - nothing should be marked removed", RecentlyRemovedTasks.current(context).isEmpty())
    }
}

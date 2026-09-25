// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-24, after version v0.2.0-81 main 2026-09-24
package com.microtasking.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The cross-app message contract, v1 (SPEC.md "Synchronization" > "Message contract, v1"). The
 * extras and names asserted here are the wire format ActiveTasks implements the other end of - a
 * change to any of them has to be made in both apps at once.
 */
@RunWith(RobolectricTestRunner::class)
class TaskEventsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TaskDelivery.prefs(context).edit().clear().commit()
    }

    private fun referral(taskId: String? = "t-1", link: String = "https://example.com") = PendingChange(
        id = "q1", taskId = taskId, category = "Cleaning", description = "Wipe the counters",
        link = link, importance = 0.8, urgency = 0.3, queuedAtEpochMs = 1_700_000_000_000L
    )

    private fun incoming(
        event: String,
        at: Long = 2_000L,
        taskId: String? = "t-1",
        category: String = "Cleaning",
        description: String = "Wipe the counters",
        schema: Int = 1
    ) = Intent(TaskEventContract.ACTION)
        .putExtra(TaskEventContract.EXTRA_SCHEMA_VERSION, schema)
        .putExtra(TaskEventContract.EXTRA_EVENT, event)
        .putExtra(TaskEventContract.EXTRA_EVENT_AT, at)
        .apply { if (taskId != null) putExtra(TaskEventContract.EXTRA_TASK_ID, taskId) }
        .putExtra(TaskEventContract.EXTRA_CATEGORY, category)
        .putExtra(TaskEventContract.EXTRA_DESCRIPTION, description)

    private fun task(taskId: String? = "t-1", referredAt: Long? = 1_000L, category: String = "Cleaning", description: String = "Wipe the counters") =
        ManagedTask(
            id = "external-${taskId ?: "$category-$description"}", description = description, category = category,
            durationMinutes = 5, builtIn = false, referredAt = referredAt, taskId = taskId
        )

    // --- sending: the wire format --------------------------------------------------------

    @Test
    fun referredIntent_matchesTheContract() {
        val intent = buildReferredIntent(referral())

        assertEquals("com.vernmcgeorge.tasks.action.TASK_EVENT", intent.action)
        assertEquals("com.activetasks.app", intent.component?.packageName)
        assertEquals("com.activetasks.app.TaskEventReceiver", intent.component?.className)
        assertEquals(1, intent.getIntExtra("schemaVersion", 0))
        assertEquals("referred", intent.getStringExtra("event"))
        assertEquals("t-1", intent.getStringExtra("taskId"))
        assertEquals("Cleaning", intent.getStringExtra("category"))
        assertEquals("Wipe the counters", intent.getStringExtra("description"))
        assertEquals("https://example.com", intent.getStringExtra("link"))
        assertEquals(0.8, intent.getDoubleExtra("importance", -1.0), 0.0)
        assertEquals(0.3, intent.getDoubleExtra("urgency", -1.0), 0.0)
    }

    @Test
    fun referredIntent_carriesTheTimeTheUserReferredNotTheTimeItWasSent() {
        val intent = buildReferredIntent(referral())

        assertEquals(1_700_000_000_000L, intent.getLongExtra("eventAtEpochMs", 0L))
    }

    @Test
    fun referredIntent_omitsTaskIdForARowThatHasNone_andAllowsAnEmptyLink() {
        val intent = buildReferredIntent(referral(taskId = null, link = ""))

        assertTrue("taskId may be absent for a pre-taskId row", !intent.hasExtra("taskId"))
        assertEquals("", intent.getStringExtra("link"))
    }

    @Test
    fun contractConstants_areExactlyTheAgreedStrings() {
        assertEquals("com.vernmcgeorge.tasks.permission.TASK_EVENTS", TaskEventContract.PERMISSION)
        assertEquals("com.vernmcgeorge.tasks.action.TASK_EVENT", TaskEventContract.ACTION)
        assertEquals("referred", TaskEventContract.EVENT_REFERRED)
        assertEquals("completedForNow", TaskEventContract.EVENT_COMPLETED_FOR_NOW)
        assertEquals("fullyCompleted", TaskEventContract.EVENT_FULLY_COMPLETED)
    }

    // --- receiving: decoding -------------------------------------------------------------

    @Test
    fun parse_decodesAWellFormedMessage() {
        val event = parseTaskEvent(incoming("completedForNow", at = 4_242L))!!

        assertEquals("completedForNow", event.event)
        assertEquals(4_242L, event.eventAtEpochMs)
        assertEquals("t-1", event.taskId)
        assertEquals("Cleaning", event.category)
        assertEquals("Wipe the counters", event.description)
    }

    @Test
    fun parse_aReferredMessageRoundTrips() {
        val event = parseTaskEvent(buildReferredIntent(referral()))!!

        assertEquals("referred", event.event)
        assertEquals("https://example.com", event.link)
        assertEquals(0.8, event.importance!!, 0.0)
        assertEquals(0.3, event.urgency!!, 0.0)
    }

    @Test
    fun parse_taskIdMayBeAbsent() {
        assertNull(parseTaskEvent(incoming("completedForNow", taskId = null))!!.taskId)
    }

    @Test
    fun parse_ignoresANewerSchemaVersionItDoesNotKnow() {
        assertNull(parseTaskEvent(incoming("completedForNow", schema = 2)))
    }

    @Test
    fun parse_ignoresMalformedMessages() {
        assertNull("wrong action", parseTaskEvent(Intent("some.other.ACTION")))
        assertNull("no intent", parseTaskEvent(null))
        assertNull("unknown event", parseTaskEvent(incoming("somethingNew")))
        assertNull("no schemaVersion", parseTaskEvent(Intent(TaskEventContract.ACTION).putExtra("event", "completedForNow")))
        assertNull(
            "no timestamp",
            parseTaskEvent(
                Intent(TaskEventContract.ACTION)
                    .putExtra("schemaVersion", 1).putExtra("event", "completedForNow")
                    .putExtra("category", "Cleaning").putExtra("description", "x")
            )
        )
        assertNull(
            "no description",
            parseTaskEvent(
                Intent(TaskEventContract.ACTION)
                    .putExtra("schemaVersion", 1).putExtra("event", "completedForNow")
                    .putExtra("eventAtEpochMs", 1L).putExtra("category", "Cleaning")
            )
        )
    }

    // --- receiving: applying -------------------------------------------------------------

    private fun completedForNow(at: Long = 2_000L, taskId: String? = "t-1", description: String = "Wipe the counters") =
        parseTaskEvent(incoming("completedForNow", at = at, taskId = taskId, description = description))!!

    private fun fullyCompleted(at: Long = 2_000L, taskId: String? = "t-1", description: String = "Wipe the counters") =
        parseTaskEvent(incoming("fullyCompleted", at = at, taskId = taskId, description = description))!!

    @Test
    fun completedForNow_putsTheTaskBackInThePool() {
        val result = applyIncomingEvent(listOf(task(referredAt = 1_000L)), completedForNow(at = 2_000L))

        assertNull(result.single().referredAt)
    }

    @Test
    fun completedForNow_findsTheTaskByTaskIdEvenIfItsTextChanged() {
        val result = applyIncomingEvent(listOf(task(description = "Reworded since")), completedForNow(description = "Wipe the counters"))

        assertNull(result.single().referredAt)
    }

    @Test
    fun completedForNow_fallsBackToCategoryAndDescriptionWhenThereIsNoTaskId() {
        val noIds = listOf(task(taskId = null))

        assertNull(applyIncomingEvent(noIds, completedForNow(taskId = null)).single().referredAt)
        // ... and when the local task has an id but the message doesn't (a pre-taskId row on the other side).
        assertNull(applyIncomingEvent(listOf(task(taskId = "t-1")), completedForNow(taskId = null)).single().referredAt)
    }

    @Test
    fun completedForNow_twoDifferentTaskIdsAreDifferentTasksEvenWithIdenticalText() {
        val other = listOf(task(taskId = "some-other-row"))

        assertEquals(1_000L, applyIncomingEvent(other, completedForNow(taskId = "t-1")).single().referredAt)
    }

    @Test
    fun completedForNow_aStaleEventDoesNotUndoALaterReferral() {
        // Referred again at 5_000; a late-delivered completion from before that must not clear it.
        val result = applyIncomingEvent(listOf(task(referredAt = 5_000L)), completedForNow(at = 2_000L))

        assertEquals(5_000L, result.single().referredAt)
    }

    @Test
    fun completedForNow_deliveredTwiceIsHarmless() {
        val once = applyIncomingEvent(listOf(task()), completedForNow())
        val twice = applyIncomingEvent(once, completedForNow())

        assertEquals(once, twice)
    }

    @Test
    fun completedForNow_forATaskThatIsNotReferredChangesNothing() {
        val tasks = listOf(task(referredAt = null))

        assertEquals(tasks, applyIncomingEvent(tasks, completedForNow()))
    }

    @Test
    fun fullyCompleted_dropsTheTask() {
        val keep = task(taskId = "t-2", description = "Something else")

        val result = applyIncomingEvent(listOf(task(), keep), fullyCompleted())

        assertEquals(listOf(keep), result)
    }

    @Test
    fun fullyCompleted_aStaleEventDoesNotDropATaskReferredAgainSince() {
        val referredAgain = task(referredAt = 9_000L)

        assertEquals(listOf(referredAgain), applyIncomingEvent(listOf(referredAgain), fullyCompleted(at = 2_000L)))
    }

    @Test
    fun fullyCompleted_dropsATaskThatWasNeverMarkedReferredHere() {
        // e.g. referred from another device: the row is deleted from the Sheet either way.
        assertTrue(applyIncomingEvent(listOf(task(referredAt = null)), fullyCompleted()).isEmpty())
    }

    @Test
    fun referredMessages_areForTheOtherAppAndChangeNothingHere() {
        val tasks = listOf(task(referredAt = null))

        assertEquals(tasks, applyIncomingEvent(tasks, parseTaskEvent(buildReferredIntent(referral()))!!))
    }

    // --- the receiver, end to end --------------------------------------------------------

    private fun savedTasks() = readManagedTasks(TaskDelivery.prefs(context).getString("managed_tasks", "[]") ?: "[]")

    @Test
    fun receiver_writesTheChangeIntoTheSavedTaskListWithNoNetworkAndNoSheetRead() {
        TaskDelivery.prefs(context).edit()
            .putString("managed_tasks", writeManagedTasks(listOf(task(referredAt = 1_000L), task(taskId = "t-2", referredAt = 1_000L, description = "Other"))))
            .commit()

        TaskEventReceiver().onReceive(context, incoming("completedForNow", at = 2_000L))

        val byId = savedTasks().associateBy { it.taskId }
        assertNull("the completed task is back in the pool", byId.getValue("t-1").referredAt)
        assertEquals("the other task is untouched", 1_000L, byId.getValue("t-2").referredAt)
    }

    @Test
    fun receiver_fullyCompletedRemovesTheTaskFromTheSavedList() {
        TaskDelivery.prefs(context).edit().putString("managed_tasks", writeManagedTasks(listOf(task()))).commit()

        TaskEventReceiver().onReceive(context, incoming("fullyCompleted", at = 2_000L))

        assertTrue(savedTasks().isEmpty())
    }

    @Test
    fun receiver_ignoresAMessageItDoesNotUnderstand() {
        val before = listOf(task())
        TaskDelivery.prefs(context).edit().putString("managed_tasks", writeManagedTasks(before)).commit()

        TaskEventReceiver().onReceive(context, incoming("completedForNow", schema = 99))
        TaskEventReceiver().onReceive(context, null)

        assertEquals(before, savedTasks())
    }

    @Test
    fun receiver_worksWhenTheAppHasNothingSavedYet() {
        TaskEventReceiver().onReceive(context, incoming("completedForNow"))

        assertNotNull(savedTasks())
        assertTrue(savedTasks().isEmpty())
    }
}

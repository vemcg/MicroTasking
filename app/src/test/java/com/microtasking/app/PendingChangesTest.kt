// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-24, after version v0.2.0-81 main 2026-09-24
package com.microtasking.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pending-changes queue (SPEC.md "Synchronization" > "Pending-changes queue"): every Sheet
 * write goes through a persisted queue, so an offline or failed write is retried, never lost, and
 * is never "corrected" away by a sync that reads the Sheet before the write got there.
 */
@RunWith(RobolectricTestRunner::class)
class PendingChangesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TaskDelivery.prefs(context).edit().clear().commit()
    }

    private fun change(
        id: String,
        taskId: String? = "t-$id",
        category: String = "Cleaning",
        description: String = "Task $id",
        queuedAt: Long = 1_000L,
        importance: Double = 0.5
    ) = PendingChange(
        id = id, taskId = taskId, category = category, description = description,
        link = "", importance = importance, urgency = 0.5, queuedAtEpochMs = queuedAt
    )

    // --- queue rules ---------------------------------------------------------------------

    @Test
    fun enqueue_newerChangeForTheSameRowReplacesTheOlderUnsentOne() {
        val first = change("a", taskId = "row-1", importance = 0.2)
        val second = change("b", taskId = "row-1", importance = 0.9)

        val queue = enqueuePending(enqueuePending(emptyList(), first), second)

        assertEquals(listOf("b"), queue.map { it.id })
        assertEquals(0.9, queue.single().importance, 0.0)
    }

    @Test
    fun enqueue_differentRowsBothStay() {
        val queue = enqueuePending(enqueuePending(emptyList(), change("a", taskId = "row-1")), change("b", taskId = "row-2"))

        assertEquals(listOf("a", "b"), queue.map { it.id })
    }

    @Test
    fun enqueue_rowsWithoutTaskIdsAreMatchedByCategoryAndDescription() {
        val a = change("a", taskId = null, category = "Cleaning", description = "Wipe counters")
        val sameRow = change("b", taskId = null, category = "Cleaning", description = "Wipe counters")
        val otherRow = change("c", taskId = null, category = "Cleaning", description = "Vacuum")

        val queue = enqueuePending(enqueuePending(enqueuePending(emptyList(), a), sameRow), otherRow)

        assertEquals(listOf("b", "c"), queue.map { it.id })
    }

    @Test
    fun sameRow_twoDifferentTaskIdsAreTwoRowsEvenWithIdenticalText() {
        assertFalse(sameRow("x", "Cleaning", "Same text", "y", "Cleaning", "Same text"))
        assertTrue(sameRow("x", "Old tab name", "Old text", "x", "New tab name", "New text"))
        assertTrue(sameRow(null, "Cleaning", "Same text", "y", "Cleaning", "Same text"))
    }

    // --- persistence ---------------------------------------------------------------------

    @Test
    fun queueSurvivesBeingReadBackFromStorage() {
        PendingChanges.enqueue(context, change("a", taskId = "row-1", queuedAt = 5L))
        PendingChanges.enqueue(context, change("b", taskId = null, queuedAt = 6L))

        // A different reader of the same prefs sees the same queue - i.e. it outlives the process.
        val read = readPendingChanges(TaskDelivery.prefs(context).getString(PendingChanges.PREFS_KEY, "[]")!!)

        assertEquals(listOf("a", "b"), read.map { it.id })
        assertEquals("row-1", read[0].taskId)
        assertNull(read[1].taskId)
        assertEquals(5L, read[0].queuedAtEpochMs)
        assertEquals(2, PendingChanges.count(context))
    }

    @Test
    fun clear_dropsEverythingQueued() {
        PendingChanges.enqueue(context, change("a"))
        PendingChanges.clear(context)

        assertEquals(0, PendingChanges.count(context))
    }

    // --- flush ---------------------------------------------------------------------------

    @Test
    fun flush_successDequeuesThenReportsItSoTheMessageGoesOutAfterTheSheetHasIt() {
        PendingChanges.enqueue(context, change("a"))
        val sentMessages = mutableListOf<String>()
        val queueSizeWhenMessageSent = mutableListOf<Int>()

        val remaining = PendingChanges.flush(
            context,
            send = { SendOutcome.SUCCESS },
            onSent = {
                sentMessages += it.id
                queueSizeWhenMessageSent += PendingChanges.count(context)
            }
        )

        assertEquals(0, remaining)
        assertEquals(listOf("a"), sentMessages)
        // The change was already off the queue (the Sheet had it) by the time the message went out.
        assertEquals(listOf(0), queueSizeWhenMessageSent)
    }

    @Test
    fun flush_rowNotFoundIsDroppedWithoutAMessage() {
        PendingChanges.enqueue(context, change("a"))
        val sentMessages = mutableListOf<String>()

        val remaining = PendingChanges.flush(context, send = { SendOutcome.ROW_NOT_FOUND }, onSent = { sentMessages += it.id })

        assertEquals(0, remaining)
        assertTrue("nothing reached the Sheet, so nothing is announced", sentMessages.isEmpty())
    }

    @Test
    fun flush_networkFailureKeepsTheChangeQueued() {
        PendingChanges.enqueue(context, change("a"))
        val sentMessages = mutableListOf<String>()

        val remaining = PendingChanges.flush(context, send = { SendOutcome.RETRY }, onSent = { sentMessages += it.id })

        assertEquals(1, remaining)
        assertEquals(listOf("a"), PendingChanges.all(context).map { it.id })
        assertTrue(sentMessages.isEmpty())
    }

    @Test
    fun flush_aStuckEntryDoesNotBlockTheOthers_andEntriesGoOldestFirst() {
        PendingChanges.enqueue(context, change("new", queuedAt = 300L))
        PendingChanges.enqueue(context, change("stuck", queuedAt = 100L))
        PendingChanges.enqueue(context, change("mid", queuedAt = 200L))
        val attempted = mutableListOf<String>()

        val remaining = PendingChanges.flush(
            context,
            send = {
                attempted += it.id
                if (it.id == "stuck") SendOutcome.RETRY else SendOutcome.SUCCESS
            }
        )

        assertEquals(listOf("stuck", "mid", "new"), attempted)
        assertEquals(1, remaining)
        assertEquals(listOf("stuck"), PendingChanges.all(context).map { it.id })
    }

    @Test
    fun flush_aChangeQueuedWhileAnotherIsBeingSentIsNotLost() {
        PendingChanges.enqueue(context, change("a", queuedAt = 100L))

        PendingChanges.flush(context, send = {
            // The user refers another task while the first write is in flight.
            PendingChanges.enqueue(context, change("b", queuedAt = 200L))
            SendOutcome.SUCCESS
        })

        assertEquals(listOf("b"), PendingChanges.all(context).map { it.id })
    }

    // --- error classification ------------------------------------------------------------

    @Test
    fun isRowNotFound_recognisesTheV1ScriptsErrorTextAndTheV2Codes() {
        assertTrue(isRowNotFound(null, "No row with that task id"))
        assertTrue(isRowNotFound(null, "No row matching that description"))
        assertTrue(isRowNotFound(null, "No tab named \"Old name\""))
        assertTrue(isRowNotFound("no_such_row", "whatever"))
        assertTrue(isRowNotFound("no_such_tab", "whatever"))
        assertFalse("a real failure must be retried, not dropped", isRowNotFound(null, "Exception: Service invoked too many times"))
        assertFalse(isRowNotFound("busy", "Couldn't take the lock"))
    }

    // --- overlay onto a sync -------------------------------------------------------------

    private fun task(id: String, taskId: String? = "t-$id", referredAt: Long? = null) = ManagedTask(
        id = "external-$id", description = "Task $id", category = "Cleaning", durationMinutes = 5,
        builtIn = false, referredAt = referredAt, taskId = taskId
    )

    @Test
    fun overlay_anOfflineReferralIsNotRevertedByASyncThatReadsTheSheetWithoutIt() {
        // The Sheet (read during the sync) doesn't know about the referral yet - it's still queued.
        val fromSheet = listOf(task("a"), task("b"))
        val afterReferralState = refreshReferralState(fromSheet, referredRowKeys = emptySet(), now = 50L)
        val pending = listOf(change("q", taskId = "t-a", queuedAt = 42L))

        val result = applyPendingOverlay(afterReferralState, pending).associateBy { it.taskId }

        assertEquals("still referred, from the moment the user referred it", 42L, result.getValue("t-a").referredAt)
        assertNull("an unrelated task is untouched", result.getValue("t-b").referredAt)
    }

    @Test
    fun overlay_doesNothingOnceTheChangeHasReachedTheSheet() {
        // Queue empty (the write succeeded) and the Sheet says it isn't referred: the Sheet wins.
        val result = applyPendingOverlay(listOf(task("a")), emptyList())

        assertNull(result.single().referredAt)
    }

    @Test
    fun overlay_matchesByCategoryAndDescriptionWhenEitherSideHasNoTaskId() {
        val pending = listOf(change("q", taskId = null, category = "Cleaning", description = "Task a", queuedAt = 7L))

        val result = applyPendingOverlay(listOf(task("a", taskId = "t-a")), pending)

        assertEquals(7L, result.single().referredAt)
    }

    @Test
    fun overlay_keepsAnExistingReferredAt() {
        val result = applyPendingOverlay(listOf(task("a", referredAt = 3L)), listOf(change("q", taskId = "t-a", queuedAt = 42L)))

        assertEquals(3L, result.single().referredAt)
    }
}

// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-24, after version v0.2.0-82 synchronization-improvements 2026-09-25
package com.microtasking.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Save Settings does about the Sheet connection (SPEC.md "Synchronization" > "Settings
 * behavior"): a changed connection syncs, a different Sheet discards the old one's local state
 * first, and everything else - including a typo - leaves the task pool alone.
 */
class SettingsSaveTest {
    private val sheetA = "https://docs.google.com/spreadsheets/d/AAAAAAAAAAAAAAAAAAAA/edit#gid=0"
    private val sheetB = "https://docs.google.com/spreadsheets/d/BBBBBBBBBBBBBBBBBBBB"
    private val webAppOne = "https://script.google.com/macros/s/one/exec"
    private val webAppTwo = "https://script.google.com/macros/s/two/exec"

    @Test
    fun nothingChanged_doesNotSyncAndDoesNotSwitch() {
        val decision = decideSettingsSave(sheetA, sheetA, webAppOne, webAppOne)

        assertFalse(decision.shouldSync)
        assertFalse(decision.sheetSwitched)
    }

    @Test
    fun aDifferentSheet_switchesAndSyncs() {
        val decision = decideSettingsSave(sheetA, sheetB, webAppOne, webAppOne)

        assertTrue(decision.sheetSwitched)
        assertTrue(decision.shouldSync)
    }

    @Test
    fun theSameSheetWrittenAnotherWay_syncsButIsNotASwitch() {
        // A QR code carries the bare /d/<id> form; the user had pasted the /edit#gid= form.
        val decision = decideSettingsSave(
            sheetA, "https://docs.google.com/spreadsheets/d/AAAAAAAAAAAAAAAAAAAA", webAppOne, webAppOne
        )

        assertFalse("same spreadsheet id: keep everything local", decision.sheetSwitched)
        assertTrue(decision.shouldSync)
    }

    @Test
    fun aChangedWebAppUrlAloneSyncsWithoutSwitching() {
        val decision = decideSettingsSave(sheetA, sheetA, webAppOne, webAppTwo)

        assertTrue("a redeployed script (same Sheet) resyncs", decision.shouldSync)
        assertFalse(decision.sheetSwitched)
    }

    @Test
    fun aBlankNewSheetUrl_neverSyncsAndNeverThrowsAwayTheTaskPool() {
        val decision = decideSettingsSave(sheetA, "", webAppOne, webAppOne)

        assertFalse(decision.shouldSync)
        assertFalse(decision.sheetSwitched)
    }

    @Test
    fun anUnparseableNewSheetUrl_syncsToShowTheErrorButDoesNotDiscardAnything() {
        val decision = decideSettingsSave(sheetA, "not a url", webAppOne, webAppOne)

        assertTrue("the sync will fail and say so", decision.shouldSync)
        assertFalse("a typo must not delete the task pool", decision.sheetSwitched)
    }

    @Test
    fun firstTimeSetup_syncsWithNothingToDiscard() {
        val decision = decideSettingsSave("", sheetA, "", webAppOne)

        assertTrue(decision.shouldSync)
        assertFalse(decision.sheetSwitched)
    }

    @Test
    fun whitespaceAroundAnUnchangedUrlIsNotAChange() {
        val decision = decideSettingsSave(sheetA, "  $sheetA  ", webAppOne, "  $webAppOne ")

        assertFalse(decision.shouldSync)
    }
}

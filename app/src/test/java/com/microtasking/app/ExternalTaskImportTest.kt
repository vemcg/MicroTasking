// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTaskImportTest {
    @Test
    fun normalizeGoogleSheetCsvUrl_convertsEditLinkToExportUrl() {
        val input = "https://docs.google.com/spreadsheets/d/abc123/edit?usp=sharing#gid=456"
        val result = normalizeGoogleSheetCsvUrl(input)

        assertEquals("https://docs.google.com/spreadsheets/d/abc123/export?format=csv&gid=456", result)
    }

    @Test
    fun splitCsvLine_keepsCommasInsideQuotedFields() {
        assertEquals(
            listOf("TRUE", "Sort the mail into keep, recycle, shred", "https://x.test"),
            splitCsvLine(""" TRUE ,"Sort the mail into keep, recycle, shred", https://x.test """.trim())
        )
    }

    @Test
    fun splitCsvLine_unescapesDoubledQuotes() {
        assertEquals(
            listOf("FALSE", """Read "Atomic Habits", chapter 1""", ""),
            splitCsvLine(""" FALSE,"Read ""Atomic Habits"", chapter 1", """.trim())
        )
    }

    @Test
    fun parseExternalTaskCsv_columnAIsTheEnabledToggleWithNoHeaderText() {
        // Row 1: A1 is the master checkbox (exports as TRUE), B1/C1 are the real headers.
        val csv = """
            TRUE,Description,Link
            TRUE,Wash the dishes,https://example.com/dishes
            FALSE,Take out the trash,
            TRUE,Reply to one email,
        """.trimIndent()

        val tasks = parseExternalTaskCsv(csv, "Imported")

        assertEquals(3, tasks.size)
        assertEquals(listOf("Wash the dishes", "Take out the trash", "Reply to one email"), tasks.map { it.description })
        assertEquals(listOf(true, false, true), tasks.map { it.enabled })
        assertEquals("Imported", tasks[0].category)
    }

    @Test
    fun parseExternalTaskCsv_blankColumnAIsDisabledWhenTheTabUsesCheckboxes() {
        val csv = """
            TRUE,Description,Link
            TRUE,Checked task,
            ,Task whose checkbox was removed,
        """.trimIndent()

        val tasks = parseExternalTaskCsv(csv, "Imported")

        assertEquals(2, tasks.size)
        assertTrue(tasks[0].enabled)
        assertFalse(tasks[1].enabled)
    }

    @Test
    fun parseExternalTaskCsv_tabWithNoCheckboxesImportsEverythingEnabled() {
        // An older sheet from before column A held checkboxes: no TRUE/FALSE anywhere in column A.
        val csv = """
            ,Description,Link
            ,First task,
            ,Second task,
        """.trimIndent()

        val tasks = parseExternalTaskCsv(csv, "Legacy")

        assertEquals(2, tasks.size)
        assertTrue(tasks.all { it.enabled })
    }

    @Test
    fun parseExternalTaskCsv_taskIdIsStableAcrossImports() {
        val csv = """
            TRUE,Description,Link
            TRUE,Wipe the counters,
        """.trimIndent()

        val first = parseExternalTaskCsv(csv, "Cleaning").single()
        val second = parseExternalTaskCsv(csv, "Cleaning").single()

        assertEquals(first.id, second.id)
        assertEquals("external-Cleaning-Wipe the counters", first.id)
    }

    // DEV (sheet-surrogate-keys): a "Task ID" column, matched by header text like Description/Link.

    @Test
    fun parseExternalTaskCsv_readsTaskIdColumnAndBuildsIdFromIt() {
        val csv = """
            TRUE,Description,Link,Task ID
            TRUE,Wipe the counters,,uuid-123
        """.trimIndent()

        val task = parseExternalTaskCsv(csv, "Cleaning").single()

        assertEquals("uuid-123", task.taskId)
        assertEquals("external-uuid-123", task.id)
    }

    @Test
    fun parseExternalTaskCsv_noTaskIdColumnFallsBackToLegacyTextId() {
        val csv = """
            TRUE,Description,Link
            TRUE,Wipe the counters,
        """.trimIndent()

        val task = parseExternalTaskCsv(csv, "Cleaning").single()

        assertEquals(null, task.taskId)
        assertEquals("external-Cleaning-Wipe the counters", task.id)
    }

    @Test
    fun parseExternalTaskCsv_idSurvivesADescriptionRenameWhenTaskIdIsPresent() {
        val before = parseExternalTaskCsv(
            "TRUE,Description,Link,Task ID\nTRUE,Wipe the counters,,uuid-123", "Cleaning"
        ).single()
        // Same task id, reworded description - simulates editing the Sheet row by hand.
        val after = parseExternalTaskCsv(
            "TRUE,Description,Link,Task ID\nTRUE,Wipe down the kitchen counters,,uuid-123", "Cleaning"
        ).single()

        assertEquals("the id is the same task before and after the rename", before.id, after.id)
        assertEquals("Wipe down the kitchen counters", after.description)
    }

    @Test
    fun parseExternalTaskCsv_skipsRowsWithNoDescription() {
        val csv = """
            TRUE,Description,Link
            TRUE,,https://example.com
            TRUE,Real task,
        """.trimIndent()

        assertEquals(listOf("Real task"), parseExternalTaskCsv(csv, "X").map { it.description })
    }

    // Hardening (PUNCH_LIST "Harden against user edits to the shared Sheet"): a duplicate id in
    // the sheet import - a not-yet-repaired sheet where two rows share the same description text,
    // or a taskId column briefly holding a copy/paste duplicate before the script's own dedupe
    // catches up - must never reach a screen's LazyColumn as two entries with the same key, which
    // is a hard crash (see the Task Pool screen's items(..., key = { it.id })).
    @Test
    fun mergeImportedManagedTasks_duplicateIdInTheImportCollapsesToOne() {
        val imported = listOf(
            ManagedTask("external-Cleaning-A", "A", "Cleaning", 5, false, enabled = true),
            ManagedTask("external-Cleaning-A", "A", "Cleaning", 5, false, enabled = false)
        )

        val merged = mergeImportedManagedTasks(imported, existing = emptyList())

        assertEquals("first occurrence wins, not two entries with the same id", 1, merged.size)
        assertTrue("first occurrence's fields win", merged.single().enabled)
    }

    @Test
    fun mergeImportedManagedTasks_sheetWinsEnabledButAppFlagsSurvive() {
        val existing = listOf(
            ManagedTask(
                id = "external-Cleaning-Wipe the counters",
                description = "Wipe the counters",
                category = "Cleaning",
                durationMinutes = 5,
                builtIn = false,
                enabled = true,
                temporarilyUnavailable = true,
                neverSuggest = true
            ),
            ManagedTask(
                id = "custom-1", description = "My own cleaning task", category = "Cleaning",
                durationMinutes = 10, builtIn = false
            )
        )
        val imported = listOf(
            ManagedTask(
                id = "external-Cleaning-Wipe the counters",
                description = "Wipe the counters",
                category = "Cleaning",
                durationMinutes = 5,
                builtIn = false,
                enabled = false,
                temporarilyUnavailable = false,
                neverSuggest = false
            )
        )

        val merged = mergeImportedManagedTasks(imported, existing)

        val wipe = merged.single { it.id == "external-Cleaning-Wipe the counters" }
        assertFalse("sheet checkbox is authoritative for enabled", wipe.enabled)
        assertTrue("neverSuggest set in the app survives a re-sync", wipe.neverSuggest)
        assertTrue("temporarilyUnavailable set in the app survives a re-sync", wipe.temporarilyUnavailable)
        assertTrue("a custom task in a category the sheet still has is kept", merged.any { it.id == "custom-1" })
    }

    @Test
    fun mergeImportedManagedTasks_droppedSheetRowDisappears() {
        val existing = listOf(
            ManagedTask("external-Cleaning-A", "A", "Cleaning", 5, false),
            ManagedTask("external-Cleaning-B", "B", "Cleaning", 5, false)
        )
        val imported = listOf(ManagedTask("external-Cleaning-A", "A", "Cleaning", 5, false))

        val merged = mergeImportedManagedTasks(imported, existing)

        assertEquals(listOf("external-Cleaning-A"), merged.map { it.id })
    }

    @Test
    fun mergeImportedManagedTasks_removedTabTakesItsWholeCategory() {
        val existing = listOf(
            ManagedTask("external-Errands-Old", "Old errand", "Errands", 5, false),
            ManagedTask("seed-errands-0", "Built-in errand", "Errands", 5, true),
            ManagedTask("custom-1", "Hand-added errand", "Errands", 10, false),
            ManagedTask("custom-2", "Hand-added cleaning task", "Cleaning", 10, false)
        )
        // Re-sync of a sheet that no longer has an "Errands" tab at all.
        val imported = listOf(ManagedTask("external-Cleaning-A", "A", "Cleaning", 5, false))

        val merged = mergeImportedManagedTasks(imported, existing)

        // "Errands" is gone entirely - external, built-in, and custom. A custom task in a
        // category the sheet still has ("Cleaning") stays.
        assertEquals(listOf("external-Cleaning-A", "custom-2"), merged.map { it.id })
    }

    @Test
    fun mergeImportedManagedTasks_authoritativeCategoriesFromTabNamesNotTaskPresence() {
        val existing = listOf(
            ManagedTask("custom-keep", "In a live but empty tab", "Health", 5, false),
            ManagedTask("custom-drop", "In a tab the sheet doesn't have", "Must Do", 5, false),
            ManagedTask("seed-health-0", "Built-in health", "Health", 5, true)
        )
        // The sheet has a Cleaning tab (with a row) and a Health tab (currently empty).
        val imported = listOf(ManagedTask("external-Cleaning-A", "A", "Cleaning", 5, false))
        val tabNames = setOf("Cleaning", "Health")

        val merged = mergeImportedManagedTasks(imported, existing, tabNames)

        // custom-keep survives (Health is a real tab even with no rows); custom-drop and the
        // built-in both go (no tab, or not custom).
        assertEquals(listOf("external-Cleaning-A", "custom-keep"), merged.map { it.id })
    }
}

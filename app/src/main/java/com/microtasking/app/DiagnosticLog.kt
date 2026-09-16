// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import android.content.Context
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Plain-text operational log of normal (non-crash) app activity - task lifecycle, app/permission
 * lifecycle, and settings changes - so a USB-tethered pull can explain behavior (e.g. "why did two
 * tasks show up at once", DEFECTS.md item 3) without a crash having happened. Distinct from crash
 * reporting (PUNCH_LIST.md item 7, not yet built). See SPEC.md "Diagnostics".
 *
 * Retrieve with: adb exec-out run-as com.microtasking.app cat files/diagnostic.log
 */
object DiagnosticLog {
    private const val FILE_NAME = "diagnostic.log"
    private const val MAX_BYTES = 2L * 1024 * 1024

    // Trimming re-reads and re-writes the whole file, so it only runs once the file overshoots
    // the cap by a margin rather than on every single write - keeps the common-case write cheap.
    private const val TRIM_TRIGGER_BYTES = MAX_BYTES + 256 * 1024
    private val RETENTION = Duration.ofHours(48)
    private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Synchronized
    fun log(context: Context, event: String, details: String = "") {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val line = buildString {
                append(LocalDateTime.now().format(TIMESTAMP_FORMAT))
                append("  ")
                append(event)
                if (details.isNotBlank()) {
                    append("  ")
                    append(details)
                }
            }
            file.appendText(line + "\n")
            if (file.length() > TRIM_TRIGGER_BYTES) trim(file)
        }
    }

    // The file is append-ordered (each event's timestamp >= the previous one's), so dropping the
    // oldest entries is a plain scan from the front - never a sort.
    private fun trim(file: File) {
        val cutoff = LocalDateTime.now().minus(RETENTION)
        var lines = file.readLines().dropWhile { line ->
            val timestamp = runCatching { LocalDateTime.parse(line.take(19), TIMESTAMP_FORMAT) }.getOrNull()
            timestamp != null && timestamp.isBefore(cutoff)
        }
        // Backstop independent of the time window: a future logging-loop bug could otherwise grow
        // the file unbounded within a single 48h span before the trim above ever catches up.
        var totalBytes = lines.sumOf { it.toByteArray().size + 1L }
        while (totalBytes > MAX_BYTES && lines.size > 1) {
            totalBytes -= lines.first().toByteArray().size + 1L
            lines = lines.drop(1)
        }
        file.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
    }
}

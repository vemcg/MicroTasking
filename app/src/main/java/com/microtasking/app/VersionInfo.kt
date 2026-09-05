// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

/**
 * Parses and compares app versions component-by-component instead of relying on
 * lexical string comparison (which would treat "10" as less than "2").
 *
 * Expected short form: "<major>.<minor>.<patch>-<buildNumber>", e.g. "0.1.7-11".
 */
data class VersionInfo(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val buildNumber: Int
) : Comparable<VersionInfo> {

    override fun compareTo(other: VersionInfo): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        return buildNumber.compareTo(other.buildNumber)
    }

    companion object {
        /** Parses "0.1.7-11" style strings, tolerating a leading "v" and extra "-timestamp-sha" suffix. */
        fun parse(rawVersion: String): VersionInfo? {
            val stripped = rawVersion.removePrefix("v")
            val parts = stripped.split("-")
            if (parts.isEmpty()) return null
            val basePart = parts[0]
            val buildPart = parts.getOrNull(1)?.toIntOrNull() ?: 0

            val baseComponents = basePart.split(".")
            if (baseComponents.size != 3) return null
            val major = baseComponents[0].toIntOrNull() ?: return null
            val minor = baseComponents[1].toIntOrNull() ?: return null
            val patch = baseComponents[2].toIntOrNull() ?: return null

            return VersionInfo(major, minor, patch, buildPart)
        }
    }
}

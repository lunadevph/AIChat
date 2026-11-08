package com.android5.ai

import java.io.File

/** Bounded, synchronous writes; callers use a background collector except for fatal crash capture. */
internal class RotatingAppLog(private val directory: File, private val session: String,
    private val maxFileBytes: Int = 1_048_576, private val retainedFiles: Int = 8) {
    private var current: File? = null
    private var sequence = 0
    init { require(session.matches(Regex("[A-Za-z0-9_-]+")) && maxFileBytes >= 128 && retainedFiles > 0) }

    @Synchronized fun append(raw: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create log directory" }
        val sanitized = AppLogRedactor.redact(raw)
        val limit = minOf(maxFileBytes, 65_536)
        val normal = (sanitized + "\n").toByteArray(Charsets.UTF_8)
        val bytes = if (normal.size <= limit) normal else {
            val suffix = " [truncated]\n"
            (prefix(sanitized, limit - suffix.toByteArray().size) + suffix).toByteArray(Charsets.UTF_8)
        }
        var destination = current
        if (destination == null || destination.length() + bytes.size > maxFileBytes) {
            destination = File(directory, "app-$session-${(sequence++).toString().padStart(6, '0')}.log")
            current = destination
        }
        // The append stream is closed for every record so a process kill does not lose buffered lines.
        java.io.FileOutputStream(destination, true).use { it.write(bytes) }
        files().filterNot { it == destination }.dropLast((retainedFiles - 1).coerceAtLeast(0)).forEach { it.delete() }
    }

    @Synchronized fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("app-") && it.extension == "log" && it.canonicalFile.parentFile == directory.canonicalFile }
        .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })

    @Synchronized fun clear(): Boolean {
        current = null
        return files().map { it.delete() }.all { it }
    }

    private fun prefix(text: String, maxBytes: Int): String {
        var low = 0; var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (text.substring(0, mid).toByteArray(Charsets.UTF_8).size <= maxBytes) low = mid else high = mid - 1
        }
        if (low > 0 && text[low - 1].isHighSurrogate()) low--
        return text.substring(0, low)
    }
}

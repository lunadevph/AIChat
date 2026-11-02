package com.android5.ai

import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Commands use the app's Android permissions. The working directory is not a sandbox. */
internal object ShellRunner {
    data class Result(val exitCode: Int?, val output: String, val timedOut: Boolean, val truncated: Boolean)

    suspend fun run(
        command: String,
        directory: File,
        timeoutMillis: Long = 30_000,
        maxBytes: Int = 12_000,
        shell: List<String> = listOf("/system/bin/sh", "-c")
    ): Result = withContext(Dispatchers.IO) {
        require(command.isNotBlank() && command.length <= 4_000)
        require(timeoutMillis > 0 && maxBytes > 0 && directory.isDirectory)
        val process = ProcessBuilder(shell + command).directory(directory).redirectErrorStream(true).start()
        val input = process.inputStream
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val started = System.nanoTime()
        var truncated = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                    return@withContext Result(null, output.toString("UTF-8"), true, truncated)
                }
                val available = input.available()
                if (available > 0) {
                    val count = input.read(buffer, 0, minOf(available, buffer.size))
                    if (count > 0) {
                        val kept = minOf(count, maxBytes - output.size())
                        if (kept > 0) output.write(buffer, 0, kept)
                        if (kept < count) truncated = true
                    }
                } else {
                    val exit = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (exit != null) return@withContext Result(exit, output.toString("UTF-8"), false, truncated)
                    delay(10)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        } finally {
            runCatching { process.destroy() }
            runCatching { input.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}

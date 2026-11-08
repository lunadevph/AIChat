package com.android5.ai

import android.content.Context
import android.net.Uri
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Automatic app-process Logcat capture in debug/debug-channel builds. No READ_LOGS permission. */
internal object AppLogger {
    const val DIRECTORY = "app_logs"
    private val started = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var sink: RotatingAppLog? = null
    @Volatile private var capturing = false
    @Volatile private var lastError: String? = null

    fun start(context: Context) {
        if (!BuildConfig.DEBUG || !started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Thread({ collect() }, "AppLogger").apply { isDaemon = true; start() }
    }

    private fun writer(): RotatingAppLog = synchronized(lock) {
        sink ?: RotatingAppLog(File(requireNotNull(appContext).filesDir, DIRECTORY),
            "${System.currentTimeMillis()}-${Process.myPid()}").also { sink = it }
    }

    private fun collect() {
        var pidOption = true
        var attempt = 0
        while (!Thread.currentThread().isInterrupted) {
            var process: java.lang.Process? = null
            try {
                val output = writer()
                output.append("APPLOGGER start version=${BuildConfig.VERSION_NAME} build=${BuildConfig.BUILD_NUMBER} pid=${Process.myPid()}")
                val command = mutableListOf("/system/bin/logcat", "-b", "all", "-v", "threadtime")
                if (pidOption) command += "--pid=${Process.myPid()}"
                if (attempt > 0) command += listOf("-T", "1")
                command += "*:V"
                process = ProcessBuilder(command).redirectErrorStream(true).start()
                capturing = true; lastError = null
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (pidOption && (line.contains("unrecognized option", true) || line.contains("unknown option", true))) pidOption = false
                        else if (pidOption || ownsLine(line, Process.myPid())) output.append(line)
                    }
                }
                lastError = "logcat stopped (exit ${process.waitFor()})"
            } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return }
            catch (e: Throwable) {
                // A diagnostic failure must never take the app down or recursively log itself.
                lastError = AppLogRedactor.redact("${e.javaClass.simpleName}: ${e.message.orEmpty()}").take(300)
            } finally { capturing = false; process?.destroy() }
            attempt++
            try { Thread.sleep(if (attempt <= 3) 2_000 else 30_000) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
    }

    internal fun ownsLine(line: String, pid: Int): Boolean =
        line.trim().split(Regex("\\s+"), limit = 6).let { it.size >= 5 && it[2].toIntOrNull() == pid }

    fun recordCrash(thread: Thread, throwable: Throwable) {
        if (!BuildConfig.DEBUG || appContext == null) return
        runCatching { writer().append("APPLOGGER FATAL thread=${thread.name}\n${throwable.stackTraceToString()}") }
    }

    data class Stats(val path: String, val capturing: Boolean, val files: Int, val bytes: Long, val error: String?)
    fun stats(context: Context): Stats {
        val root = File(context.filesDir, DIRECTORY)
        val files = if (BuildConfig.DEBUG) runCatching { writer().files() }.getOrElse {
            lastError = AppLogRedactor.redact("${it.javaClass.simpleName}: ${it.message.orEmpty()}").take(300)
            emptyList()
        } else emptyList()
        return Stats(root.absolutePath, capturing, files.size, files.sumOf { it.length() }, lastError)
    }

    fun clear(): Boolean = BuildConfig.DEBUG && runCatching { writer().clear() }.getOrDefault(false)

    fun export(context: Context, uri: Uri): Boolean {
        if (!BuildConfig.DEBUG) return false
        return runCatching {
            val log = writer()
            synchronized(log) {
                val files = log.files()
                if (files.isEmpty()) return false
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: return false
                ZipOutputStream(output).use { zip ->
                    files.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { input ->
                            // Copy a bounded snapshot, even while another app process appends logs.
                            var remaining = file.length().coerceAtMost(1_048_576L)
                            val buffer = ByteArray(8192)
                            while (remaining > 0) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) break
                                zip.write(buffer, 0, count); remaining -= count
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            true
        }.getOrDefault(false)
    }
}

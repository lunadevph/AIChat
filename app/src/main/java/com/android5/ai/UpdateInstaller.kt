package com.android5.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Turns "a new version exists" into an installed one, without leaving the app.
 *
 * The release APK is streamed into the cache, then handed to the system package
 * installer through a [FileProvider] URI — a normal app cannot install a package
 * itself, so the installer's own confirmation screen is the last step. Android 8
 * and up also needs the user to allow this app to install packages, which lives on
 * a system screen; [canInstall] reports that state and [unknownSourcesIntent]
 * opens it.
 */
object UpdateInstaller {

    private const val DIR = "updates"

    /** Where the APK for [versionName] is (or would be) staged. */
    fun apkFile(context: Context, versionName: String?): File {
        val safe = versionName
            ?.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            ?.takeIf { it.isNotBlank() }
            ?: "update"
        return File(File(context.cacheDir, DIR), "ai-chat-$safe.apk")
    }

    /** An already-downloaded APK for this version, if the cache still has it. */
    fun cachedApk(context: Context, versionName: String?): File? =
        apkFile(context, versionName).takeIf { it.isFile && it.length() > 0 }

    /** Whether the system will let us open the installer for a downloaded APK. */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * Streams [url] into [destination], reporting `(bytes, total)` as it goes so
     * the screen can show real progress. Throws with a readable message on
     * failure, and never leaves a half-written file behind.
     */
    suspend fun download(
        context: Context,
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AIChat-Android")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("the server answered HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("the server sent no data")
            val total = body.contentLength()

            destination.parentFile?.mkdirs()
            val partial = File(destination.parentFile, destination.name + ".part")

            try {
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            written += read
                            output.write(buffer, 0, read)
                            onProgress(written, total)
                        }
                        output.flush()
                    }
                }
                if (partial.length() <= 0) throw IOException("the download was empty")
                if (destination.exists()) destination.delete()
                if (!partial.renameTo(destination)) {
                    throw IOException("could not finish writing the update file")
                }
            } catch (e: Exception) {
                partial.delete()
                throw e
            }

            destination
        }
    }

    /** The system installer's confirmation screen for a downloaded APK. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Drops staged APKs once the update is behind us. */
    fun clear(context: Context) {
        File(context.cacheDir, DIR).deleteRecursively()
    }
}

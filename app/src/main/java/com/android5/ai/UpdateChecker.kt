package com.android5.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Checks the update mirror in the background and surfaces a result to the user.
 *
 * Runs on every app launch without blocking the UI: the fetch happens on IO, and
 * when a newer build exists the user gets both an in-app flag (read by the chat
 * screen to show a banner) and a system notification, so they notice even if they
 * are not looking at the app at the time.
 */
object UpdateChecker {

    private const val PREFS = "update_checker"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_LATEST_CODE = "latest_code"
    private const val KEY_LATEST_NAME = "latest_name"
    private const val CHANNEL_ID = "ai_chat_updates"
    private const val NOTIF_ID = 42

    private const val MIRROR_URL = "https://raw.githubusercontent.com/LunaDevPH/update-mirror/main/"

    /** Minimum gap between checks, so launching the app ten times a minute does
     *  not hammer the mirror. */
    private const val MIN_CHECK_GAP_MS = 30 * 60 * 1000L

    fun latestAvailableCode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LATEST_CODE, 0)

    fun latestAvailableName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LATEST_NAME, null)

    /** True when the mirror's latest build is newer than the running one. */
    fun isUpdateAvailable(context: Context): Boolean {
        val latest = latestAvailableCode(context)
        return latest > 0 && latest > currentCode(context)
    }

    private fun currentCode(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (e: Exception) {
        BuildConfig.VERSION_CODE
    }

    /**
     * Kicks off a background check. Safe to call from any thread; returns
     * immediately. The check is throttled by [MIN_CHECK_GAP_MS].
     */
    fun checkInBackground(context: Context, scope: CoroutineScope) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (now - last < MIN_CHECK_GAP_MS) return

        scope.launch(Dispatchers.IO) {
            try {
                val api = Retrofit.Builder()
                    .baseUrl(MIRROR_URL)
                    .client(
                        OkHttpClient.Builder()
                            .connectTimeout(6, TimeUnit.SECONDS)
                            .callTimeout(10, TimeUnit.SECONDS)
                            .build()
                    )
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(UpdateApi::class.java)

                val config = api.getUpdateConfig("mirror.json")
                val latestCode = config.appLatestCodeVersion
                val latestName = config.appLatestVersion

                prefs.edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .putInt(KEY_LATEST_CODE, latestCode)
                    .putString(KEY_LATEST_NAME, latestName)
                    .apply()

                if (latestCode > currentCode(appCtx)) {
                    withContext(Dispatchers.Main) {
                        postNotification(appCtx, latestName ?: "newer build", latestCode)
                    }
                }
            } catch (e: Exception) {
                // Offline or mirror unreachable: leave the cached result alone.
            }
        }
    }

    private fun postNotification(context: Context, versionName: String, versionCode: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when a newer build of AI Chat is available"
            }
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, UpdateActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AI Chat update available")
            .setContentText("Version $versionName (build $versionCode) is ready to install.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID, notification)
    }

    /** Clears the cached "update available" state (e.g. after a successful install). */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LATEST_CODE)
            .remove(KEY_LATEST_NAME)
            .apply()
    }
}

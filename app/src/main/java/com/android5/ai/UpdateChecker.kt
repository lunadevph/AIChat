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

    private const val MIRROR_URL = UpdateChannels.BASE_URL

    private fun channel(context: Context) = SettingsManager(context).updateChannel
    private fun key(context: Context, value: String) = UpdateChannels.cacheKey(value, channel(context))

    /** Minimum gap between checks, so launching the app ten times a minute does
     *  not hammer the mirror. */
    private const val MIN_CHECK_GAP_MS = 30 * 60 * 1000L

    fun latestAvailableCode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(key(context, KEY_LATEST_CODE), 0)

    fun latestAvailableName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(context, KEY_LATEST_NAME), null)

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
        val selectedChannel = channel(appCtx)
        val lastKey = UpdateChannels.cacheKey(KEY_LAST_CHECK, selectedChannel)
        val codeKey = UpdateChannels.cacheKey(KEY_LATEST_CODE, selectedChannel)
        val nameKey = UpdateChannels.cacheKey(KEY_LATEST_NAME, selectedChannel)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(lastKey, 0L)
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

                val config = api.getUpdateConfig(UpdateChannels.feed(selectedChannel))
                if (!UpdateChannels.accepts(config, selectedChannel, appCtx.packageName)) return@launch
                val latestCode = config.appLatestCodeVersion
                val latestName = config.appLatestVersion

                prefs.edit()
                    .putLong(lastKey, System.currentTimeMillis())
                    .putInt(codeKey, latestCode)
                    .putString(nameKey, latestName)
                    .apply()

                if (latestCode > currentCode(appCtx) && channel(appCtx) == selectedChannel) {
                    withContext(Dispatchers.Main) {
                        postNotification(appCtx, latestName ?: "newer build", latestCode)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.update_notification_description)
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
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, versionName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID, notification)
    }

    fun remember(context: Context, config: AppUpdateConfig, selectedChannel: String) {
        if (!UpdateChannels.accepts(config, selectedChannel, context.packageName)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(UpdateChannels.cacheKey(KEY_LAST_CHECK, selectedChannel), System.currentTimeMillis())
            .putInt(UpdateChannels.cacheKey(KEY_LATEST_CODE, selectedChannel), config.appLatestCodeVersion)
            .putString(UpdateChannels.cacheKey(KEY_LATEST_NAME, selectedChannel), config.appLatestVersion).apply()
    }

    /** Clears the cached "update available" state (e.g. after a successful install). */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(key(context, KEY_LATEST_CODE))
            .remove(key(context, KEY_LATEST_NAME))
            .remove(key(context, KEY_LAST_CHECK))
            .apply()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
    }
}

package com.android5.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * The user's own storage, and the switch that lets the assistant touch it.
 *
 * Nothing here is needed for the app to work: the tools live in an app-private
 * workspace. Granting this extends their reach to the shared folders (Downloads,
 * Documents, pictures) so the assistant can read a document the user points at,
 * search their files, or save something where they will find it. Because the
 * shared root is `/storage/emulated/0`, the tools can be addressed with absolute
 * paths such as `/storage/emulated/0/Download/report.pdf`. The permission is the
 * system's own "All files access" on Android 11+, which is why granting it means
 * leaving the app for a system screen.
 */
object DeviceStorage {

    /** The shared storage root on a normal device: `/storage/emulated/0`. */
    fun root(): File = Environment.getExternalStorageDirectory()

    /** The shared storage root, or empty when access has not been granted. */
    fun roots(context: Context): List<File> =
        if (granted(context)) listOf(root()) else emptyList()

    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** True when granting it still needs a runtime dialog rather than a settings screen. */
    fun needsRuntimeRequest(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /** Opens the system screen where the user flips the switch. */
    fun settingsIntent(context: Context): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }
    }
}

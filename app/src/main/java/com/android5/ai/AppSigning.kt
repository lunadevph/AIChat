package com.android5.ai

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * The app's signing identity, and what it implies about the build.
 *
 * The official release is signed with the LunaDevPH upload key. A build signed
 * with anything else — a debug build, a fork, a repack — is reported as
 * unofficial, so a user can tell whether the APK they are running is the one
 * the project actually published.
 */
object AppSigning {

    /** SHA-256 of the LunaDevPH release signing certificate (no colons, lower case). */
    const val OFFICIAL_CERT_SHA256 =
        "91e80ed52d4ae0ccea71ed52f5ffa18f3df7d71a2e62ad99dfe431fd82663902"

    fun signingCertSha256(context: Context): String? {
        return try {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val sigs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            val sig = sigs?.firstOrNull() ?: return null
            MessageDigest.getInstance("SHA-256")
                .digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun isOfficial(context: Context): Boolean {
        // A debug build is always the developer's own build, even though it is
        // signed with the debug keystore rather than the release key.
        if (BuildConfig.DEBUG) return true
        val hash = signingCertSha256(context) ?: return false
        return hash.equals(OFFICIAL_CERT_SHA256, ignoreCase = true)
    }

    /** Short, displayable fingerprint. */
    fun shortFingerprint(context: Context): String {
        val h = signingCertSha256(context) ?: return "unknown"
        return h.take(12) + "…" + h.takeLast(6)
    }
}

package com.android5.ai

import com.google.gson.annotations.SerializedName

data class AppUpdateConfig(
    val appPackageName: String? = null,
    @SerializedName("appLatestVersion") val appLatestVersion: String? = null,
    @SerializedName("appLatestCodeVersion") val appLatestCodeVersion: Int = 0,
    val googleIcon: String? = null,
    val openaiIcon: String? = null,
    val openrouterIcon: String? = null,
    val groqIcon: String? = null,
    val updateStatus: String? = null,
    val updateRequired: Boolean = false,
    val updateLinkAvailable: Boolean = false,
    val downloadUrl: String? = null,
    val changelog: String? = null,
    /**
     * Mirror format version. 0 when the mirror does not say, which is what a v1
     * mirror does; every v2 field below is optional so a v1 file still parses.
     */
    val schemaVersion: Int = 0,
    /** Provider mark for NVIDIA NIM, alongside the other provider icons. */
    val nvidiaIcon: String? = null,
    /** Oldest build the mirror still supports; 0 means "no minimum". */
    val minVersionCode: Int = 0,
    /** UTC ISO 8601 time the mirror was written. */
    val releasedAt: String? = null,
    /** Exact size of the APK at [downloadUrl], or 0 when unknown. */
    val apkSizeBytes: Long = 0L,
    /** Lower-case hex SHA-256 of the APK at [downloadUrl], or null when unknown. */
    val apkSha256: String? = null
)

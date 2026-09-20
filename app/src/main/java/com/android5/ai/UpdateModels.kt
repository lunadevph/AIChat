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
    val downloadUrl: String? = null
)

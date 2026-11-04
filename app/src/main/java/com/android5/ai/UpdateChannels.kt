package com.android5.ai

object UpdateChannels {
    const val RELEASE = "release"
    const val DEBUG = "debug"
    const val BASE_URL = "https://raw.githubusercontent.com/lunadevph/update-mirror/main/"
    val all = listOf(RELEASE, DEBUG)

    fun normalize(value: String): String = if (value == DEBUG) DEBUG else RELEASE
    fun feed(value: String): String = if (normalize(value) == DEBUG) "debug/mirror.json" else "mirror.json"
    fun cacheKey(key: String, channel: String): String = if (normalize(channel) == RELEASE) key else "${key}_debug"

    fun accepts(config: AppUpdateConfig, channel: String, packageName: String): Boolean =
        (config.appPackageName == null || config.appPackageName == packageName) &&
            ((config.channel ?: RELEASE) == normalize(channel))
}

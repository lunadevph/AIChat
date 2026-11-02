package com.android5.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Read the device clock at use time so resumed chats never reuse an old date. */
internal object AppClock {
    fun utcTimestamp(now: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)

    fun context(now: Date = Date(), zone: TimeZone = TimeZone.getDefault()): String {
        val local = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = zone
        }.format(now)
        return "Current device date and time: $local (${zone.id}). UTC: ${utcTimestamp(now)}. " +
            "Use this date for today and relative dates; dates in older messages or memory may be stale. " +
            "Use the time tool if you need a refreshed timestamp."
    }
}

package com.android5.ai

import java.util.Date
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class AppClockTest {
    @Test fun localDateUsesTheUsersTimezoneAcrossMidnight() {
        val context = AppClock.context(Date(16 * 60 * 60 * 1000L), TimeZone.getTimeZone("Asia/Taipei"))
        assertTrue(context.contains("1970-01-02T00:00:00+08:00 (Asia/Taipei)"))
        assertTrue(context.contains("UTC: 1970-01-01T16:00:00Z"))
    }

    @Test fun timestampMarkedUtcDoesNotDependOnTheDeviceTimezone() {
        assertEquals("1970-01-01T00:00:00Z", AppClock.utcTimestamp(Date(0)))
    }

    @Test fun eachRequestCanRefreshItsDate() {
        val zone = TimeZone.getTimeZone("UTC")
        assertNotEquals(AppClock.context(Date(0), zone), AppClock.context(Date(86_400_000), zone))
    }
}

package com.android5.ai

import org.junit.Assert.*
import org.junit.Test

class UpdateChannelsTest {
    @Test fun legacyFeedBelongsOnlyToRelease() {
        val legacy = AppUpdateConfig(appPackageName = "com.android5.ai")
        assertTrue(UpdateChannels.accepts(legacy, "release", "com.android5.ai"))
        assertFalse(UpdateChannels.accepts(legacy, "debug", "com.android5.ai"))
    }

    @Test fun channelsRejectEachOthersMetadataAndWrongPackage() {
        val debug = AppUpdateConfig(appPackageName = "com.android5.ai", channel = "debug")
        assertTrue(UpdateChannels.accepts(debug, "debug", "com.android5.ai"))
        assertFalse(UpdateChannels.accepts(debug, "release", "com.android5.ai"))
        assertFalse(UpdateChannels.accepts(debug, "debug", "another.app"))
        assertNotEquals(UpdateChannels.feed("release"), UpdateChannels.feed("debug"))
        assertNotEquals(UpdateChannels.cacheKey("latest_code", "release"), UpdateChannels.cacheKey("latest_code", "debug"))
    }
}

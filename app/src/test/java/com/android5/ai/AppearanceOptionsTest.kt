package com.android5.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceOptionsTest {
    @Test fun retiredThemesMigrateToSupportedAppearances() {
        assertEquals("forest", AppearanceOptions.theme("old_android"))
        assertEquals("ocean", AppearanceOptions.theme("holo"))
        assertEquals("dynamic", AppearanceOptions.theme("compact"))
    }

    @Test fun unsupportedBackupValuesHaveUsableDefaults() {
        assertEquals("dynamic", AppearanceOptions.theme("unknown"))
        assertEquals("tonal", AppearanceOptions.bubble("unknown"))
    }
}

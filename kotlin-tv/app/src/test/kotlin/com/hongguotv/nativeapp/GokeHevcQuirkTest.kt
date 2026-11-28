// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GokeHevcQuirkTest {
    @Test
    fun androidNineWithMisreportedHevcRecoversLandscapeAndPortrait1080p() {
        val limit = GokeHevcQuirk.fromReportedSizes(28, 288, 288, 4096, 2304)
        assertEquals(GokeHevcQuirk(1920, 1088), limit)
        checkNotNull(limit)
        assertTrue(limit.supports(1920, 1080))
        assertTrue(limit.supports(1080, 1920))
        assertFalse(limit.supports(3840, 2160))
        assertFalse(limit.supports(0, 1080))
    }

    @Test
    fun normalCapabilitiesAndOtherAndroidVersionsKeepTheDefaultPath() {
        assertNull(GokeHevcQuirk.fromReportedSizes(28, 1920, 1088, 4096, 2304))
        assertNull(GokeHevcQuirk.fromReportedSizes(28, 288, 288, 640, 480))
        assertNull(GokeHevcQuirk.fromReportedSizes(23, 288, 288, 4096, 2304))
        assertNull(GokeHevcQuirk.fromReportedSizes(26, 288, 288, 4096, 2304))
        assertNull(GokeHevcQuirk.fromReportedSizes(27, 288, 288, 4096, 2304))
        assertNull(GokeHevcQuirk.fromReportedSizes(29, 288, 288, 4096, 2304))
        assertNull(GokeHevcQuirk.fromReportedSizes(28, 288, 288, 4096, 500))
    }

    @Test
    fun recoveryDoesNotExceedTheAvcDimensions() {
        val limit = checkNotNull(GokeHevcQuirk.fromReportedSizes(28, 288, 288, 1280, 720))
        assertTrue(limit.supports(720, 1280))
        assertFalse(limit.supports(1080, 1920))
    }
}

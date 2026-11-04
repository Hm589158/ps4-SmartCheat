package com.ps4smartcheat.app

import com.ps4smartcheat.app.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsTest {
    @Test fun defaultsAreSafe() {
        val s = AppSettings()
        assertFalse(s.editingEnabled)
        assertFalse(s.editPolicy().allowSystemProcesses)
        assertFalse(s.connectOptions().allowNonLocalHosts)
        assertEquals(744, s.connectOptions().port)
    }

    @Test fun regionSizeLimitMapsToBytes() {
        assertEquals(0L, AppSettings().regionFilter().maxRegionBytes)
        assertEquals(512L * 1024 * 1024, AppSettings(maxRegionMiB = 512).regionFilter().maxRegionBytes)
    }

    @Test fun scanOptionsAreClamped() {
        val o = AppSettings(chunkKb = 1_000_000, maxResults = 1, unalignedScan = true).scanOptions()
        assertEquals(4096 * 1024, o.chunkSize)
        assertEquals(1_000, o.maxResults)
        assertEquals(1, o.alignment)
    }
}

package com.ps4smartcheat.app

import com.ps4smartcheat.app.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test fun defaults() {
        val s = AppSettings()
        // Editing is on by default (owner's choice); each write is still confirmed and verified.
        assertTrue(s.editingEnabled && s.allowNonWritableRegions && s.editPolicy().allowSystemProcesses)
        // The network stays local-only by default.
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

package com.ps4smartcheat.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ps4FirmwareTest {
    @Test fun theListMatchesPs4debugNg() {
        // ps4debug-NG README (8be828f): 39 versions across 5.05-14.02.
        assertEquals(39, Ps4Firmware.SUPPORTED_BY_PS4DEBUG_NG.size)
        assertEquals(Ps4Firmware.SUPPORTED_BY_PS4DEBUG_NG.sorted(), Ps4Firmware.SUPPORTED_BY_PS4DEBUG_NG)
        assertEquals("5.05–14.02", Ps4Firmware.range)
        listOf(505, 672, 900, 1100, 1202, 1352, 1402).forEach { assertTrue(Ps4Firmware.isSupported(it)) }
        listOf(500, 650, 1101, 1500).forEach { assertFalse(Ps4Firmware.isSupported(it)) }
    }

    @Test fun notesForTheConnectionScreen() {
        assertEquals("11.00", Ps4Firmware.format(1100))
        assertEquals("5.05", Ps4Firmware.format(505))
        assertEquals("supported by ps4debug-NG", Ps4Firmware.note(900))
        assertTrue(Ps4Firmware.note(1500)!!.startsWith("not in ps4debug-NG's list"))
        assertNull(Ps4Firmware.note(null))
    }
}

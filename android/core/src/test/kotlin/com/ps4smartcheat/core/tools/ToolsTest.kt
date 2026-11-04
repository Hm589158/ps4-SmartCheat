package com.ps4smartcheat.core.tools

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ResultsBuilderAccess
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {
    @Test fun hexRowsMarkChangesAndUnmapped() {
        val a = MemoryWindow(0x1000, ByteArray(32) { it.toByte() }, BooleanArray(32) { it < 24 })
        val bBytes = a.bytes.copyOf().also { it[3] = 99; it[20] = 77; it[30] = 5 }
        val b = MemoryWindow(0x1000, bBytes, a.mapped.copyOf())
        val rows = HexDump.rows(b, a)
        assertEquals(2, rows.size)
        assertEquals(0x1010L, rows[1].address)
        assertTrue(rows[0].changed[3])
        assertTrue(rows[1].changed[4]) // 0x1014
        assertFalse(rows[1].changed[14]) // unmapped byte never "changed"
        assertEquals("??", rows[1].hex(14))
        assertEquals("63", rows[0].hex(3))
        assertEquals(2, HexDump.changedCount(rows))
        assertEquals(0x1230L, HexDump.alignDown(0x123F))
        assertEquals('A', HexDump.printable(0x41))
        assertEquals('.', HexDump.printable(0x01))
    }

    @Test fun viewerNeverReadsUnmappedMemory() = runBlocking {
        MockPs4DebugServer().use { server ->
            val p = server.addProcess(MockPs4DebugServer.Process(3, "eboot.bin"))
            val r = p.region("d", 0x10_0000, 0x100, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_W)
            r.data.fill(0x11)
            val client = Ps4DebugClient()
            client.connect(ConnectOptions("127.0.0.1", server.port, firmwareProbeTimeoutMs = 300))
            val regions = client.getMemoryRegions(3)
            val before = server.readRequests.get()
            val w = MemoryViewer(client).read(3, 0x10_0000 - 0x40, 0x80, regions)
            assertEquals(1, server.readRequests.get() - before)
            assertFalse(w.mapped[0x3F])
            assertTrue(w.mapped[0x40])
            assertEquals(0x11, w.bytes[0x40].toInt())
            // Entirely unmapped window: no request at all.
            MemoryViewer(client).read(3, 0x50_0000, 0x40, regions)
            assertEquals(1, server.readRequests.get() - before)
            client.disconnect()
        }
    }

    @Test fun csvExport() {
        val res = ResultsBuilderAccess.build(ValueType.INT32, listOf(0x500120L to 5000, 0x2000000010L to -7))
        val sb = StringBuilder()
        val n = ScanResultsCsv.write(
            sb, res,
            listOf(com.ps4smartcheat.core.model.MemoryRegion("eboot.bin", 0x500000, 0x510000, 0, 3)),
            processName = "eboot, bin",
        )
        assertEquals(2, n)
        val lines = sb.lines().filter { it.isNotEmpty() }
        assertEquals("# process=\"eboot, bin\"", lines[0])
        assertEquals(ScanResultsCsv.HEADER, lines[1])
        assertEquals("0,0x000500120,Int32,5000,88130000,eboot.bin+0x120 [rw-]", lines[2])
        assertEquals("1,0x2000000010,Int32,-7,F9FFFFFF,unmapped", lines[3])
    }
}

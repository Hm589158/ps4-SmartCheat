package com.ps4smartcheat.core.tools

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_R
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_W
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_X
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PatternSearchTest {
    @Test fun parsesHexPatternsWithWildcards() {
        val p = BytePattern.hex("48 8b ?? 4? ?F 05")
        assertEquals(6, p.length)
        assertEquals("48 8B ?? 4? ?F 05", p.description)
        assertTrue(p.matchesAt(byteArrayOf(0x48, 0x8B.toByte(), 0x11, 0x4C, 0x3F, 0x05), 0))
        assertFalse(p.matchesAt(byteArrayOf(0x48, 0x8B.toByte(), 0x11, 0x5C, 0x3F, 0x05), 0))
        assertFalse(p.matchesAt(byteArrayOf(0x48, 0x8B.toByte(), 0x11, 0x4C, 0x3E, 0x05), 0))
        assertEquals(4, BytePattern.hex("DEADBEEF").length) // compact form
        assertThrows(PatternParseException::class.java) { BytePattern.hex("") }
        assertThrows(PatternParseException::class.java) { BytePattern.hex("?? ??") } // nothing fixed
        assertThrows(PatternParseException::class.java) { BytePattern.hex("4G") }
        assertThrows(PatternParseException::class.java) { BytePattern.hex("123") }
    }

    @Test fun textPatterns() {
        val p = BytePattern.text("Ammo", BytePattern.Companion.TextEncoding.UTF8, ignoreCase = true)
        assertTrue(p.matchesAt("AMMO".toByteArray(), 0))
        assertTrue(p.matchesAt("ammo".toByteArray(), 0))
        assertFalse(p.matchesAt("amm0".toByteArray(), 0))
        val exact = BytePattern.text("Ammo", BytePattern.Companion.TextEncoding.UTF8, ignoreCase = false)
        assertFalse(exact.matchesAt("ammo".toByteArray(), 0))
        val wide = BytePattern.text("Hi", BytePattern.Companion.TextEncoding.UTF16LE, ignoreCase = true)
        assertEquals(4, wide.length)
        assertTrue(wide.matchesAt(byteArrayOf(0x68, 0, 0x49, 0), 0))
    }

    private val server = MockPs4DebugServer()
    private val client = Ps4DebugClient(maxReadChunk = 4096)

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    @Test fun findsEveryOccurrenceIncludingAcrossChunkBordersButNotAcrossRegions() = runBlocking {
        val p = server.addProcess(MockPs4DebugServer.Process(9, "eboot.bin"))
        val a = p.region("a", 0x10_0000, 0x3000, PROT_R or PROT_W)
        val b = p.region("b", 0x10_3000, 0x1000, PROT_R or PROT_X)
        val rnd = Random(7)
        rnd.nextBytes(a.data); rnd.nextBytes(b.data)
        val needle = byteArrayOf(0x13, 0x37, 0x42, 0x24, 0x55)
        fun plant(r: MockPs4DebugServer.Region, off: Int) = System.arraycopy(needle, 0, r.data, off, needle.size)
        plant(a, 0); plant(a, 0x0FFE) /* crosses a 4 KiB chunk border */
        plant(b, 0x10); plant(b, 0x1000 - 5) /* ends exactly at the region end */
        // Split across the a|b boundary: must NOT be reported.
        System.arraycopy(needle, 0, a.data, 0x3000 - 2, 2); System.arraycopy(needle, 2, b.data, 0, 3)
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
        val regions = client.getMemoryRegions(9)
        val pattern = BytePattern.hex("13 37 ?? 24 55")
        val res = PatternSearch.search(client, 9, regions, pattern, chunkSize = 0x1000)
        // Brute-force reference per region.
        val expected = listOf(a, b).flatMap { r ->
            (0..r.data.size - 5).filter { o -> pattern.matchesAt(r.data, o) }.map { r.start + it }
        }
        assertEquals(expected, res.hits.map { it.address })
        assertTrue(res.hits.map { it.address }.containsAll(listOf(0x10_0000L, 0x10_0FFEL, 0x10_3010L, 0x10_3FFBL)))
        assertFalse(res.truncated)
        assertTrue(client.state.value is ConnectionState.Connected)
        // Aligned search only reports aligned starts; the hit cap truncates.
        val aligned = PatternSearch.search(client, 9, regions, pattern, chunkSize = 0x1000, alignment = 4)
        assertEquals(expected.filter { it % 4 == 0L }, aligned.hits.map { it.address })
        val capped = PatternSearch.search(client, 9, regions, pattern, maxHits = 2, chunkSize = 0x1000)
        assertTrue(capped.truncated)
        assertEquals(expected.take(2), capped.hits.map { it.address })
    }
}

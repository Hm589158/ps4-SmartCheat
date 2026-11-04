package com.ps4smartcheat.core.diag

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDiagnosticsTest {
    @Test fun statsMaths() {
        val s = PingStats(listOf(10.0, 14.0, 12.0), failures = 1)
        assertEquals(10.0, s.minMs!!, 0.0)
        assertEquals(14.0, s.maxMs!!, 0.0)
        assertEquals(12.0, s.avgMs!!, 1e-9)
        assertEquals(3.0, s.jitterMs!!, 1e-9) // (4 + 2) / 2
        assertNull(PingStats(emptyList(), 3).avgMs)
    }

    @Test fun pingMeasuresTheMockLinkLatency() = runBlocking {
        MockPs4DebugServer().use { server ->
            server.faults.linkLatencyMs = 30L
            val client = Ps4DebugClient()
            client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
            val s = LinkDiagnostics.ping(client, count = 4, pauseMs = 0)
            assertEquals(4, s.samples.size)
            assertEquals(0, s.failures)
            assertTrue("avg ${s.avgMs}", s.avgMs!! >= 25.0) // mock adds ≥30 ms per reply
            client.disconnect()
        }
    }
}

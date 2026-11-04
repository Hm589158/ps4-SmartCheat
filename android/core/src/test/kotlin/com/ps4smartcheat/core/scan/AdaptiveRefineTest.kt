package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRefineTest {
    @Test fun breakEvenGapIsBandwidthTimesRtt() {
        assertEquals(16_384, ScanEngine.LinkEstimate(2.0, 8_192_000.0).breakEvenGap)
        assertEquals(1024, ScanEngine.LinkEstimate(0.01, 1e6).breakEvenGap) // clamped low
        assertEquals(1 shl 20, ScanEngine.LinkEstimate(1000.0, 1e9).breakEvenGap) // clamped high
    }

    @Test fun measuresSimulatedLink() = runBlocking {
        MockPs4DebugServer().use { server ->
            server.faults.linkLatencyMs = 10
            server.faults.linkBytesPerSecond = 4L shl 20
            val p = server.addProcess(MockPs4DebugServer.Process(5, "eboot.bin"))
            p.region("heap", 0x10_0000, 1 shl 20, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_W)
            val client = Ps4DebugClient()
            client.connect(ConnectOptions("127.0.0.1", server.port, firmwareProbeTimeoutMs = 300))
            val est = ScanEngine.measureLink(client, 5, MemoryRegion("heap", 0x10_0000, 0x20_0000, 0, 3))!!
            assertTrue("rtt ${est.rttMs}", est.rttMs in 9.0..40.0)
            assertTrue("bw ${est.bytesPerSecond}", est.bytesPerSecond in 1.5e6..8e6)
            client.disconnect()
        }
    }
}

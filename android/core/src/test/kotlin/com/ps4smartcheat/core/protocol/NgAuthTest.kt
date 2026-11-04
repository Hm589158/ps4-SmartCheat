package com.ps4smartcheat.core.protocol

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.scan.BackendPreference
import com.ps4smartcheat.core.scan.ScanBackendKind
import com.ps4smartcheat.core.scan.ScanController
import com.ps4smartcheat.core.scan.ScanMode
import com.ps4smartcheat.core.scan.ScanOptions
import com.ps4smartcheat.core.scan.ScanStatus
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Hex
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NgAuthTest {
    /**
     * Reference vectors produced by compiling `auth_lfsr_next` + `proc_auth_xor_keystream` copied verbatim
     * from ps4debug-NG debugger/source/proc.c with gcc (see docs/PROTOCOL.md).
     */
    @Test fun keystreamMatchesUpstreamCReference() {
        val zero = ByteArray(64)
        assertArrayEquals(
            Hex.decode("A0488FED8232B5EE44C1DC1CE467A658B1FE5DC097E0622B3BDA48B6527506E1618B46B94C8CD91A85BBFEF3953263CB1808948CECF76BC633A48B91E18123B5"),
            NgProtocol.authResponse(zero),
        )
        val ramp = ByteArray(64) { (it * 37 + 11).toByte() }
        assertArrayEquals(
            Hex.decode("AB78DA971DF65CE07799A1BE238BB76EEA7EF80A78F45B75B872854445496767CA5BB3A373E850B45643E3B1F2BED21DE328D1E66343B23810ECE603565D2293"),
            NgProtocol.authResponse(ramp),
        )
    }

    @Test fun requestsOnlyTheScanPermissionBit() {
        MockPs4DebugServer().use { server ->
            server.faults.enableNg()
            val client = Ps4DebugClient()
            runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, firmwareProbeTimeoutMs = 300)) }
            assertTrue(client.capabilities.scanAuthorized)
            assertEquals(NgProtocol.AUTH_FLAG_SCAN, server.lastAuthFlags)
            runBlocking { client.disconnect() }
        }
    }

    @Test fun reauthenticatesAutomaticallyAfterAuthorisationIsLost() = runBlocking {
        MockPs4DebugServer().use { server ->
            server.faults.enableNg(turbo = true)
            val p = server.addProcess(MockPs4DebugServer.Process(9, "eboot.bin"))
            p.region("heap", 0x1_0000_0000L, 1 shl 20, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_W)
            server.poke(9, 0x1_0000_0100L, byteArrayOf(0x39, 0x05, 0, 0)) // 1337
            val client = Ps4DebugClient()
            client.connect(ConnectOptions("127.0.0.1", server.port, firmwareProbeTimeoutMs = 300))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = ScanController(client, scope)
            val attemptsBefore = server.authAttempts.get()
            server.revokeAuth()
            controller.firstScan(9, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "1337", options = ScanOptions(backend = BackendPreference.AUTO))
            val st = withTimeout(30_000) { controller.status.first { it !is ScanStatus.Running } }
            assertTrue(st.toString(), st is ScanStatus.Completed)
            val stats = controller.lastStats.value!!
            // Turbo START was refused (server closed the session); the client reconnected, re-authenticated,
            // and the next tier ran on the PS4.
            assertEquals(ScanBackendKind.SERVER, stats.backend)
            assertTrue(stats.fallbacks.toString(), stats.fallbacks.any { it.startsWith("Turbo") })
            assertTrue(server.authAttempts.get() > attemptsBefore)
            assertTrue(client.capabilities.scanAuthorized)
            val r = controller.results()!!
            assertTrue((0 until r.count).any { r.address(it) == 0x1_0000_0100L })
            scope.cancel()
            client.disconnect()
        }
    }
}

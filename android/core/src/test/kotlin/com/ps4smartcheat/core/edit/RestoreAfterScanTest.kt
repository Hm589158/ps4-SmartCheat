package com.ps4smartcheat.core.edit

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ScanController
import com.ps4smartcheat.core.scan.ScanMode
import com.ps4smartcheat.core.scan.ScanStatus
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's journey order (Turbo scan, refine, editor read, write, restore) on one NG connection. */
class RestoreAfterScanTest {
    @Test fun restoreAfterTurboScanAndWrite() = runBlocking {
        MockPs4DebugServer().use { server ->
            server.faults.enableNg(turbo = true)
            DemoGame.install(server)
            val client = Ps4DebugClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000))
                val scanner = ScanController(client, scope)
                scanner.activate(DemoGame.PID)
                scanner.firstScan(DemoGame.PID, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000").join()
                withTimeout(10_000) { scanner.status.first { it !is ScanStatus.Running } }
                server.pokeInt32(DemoGame.PID, DemoGame.COINS_ADDRESS, 4200)
                scanner.nextScan(ScanMode.EXACT, "4200").join()
                withTimeout(10_000) { scanner.status.first { it !is ScanStatus.Running } }
                scanner.readPage(0, 50)
                val editor = MemoryEditor(client, { EditPolicy(editingEnabled = true) })
                val t = EditTarget(DemoGame.PID, "eboot.bin", DemoGame.COINS_ADDRESS, ValueType.INT32)
                editor.read(DemoGame.PID, t.address, t.type)
                assertTrue(editor.execute(editor.plan(t, "99999")) is WriteOutcome.Verified)
                editor.read(DemoGame.PID, t.address, t.type)
                val out = withTimeout(10_000) { editor.restore(DemoGame.PID, t.address) }
                assertTrue(out.toString(), out is WriteOutcome.Verified)
                assertEquals(4200, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
            } finally {
                scope.cancel()
                client.disconnect()
            }
        }
    }
}

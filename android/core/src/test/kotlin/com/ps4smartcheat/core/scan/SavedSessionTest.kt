package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.process.ProcessIdentity
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class SavedSessionTest {
    private val server = MockPs4DebugServer().also { it.faults.enableNg(turbo = true); DemoGame.install(it) }
    private val client = Ps4DebugClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val id = ProcessIdentity(DemoGame.PID, "eboot.bin", "MOCK00001", "/app0/eboot.bin")

    @After fun tearDown() {
        scope.cancel()
        runBlocking { client.disconnect() }
        server.close()
    }

    private suspend fun done(c: ScanController) = withTimeout(20_000) { c.status.first { it !is ScanStatus.Running } }

    private fun addrs(r: ScanResults) = (0 until r.count).map { r.address(it) }

    @Test fun savedSessionResumesInANewControllerAndRefinesLikeTheOriginal() = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 5000))
        // Several candidates with the same value so a refine has something to do.
        for (k in 1..5) server.pokeInt32(DemoGame.PID, DemoGame.DATA_START + 0x800 + k * 16, 5000)
        val a = ScanController(client, scope)
        a.activate(DemoGame.PID)
        a.firstScan(DemoGame.PID, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000").join(); done(a)
        val first = addrs(a.results()!!)
        assertTrue(first.size >= 6)

        val bytes = ByteArrayOutputStream().also { SavedScanSession.write(it, a.exportSession(id, clock = { 42 })!!) }.toByteArray()
        val saved = SavedScanSession.read(ByteArrayInputStream(bytes))
        assertEquals(first, addrs(saved.results))
        assertEquals(1, saved.step)
        assertEquals(42L, saved.savedAtMillis)

        // "App restart": a fresh controller, same running process.
        val b = ScanController(client, scope)
        b.activate(DemoGame.PID)
        assertFalse(b.restoreSession(saved, id.copy(pid = 123))) // another instance: refused
        assertFalse(b.restoreSession(saved, id.copy(titleId = "CUSA99999")))
        assertTrue(b.restoreSession(saved, id))
        assertEquals(1, b.session.value!!.step)
        assertEquals(first.size, b.session.value!!.resultCount)

        server.pokeInt32(DemoGame.PID, DemoGame.COINS_ADDRESS, 4200)
        b.nextScan(ScanMode.EXACT, "4200").join(); done(b)
        assertEquals(listOf(DemoGame.COINS_ADDRESS), addrs(b.results()!!))
        assertEquals(2, b.session.value!!.step)
    }

    @Test fun snapshotSessionsAreNotSavedAndBadFilesAreRejected() = runBlocking {
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 5000))
        val a = ScanController(client, scope)
        a.activate(DemoGame.PID)
        assertNull(a.exportSession(id)) // nothing yet
        a.firstScan(DemoGame.PID, "eboot.bin", ValueType.INT32, ScanMode.UNKNOWN_INITIAL, null).join(); done(a)
        assertNull(a.exportSession(id)) // snapshot-based
        a.firstScan(DemoGame.PID, "eboot.bin", ValueType.INT32, ScanMode.EXACT, "5000").join(); done(a)
        assertNull(a.exportSession(id.copy(name = "other.elf"))) // wrong process
        val good = ByteArrayOutputStream().also { SavedScanSession.write(it, a.exportSession(id)!!) }.toByteArray()
        for (bad in listOf(ByteArray(0), "garbage!".toByteArray(), good.copyOf(good.size - 3))) {
            try { SavedScanSession.read(ByteArrayInputStream(bad)); throw AssertionError("accepted") } catch (_: IOException) { }
        }
    }
}

package com.ps4smartcheat.core.protocol

import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.CommandFailedException
import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.backend.NotConnectedException
import com.ps4smartcheat.core.backend.WriteStatus
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

private fun kotlinx.coroutines.CoroutineScope.launchCollect(
    client: Ps4DebugClient, into: MutableList<ConnectionState>,
) = launch { client.state.collect { into += it } }

class Ps4DebugClientTest {
    private lateinit var server: MockPs4DebugServer
    private lateinit var client: Ps4DebugClient

    private fun opts(readTimeout: Int = 3000) =
        ConnectOptions(host = "127.0.0.1", port = server.port, readTimeoutMs = readTimeout, firmwareProbeTimeoutMs = 500)

    @Before fun setUp() {
        server = MockPs4DebugServer()
        DemoGame.install(server)
        client = Ps4DebugClient(maxReadChunk = 64 * 1024)
    }

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    @Test fun connectReportsVersionAndFirmware() = runBlocking {
        val info = client.connect(opts())
        assertEquals("1.3", info.debuggerVersion)
        assertEquals(1100, info.firmware)
        assertEquals("11.00", info.firmwareText)
        assertTrue(client.state.value is ConnectionState.Connected)
    }

    @Test fun firmwareUnknownWhenExtensionMissing() = runBlocking {
        server.faults.supportFirmwareCommand = false
        val info = client.connect(opts())
        assertNull(info.firmware)
        // Session must still be in sync after the probe timeout + reconnect.
        assertEquals(3, client.getProcesses().size)
    }

    @Test fun enumeratesProcesses() = runBlocking {
        client.connect(opts())
        val procs = client.getProcesses()
        assertEquals(listOf(1 to "SceSysCore.elf", 40 to "SceShellUI", 77 to "eboot.bin"), procs.map { it.pid to it.name })
    }

    @Test fun emptyProcessListIsHandledAndSessionRecovers() = runBlocking {
        val saved = server.processes.toList()
        server.processes.clear()
        client.connect(opts())
        assertEquals(emptyList<Any>(), client.getProcesses())
        server.processes.addAll(saved)
        assertEquals(3, client.getProcesses().size) // transparently reconnected
    }

    @Test fun readsProcessInfo() = runBlocking {
        client.connect(opts())
        val info = client.getProcessInfo(DemoGame.PID)
        assertEquals("eboot.bin", info.name)
        assertEquals("MOCK00001", info.titleId)
        assertEquals("/app0/eboot.bin", info.path)
        assertEquals("MOCK-CONTENT", info.contentId)
    }

    @Test fun parsesMemoryMaps() = runBlocking {
        client.connect(opts())
        val maps = client.getMemoryRegions(DemoGame.PID)
        assertEquals(3, maps.size)
        val data = maps[1]
        assertEquals("eboot.bin", data.name)
        assertEquals(DemoGame.DATA_START, data.start)
        assertEquals(DemoGame.DATA_START + 0x10000, data.end)
        assertTrue(data.isReadable && data.isWritable && !data.isExecutable)
        assertTrue(maps[0].isExecutable && !maps[0].isWritable)
        assertEquals(DemoGame.HEAP_START, maps[2].start)
    }

    @Test fun mapsForMissingProcessFailsAndSessionRecovers() = runBlocking {
        client.connect(opts())
        try {
            client.getMemoryRegions(9999)
            fail("expected failure")
        } catch (e: CommandFailedException) {
            assertEquals(Ps4DebugProtocol.CMD_ERROR, e.status)
        }
        assertTrue(client.state.value is ConnectionState.Connected)
        assertEquals(3, client.getMemoryRegions(DemoGame.PID).size)
    }

    @Test fun readsMemoryAcrossChunks() = runBlocking {
        client.connect(opts())
        val len = 64 * 1024 * 3 + 123
        val expected = server.peek(DemoGame.PID, DemoGame.HEAP_START + 5, len)
        val actual = client.readMemory(DemoGame.PID, DemoGame.HEAP_START + 5, len)
        assertArrayEquals(expected, actual)
        assertEquals(4, server.readRequests.get())
    }

    @Test fun unmappedMemoryReadsAsZeros() = runBlocking {
        client.connect(opts())
        val bytes = client.readMemory(DemoGame.PID, 0x7000_0000L, 16)
        assertArrayEquals(ByteArray(16), bytes)
    }

    @Test fun writeAndReadBack() = runBlocking {
        client.connect(opts())
        val data = byteArrayOf(0x39, 0x05, 0, 0) // 1337
        assertEquals(WriteStatus.Accepted, client.writeMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, data))
        assertEquals(1337, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
        assertArrayEquals(data, client.readMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, 4))
    }

    @Test fun writeToUnmappedIsRejectedWithoutDisconnect() = runBlocking {
        client.connect(opts())
        val st = client.writeMemory(DemoGame.PID, 0x7000_0000L, byteArrayOf(1))
        assertTrue(st is WriteStatus.Rejected)
        assertEquals(3, client.getProcesses().size)
    }

    @Test fun readTimeoutMarksConnectionFailed() = runBlocking {
        client.connect(opts(readTimeout = 400).copy(reconnectBackoffMs = 50))
        server.faults.stallOnCommand = MockPs4DebugServer.CMD_PROC_LIST
        try {
            client.getProcesses()
            fail("expected timeout")
        } catch (e: BackendException) {
            assertTrue(e.message!!, e.message!!.contains("Timed out"))
        }
        assertTrue(client.state.value is ConnectionState.Failed)
        try {
            client.getProcesses(); fail()
        } catch (e: NotConnectedException) {
            // expected: no silent reconnect after a timeout
        }
    }

    @Test fun droppedConnectionDuringReadIsReported() = runBlocking {
        client.connect(opts())
        server.faults.dropReadAfterBytes = 1000
        try {
            client.readMemory(DemoGame.PID, DemoGame.HEAP_START, 4096)
            fail()
        } catch (e: BackendException) {
            assertTrue(e.message!!.contains("closed"))
        }
        assertTrue(client.state.value is ConnectionState.Failed)
    }

    @Test fun truncatedMapResponseIsReported() = runBlocking {
        client.connect(opts())
        server.faults.truncatedMaps = true
        try {
            client.getMemoryRegions(DemoGame.PID); fail()
        } catch (e: BackendException) {
            assertTrue(client.state.value is ConnectionState.Failed)
        }
    }

    @Test fun garbageStatusIsDetectedAsDesync() = runBlocking {
        client.connect(opts())
        server.faults.garbageStatusForList = true
        try {
            client.getProcesses(); fail()
        } catch (e: BackendException) {
            assertTrue(e.message!!, e.message!!.contains("out of sync"))
        }
    }

    @Test fun connectionRefusedIsActionable() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }
        try {
            client.connect(ConnectOptions("127.0.0.1", freePort, connectTimeoutMs = 1000))
            fail()
        } catch (e: BackendException) {
            assertTrue(e.message!!, e.message!!.contains("refused"))
        }
        assertTrue(client.state.value is ConnectionState.Failed)
    }

    @Test fun publicAddressesAreRejectedByDefault() = runBlocking {
        try {
            client.connect(ConnectOptions("8.8.8.8", 744, connectTimeoutMs = 500))
            fail()
        } catch (e: BackendException) {
            assertTrue(e.message!!.contains("not a local-network address"))
        }
    }

    @Test fun nonPs4debugServerIsRejected() = runBlocking {
        // A server that answers with an absurd version length (e.g. an HTTP server).
        ServerSocket(0).use { ss ->
            val t = Thread {
                runCatching { ss.accept().use { s -> s.getInputStream().read(ByteArray(12)); s.getOutputStream().write("HTTP/1.1 400\r\n".toByteArray()); Thread.sleep(200) } }
            }.apply { start() }
            try {
                client.connect(ConnectOptions("127.0.0.1", ss.localPort, readTimeoutMs = 1000))
                fail()
            } catch (e: BackendException) {
                assertTrue(e.message!!, e.message!!.contains("ps4debug"))
            }
            t.join()
        }
    }

    @Test fun disconnectIsCleanAndIdempotent() = runBlocking {
        client.connect(opts())
        client.disconnect()
        client.disconnect()
        assertEquals(ConnectionState.Disconnected, client.state.value)
        try { client.getProcesses(); fail() } catch (e: NotConnectedException) { }
        // Reconnect works after disconnect.
        client.connect(opts())
        assertEquals(3, client.getProcesses().size)
    }

    @Test fun serverDropIsRecoveredTransparentlyForReads() = runBlocking {
        client.connect(opts())
        server.dropAllClients()
        Thread.sleep(100)
        assertEquals(3, client.getProcesses().size) // read repeated once on the new session
        assertTrue(client.state.value is ConnectionState.Connected)
        assertEquals(1, client.reconnectCount.get())
    }

    @Test fun serverDropIsFatalWhenAutoReconnectIsOff() = runBlocking {
        client.connect(opts().copy(autoReconnect = false))
        server.dropAllClients()
        Thread.sleep(100)
        try { client.getProcesses(); fail() } catch (e: BackendException) { }
        assertTrue(client.state.value is ConnectionState.Failed)
    }

    @Test fun writeIsNeverRepeatedAfterReconnect() = runBlocking {
        client.connect(opts())
        server.faults.dropOnCommand = MockPs4DebugServer.CMD_PROC_WRITE
        try {
            client.writeMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, byteArrayOf(1, 0, 0, 0)); fail()
        } catch (e: com.ps4smartcheat.core.backend.ConnectionInterruptedException) {
            assertTrue(e.message!!, e.message!!.contains("not repeated"))
        }
        assertEquals(5000, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
        assertEquals(0, server.writeRequests.get()) // never executed, never replayed
        assertTrue(client.state.value is ConnectionState.Connected)
        server.faults.dropOnCommand = null
        assertEquals(WriteStatus.Accepted, client.writeMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, byteArrayOf(1, 0, 0, 0)))
        assertEquals(1, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
    }

    @Test fun serverGoneFailsAfterReconnectAttempts() = runBlocking {
        client.connect(opts().copy(reconnectAttempts = 2, reconnectBackoffMs = 50))
        val seen = java.util.concurrent.CopyOnWriteArrayList<ConnectionState>()
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launchCollect(client, seen)
        server.close()
        try { client.getProcesses(); fail() } catch (e: BackendException) { }
        Thread.sleep(100)
        watcher.cancel()
        assertTrue(client.state.value is ConnectionState.Failed)
        assertTrue(seen.toString(), seen.any { it is ConnectionState.Reconnecting && it.attempt == 2 })
    }

    @Test fun pingDetectsAndRepairsDeadLink() = runBlocking {
        client.connect(opts())
        server.dropAllClients()
        Thread.sleep(100)
        client.ping()
        assertEquals(1, client.reconnectCount.get())
        assertTrue(client.idleMillis() < 1000)
    }

    @Test fun eventsAreStructured() = runBlocking {
        val events = java.util.concurrent.CopyOnWriteArrayList<com.ps4smartcheat.core.logging.Event>()
        client.events = com.ps4smartcheat.core.logging.EventSink { events += it }
        client.connect(opts())
        server.dropAllClients()
        Thread.sleep(100)
        client.getProcesses()
        val msgs = events.map { it.message }
        assertTrue(msgs.toString(), msgs.containsAll(listOf("connected", "connection lost", "reconnected")))
        val line = events.first { it.message == "connected" }.toJsonLine()
        assertTrue(line, line.startsWith("{\"t\":") && line.contains("\"cat\":\"CONNECTION\"") && line.contains("\"port\":\"${server.port}\""))
    }

    @Test fun parsersHandleExactLayouts() {
        val entry = ByteArray(58)
        "libkernel.sprx".toByteArray().copyInto(entry)
        java.nio.ByteBuffer.wrap(entry).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            position(32); putLong(0x8_0000_0000L); putLong(0x8_0001_0000L); putLong(0x40L); putShort(5)
        }
        val r = Ps4DebugClient.parseMaps(entry, 1).single()
        assertEquals("libkernel.sprx", r.name)
        assertEquals(0x8_0000_0000L, r.start)
        assertEquals(0x10000L, r.size)
        assertEquals("r-x", r.protString())
    }
}

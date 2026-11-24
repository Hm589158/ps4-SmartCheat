package com.ps4smartcheat.core.protocol

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ps4debug-NG commands beyond the classic set, used only when the payload's capability level guarantees them: an
 * unknown command makes the payload drop the connection.
 */
class NgExtrasTest {
    private val server = MockPs4DebugServer()
    private val client = Ps4DebugClient()

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private fun connect(configure: (MockPs4DebugServer.Faults) -> Unit) {
        configure(server.faults)
        runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 5000, firmwareProbeTimeoutMs = 300)) }
    }

    @Test fun foregroundGameWithItsVersion() = runBlocking {
        server.addProcess(MockPs4DebugServer.Process(40, "SceShellUI"))
        server.addProcess(MockPs4DebugServer.Process(77, "eboot.bin", titleId = "CUSA05294", contentId = "EP0001-CUSA05294_00-GAME", appVersion = "01.25"))
        connect { it.enableNg() }
        val fg = client.foregroundApp()!!
        assertEquals(77, fg.pid)
        assertEquals("CUSA05294", fg.titleId)
        assertEquals("eboot.bin", fg.name)
        assertEquals("01.25", fg.appVersion)
        assertEquals("EP0001-CUSA05294_00-GAME", fg.contentId)
    }

    @Test fun noGameAndOlderPayloadsAnswerNull() = runBlocking {
        connect { it.enableNg() }
        assertNull(client.foregroundApp()) // pid 0: nothing in the foreground
        assertEquals(1, server.foregroundRequests.get())
    }

    @Test fun olderBuildsAreNeverAsked() = runBlocking {
        server.addProcess(MockPs4DebugServer.Process(77, "eboot.bin", titleId = "CUSA05294"))
        connect { it.enableNg(); it.capability("1.1") }
        assertNull(client.foregroundApp())
        assertEquals(0, server.foregroundRequests.get())
        assertTrue(client.getProcesses().isNotEmpty()) // connection still up
    }

    @Test fun classicPs4debugIsNeverAsked() = runBlocking {
        server.addProcess(MockPs4DebugServer.Process(77, "eboot.bin", titleId = "CUSA05294"))
        connect { it.closeOnUnknownCommand = true }
        assertNull(client.foregroundApp())
        assertEquals(0, server.foregroundRequests.get())
        assertTrue(client.getProcesses().isNotEmpty())
    }
}

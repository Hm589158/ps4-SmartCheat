package com.ps4smartcheat.core.protocol

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.backend.WriteStatus
import com.ps4smartcheat.core.edit.EditPolicy
import com.ps4smartcheat.core.edit.EditTarget
import com.ps4smartcheat.core.edit.MemoryEditor
import com.ps4smartcheat.core.edit.WriteOutcome
import com.ps4smartcheat.core.live.FreezeManager
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_R
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_W
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_X
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // ------------------------------------------------------------------ writes

    private fun game(): MockPs4DebugServer.Process =
        server.addProcess(MockPs4DebugServer.Process(77, "eboot.bin", titleId = "CUSA05294")).also {
            it.region("heap", 0x10_0000, 0x1000, PROT_R or PROT_W)
            it.region("code", 0x40_0000, 0x1000, PROT_R or PROT_X)
        }

    private val editor by lazy { MemoryEditor(client, { EditPolicy(editingEnabled = true, allowNonWritableRegions = true) }) }

    private suspend fun write(address: Long, value: String): WriteOutcome =
        editor.execute(editor.plan(EditTarget(77, "eboot.bin", address, ValueType.INT32), value))

    @Test fun aWriteIsVerifiedOnThePs4WithoutASecondRead() = runBlocking {
        game()
        connect { it.enableNg() }
        val plan = editor.plan(EditTarget(77, "eboot.bin", 0x10_0010, ValueType.INT32), "999")
        val reads = server.readRequests.get()
        val out = editor.execute(plan)
        assertTrue(out.toString(), out is WriteOutcome.Verified)
        assertEquals("one read for the undo journal, none to verify", reads + 1, server.readRequests.get())
        assertEquals(999, java.nio.ByteBuffer.wrap(client.readMemory(77, 0x10_0010, 4)).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(1, editor.undoStack.value.size)
    }

    @Test fun aWriteThePs4CannotReadBackIsRejected() = runBlocking {
        game()
        connect { it.enableNg(); it.ignoreWrites = true }
        val out = write(0x10_0010, "5")
        assertTrue(out.toString(), out is WriteOutcome.Rejected)
        assertTrue(editor.undoStack.value.isEmpty())
    }

    @Test fun olderPayloadsAreVerifiedWithAFreshRead() = runBlocking {
        game()
        connect { it.enableNg(); it.capability("1.0") }
        val plan = editor.plan(EditTarget(77, "eboot.bin", 0x10_0010, ValueType.INT32), "7")
        val reads = server.readRequests.get()
        assertTrue(editor.execute(plan) is WriteOutcome.Verified)
        assertEquals(reads + 2, server.readRequests.get())
        assertEquals(WriteStatus.Accepted, client.writeMemory(77, 0x10_0010, byteArrayOf(1, 0, 0, 0)))
    }

    @Test fun dataWritesGoInOneBulkWriteCodeDoesNot() = runBlocking {
        game()
        connect { it.enableNg() }
        val plans = (0 until 5).map { editor.plan(EditTarget(77, "eboot.bin", 0x10_0100L + 8 * it, ValueType.INT32), "${100 + it}") }
        val outs = editor.executeBatch(plans)
        assertNotNull(outs)
        assertTrue(outs.toString(), outs!!.all { it is WriteOutcome.Verified })
        assertEquals(1, server.bulkWriteRequests.get())
        for (i in 0 until 5) assertEquals(100 + i, java.nio.ByteBuffer.wrap(client.readMemory(77, 0x10_0100L + 8 * i, 4)).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(5, editor.undoStack.value.size)
        // A part in executable memory: refused for the batch (order matters for code), written one at a time instead.
        val code = editor.planBytes(77, "eboot.bin", 0x40_0010, byteArrayOf(0x90.toByte()))
        assertNull(editor.executeBatch(plans.take(1) + code))
    }

    @Test fun aFailedBulkEntryIsReportedAndTheOthersStay() = runBlocking {
        game()
        connect { it.enableNg() }
        val st = client.writeMany(77, listOf(0x10_0000L to byteArrayOf(1), 0x90_0000L to byteArrayOf(2), 0x10_0004L to byteArrayOf(3)))!!
        assertEquals(WriteStatus.VerifiedOnConsole, st[0])
        assertTrue(st[1] is WriteStatus.Rejected)
        assertEquals(WriteStatus.VerifiedOnConsole, st[2]) // non-atomic upstream: later entries are still written
        assertTrue(client.getProcesses().isNotEmpty())
    }

    @Test fun bulkWriteIsNotSentToClassicPs4debug() = runBlocking {
        game()
        connect { it.closeOnUnknownCommand = true }
        assertNull(client.writeMany(77, listOf(0x10_0000L to byteArrayOf(1), 0x10_0004L to byteArrayOf(2))))
        assertEquals(0, server.bulkWriteRequests.get())
    }

    @Test fun frozenValuesAreWrittenInOneRequestPerTick() = runBlocking {
        game()
        connect { it.enableNg() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val freezer = FreezeManager(client, scope, { EditPolicy(editingEnabled = true) })
            for (i in 0 until 4) freezer.freeze(editor.plan(EditTarget(77, "eboot.bin", 0x10_0200L + 4 * i, ValueType.INT32), "42"), "v$i")
            delay(300) // the first tick may have run while only one value was frozen (a single write)
            val writes = server.writeRequests.get()
            val bulk = server.bulkWriteRequests.get()
            delay(600)
            assertTrue(server.bulkWriteRequests.get() > bulk)
            assertEquals("no single writes while bulk writes work", writes, server.writeRequests.get())
            freezer.stopAll("test")
        } finally {
            scope.cancel()
        }
    }

    @Test fun notificationOnThePs4() = runBlocking {
        connect { it.enableNg() }
        assertTrue(client.notify("SmartCheat: Infinite health ON"))
        assertEquals(listOf(222 to "SmartCheat: Infinite health ON"), server.notifications.toList())
        assertTrue(client.getProcesses().isEmpty()) // the connection is kept (no processes in this mock)
    }

    @Test fun noNotificationToOtherPayloads() = runBlocking {
        connect { it.closeOnUnknownCommand = true }
        assertTrue(!client.notify("x"))
        assertTrue(server.notifications.isEmpty())
    }
}

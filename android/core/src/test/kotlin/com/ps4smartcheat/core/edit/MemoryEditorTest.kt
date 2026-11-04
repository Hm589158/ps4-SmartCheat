package com.ps4smartcheat.core.edit

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MemoryEditorTest {
    private lateinit var server: MockPs4DebugServer
    private lateinit var client: Ps4DebugClient
    private var policy = EditPolicy(editingEnabled = true)
    private lateinit var editor: MemoryEditor
    private val pid = DemoGame.PID
    private val coins = EditTarget(pid, "eboot.bin", DemoGame.COINS_ADDRESS, ValueType.INT32)

    @Before fun setUp() {
        server = MockPs4DebugServer()
        DemoGame.install(server)
        client = Ps4DebugClient()
        runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000)) }
        editor = MemoryEditor(client, { policy })
    }

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() } catch (t: Throwable) { if (t is T) return t; throw t }
        fail("expected ${T::class.simpleName}"); throw IllegalStateException()
    }

    @Test fun writeVerifyAndRestore() = runBlocking {
        val plan = editor.plan(coins, "999999")
        assertEquals(5000, java.nio.ByteBuffer.wrap(plan.original).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertTrue(plan.warnings.isEmpty())
        val out = editor.execute(plan)
        assertTrue(out.toString(), out is WriteOutcome.Verified)
        assertEquals(999999, server.peekInt32(pid, DemoGame.COINS_ADDRESS))
        assertEquals(1, editor.journal.value.size)

        // Second write keeps the first original.
        editor.execute(editor.plan(coins, "7"))
        assertEquals(1, editor.journal.value.size)

        val restored = editor.restore(pid, DemoGame.COINS_ADDRESS)
        assertTrue(restored is WriteOutcome.Verified)
        assertEquals(5000, server.peekInt32(pid, DemoGame.COINS_ADDRESS))
        assertTrue(editor.journal.value.isEmpty())
    }

    @Test fun readOnlyModeBlocksWrites() = runBlocking {
        policy = EditPolicy(editingEnabled = false)
        val e = expect<EditDeniedException> { runBlocking { editor.plan(coins, "1") } }
        assertTrue(e.message!!.contains("Read-only"))
        assertEquals(0, server.writeRequests.get())
    }

    @Test fun systemProcessesAreProtected() = runBlocking {
        expect<EditDeniedException> { runBlocking { editor.plan(EditTarget(40, "SceShellUI", 0x1000, ValueType.INT32), "1") } }
        Unit
    }

    @Test fun nonWritableRegionNeedsOptIn() = runBlocking {
        val code = EditTarget(pid, "eboot.bin", DemoGame.TEXT_START + 0x10, ValueType.INT32)
        expect<EditDeniedException> { runBlocking { editor.plan(code, "0x90909090") } }
        policy = policy.copy(allowNonWritableRegions = true)
        val plan = editor.plan(code, "0x90909090")
        assertTrue(plan.dangerous)
        assertTrue(editor.execute(plan) is WriteOutcome.Verified)
    }

    @Test fun unmappedAddressIsRejected() = runBlocking {
        val e = expect<EditDeniedException> { runBlocking { editor.plan(coins.copy(address = 0x7000_0000L), "1") } }
        assertTrue(e.message!!.contains("not mapped"))
        // Value straddling the end of the data region is rejected too.
        expect<EditDeniedException> { runBlocking { editor.plan(coins.copy(address = DemoGame.DATA_START + 0x10000 - 2), "1") } }
        Unit
    }

    @Test fun invalidInputIsRejected() = runBlocking {
        expect<IllegalArgumentException> { runBlocking { editor.plan(coins, "12abc") } }
        expect<IllegalArgumentException> { runBlocking { editor.plan(coins.copy(type = ValueType.INT8), "300") } }
        Unit
    }

    @Test fun silentlyIgnoredWriteIsDetectedByReadBack() = runBlocking {
        server.faults.ignoreWrites = true
        val out = editor.execute(editor.plan(coins, "1"))
        assertTrue(out is WriteOutcome.Mismatch)
        assertTrue(out.message.contains("Verification failed"))
    }

    @Test fun rejectedWriteIsReported() = runBlocking {
        val plan = editor.plan(coins, "1")
        server.killProcess(pid) // process exits between planning and writing
        val out = editor.execute(plan)
        assertTrue(out.toString(), out is WriteOutcome.Rejected || out is WriteOutcome.Mismatch)
    }

    @Test fun disconnectDuringWriteIsReported() = runBlocking {
        val plan = editor.plan(coins, "1")
        server.faults.dropOnCommand = MockPs4DebugServer.CMD_PROC_WRITE
        val out = editor.execute(plan)
        assertTrue(out.toString(), out is WriteOutcome.Failed)
    }

    @Test fun restoreWithoutJournalFails() = runBlocking {
        assertTrue(editor.restore(pid, 0x1234) is WriteOutcome.Failed)
    }
}

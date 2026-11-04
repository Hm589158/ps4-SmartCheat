package com.ps4smartcheat.core.edit

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EditHistoryTest {
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

    private fun coinsNow() = server.peekInt32(pid, DemoGame.COINS_ADDRESS)

    @Test fun undoAndRedoStepThroughVerifiedWrites() = runBlocking {
        editor.execute(editor.plan(coins, "100"))
        editor.execute(editor.plan(coins, "200"))
        assertEquals(2, editor.undoStack.value.size)
        assertTrue(editor.undo() is WriteOutcome.Verified)
        assertEquals(100, coinsNow())
        assertTrue(editor.undo() is WriteOutcome.Verified)
        assertEquals(5000, coinsNow())
        assertTrue(editor.undo() is WriteOutcome.Failed) // nothing left
        assertTrue(editor.redo() is WriteOutcome.Verified)
        assertEquals(100, coinsNow())
        // A new write clears the redo stack.
        editor.execute(editor.plan(coins, "300"))
        assertTrue(editor.redoStack.value.isEmpty())
        assertTrue(editor.redo() is WriteOutcome.Failed)
    }

    @Test fun peekShowsWhenTheGameChangedTheValueAndReadOnlyBlocksUndo() = runBlocking {
        editor.execute(editor.plan(coins, "100"))
        server.pokeInt32(pid, DemoGame.COINS_ADDRESS, 90) // the game spent some
        val (item, now) = editor.peekUndo()!!
        assertEquals(100, java.nio.ByteBuffer.wrap(item.after).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(90, java.nio.ByteBuffer.wrap(now).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        policy = EditPolicy(editingEnabled = false)
        assertTrue(editor.undo() is WriteOutcome.Failed)
        assertEquals(90, coinsNow()) // nothing written
        policy = EditPolicy(editingEnabled = true)
        assertTrue(editor.undo() is WriteOutcome.Verified)
        assertEquals(5000, coinsNow())
    }

    @Test fun rawBytePatchesFollowTheSamePolicyAndCanBeUndone() = runBlocking {
        val at = DemoGame.DATA_START + 0x400
        val plan = editor.planBytes(pid, "eboot.bin", at, byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(5, plan.original.size)
        assertEquals("01 02 03 04 05", plan.replacementText)
        assertTrue(editor.execute(plan) is WriteOutcome.Verified)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), server.peek(pid, at, 5))
        assertTrue(editor.undo() is WriteOutcome.Verified)
        assertArrayEquals(ByteArray(5), server.peek(pid, at, 5))
        // Policy and region rules apply to patches too.
        try { editor.planBytes(pid, "eboot.bin", DemoGame.TEXT_START, byteArrayOf(0x90.toByte())); throw AssertionError("code patch allowed") } catch (_: EditDeniedException) { }
        try { editor.planBytes(pid, "SceShellUI", at, byteArrayOf(1)); throw AssertionError("system process allowed") } catch (_: EditDeniedException) { }
        try { editor.planBytes(pid, "eboot.bin", at, ByteArray(MAX_PATCH_BYTES + 1)); throw AssertionError("oversized") } catch (_: EditDeniedException) { }
        try { editor.planBytes(pid, "eboot.bin", DemoGame.DATA_START + 0x10000 - 2, byteArrayOf(1, 2, 3, 4)); throw AssertionError("straddles region end") } catch (_: EditDeniedException) { }
        policy = EditPolicy(editingEnabled = false)
        try { editor.planBytes(pid, "eboot.bin", at, byteArrayOf(1)); throw AssertionError("read-only allowed") } catch (_: EditDeniedException) { }
    }

    @Test fun historyIsForgottenWhenTheProcessExits() = runBlocking {
        editor.execute(editor.plan(coins, "100"))
        editor.forgetProcess(pid)
        assertTrue(editor.undoStack.value.isEmpty())
        assertTrue(editor.journal.value.isEmpty())
    }
}

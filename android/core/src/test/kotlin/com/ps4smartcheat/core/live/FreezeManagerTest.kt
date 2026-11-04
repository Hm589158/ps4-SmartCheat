package com.ps4smartcheat.core.live

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.edit.EditPolicy
import com.ps4smartcheat.core.edit.EditTarget
import com.ps4smartcheat.core.edit.MemoryEditor
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FreezeManagerTest {
    private lateinit var server: MockPs4DebugServer
    private lateinit var client: Ps4DebugClient
    private lateinit var scope: CoroutineScope
    private var policy = EditPolicy(editingEnabled = true)
    private lateinit var editor: MemoryEditor
    private lateinit var freezer: FreezeManager
    private val target = EditTarget(DemoGame.PID, "eboot.bin", DemoGame.COINS_ADDRESS, ValueType.INT32)

    @Before fun setUp() {
        server = MockPs4DebugServer()
        DemoGame.install(server)
        client = Ps4DebugClient()
        runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 3000)) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        editor = MemoryEditor(client, { policy })
        freezer = FreezeManager(client, scope, { policy }).apply { intervalMs = 50; processCheckEveryTicks = 2 }
    }

    @After fun tearDown() {
        scope.cancel()
        runBlocking { client.disconnect() }
        server.close()
    }

    private suspend fun waitFor(cond: () -> Boolean) {
        repeat(150) { if (cond()) return; delay(20) }
        throw AssertionError("condition not reached")
    }

    @Test fun keepsValueFrozenUntilUnfrozen() = runBlocking {
        val fv = freezer.freeze(editor.plan(target, "9999"), "coins")
        waitFor { server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS) == 9999 }
        server.pokeInt32(DemoGame.PID, DemoGame.COINS_ADDRESS, 1) // game spends coins
        waitFor { server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS) == 9999 }
        freezer.unfreeze(fv.id)
        delay(200)
        server.pokeInt32(DemoGame.PID, DemoGame.COINS_ADDRESS, 5)
        delay(200)
        assertEquals(5, server.peekInt32(DemoGame.PID, DemoGame.COINS_ADDRESS))
    }

    @Test fun stopsWhenProcessExits() = runBlocking {
        freezer.freeze(editor.plan(target, "9999"), "coins")
        server.killProcess(DemoGame.PID)
        waitFor { freezer.frozen.value.isEmpty() }
        assertTrue(freezer.lastStopReason.value!!, freezer.lastStopReason.value!!.contains("exited") || freezer.lastStopReason.value!!.contains("rejected"))
    }

    @Test fun stopsOnDisconnect() = runBlocking {
        freezer.freeze(editor.plan(target, "9999"), "coins")
        server.dropAllClients()
        waitFor { freezer.frozen.value.isEmpty() }
        assertNotNull(freezer.lastStopReason.value)
    }

    @Test fun stopsWhenEditingDisabled() = runBlocking {
        freezer.freeze(editor.plan(target, "9999"), "coins")
        policy = EditPolicy(editingEnabled = false)
        waitFor { freezer.frozen.value.isEmpty() }
        assertTrue(freezer.lastStopReason.value!!.contains("disabled"))
    }
}

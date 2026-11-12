package com.ps4smartcheat.core.protocol

import com.ps4smartcheat.core.backend.BackgroundRequest
import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.mockserver.DemoGame
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Responsiveness of the one connection to the PS4 (simulated server). */
class LinkTest {
    private val server = MockPs4DebugServer().also { DemoGame.install(it) }
    /** Writes that reach the socket: with TCP_NODELAY each one leaves as its own packet. */
    private val socketWrites = AtomicInteger()
    private val client = Ps4DebugClient(socketFactory = {
        object : Socket() {
            private var wrapped: OutputStream? = null
            override fun getOutputStream(): OutputStream = wrapped ?: object : FilterOutputStream(super.getOutputStream()) {
                override fun write(b: ByteArray, off: Int, len: Int) { socketWrites.incrementAndGet(); out.write(b, off, len) }
                override fun write(b: Int) { socketWrites.incrementAndGet(); out.write(b) }
            }.also { wrapped = it }
        }
    })

    @After fun tearDown() {
        runBlocking { client.disconnect() }
        server.close()
    }

    private fun connect() = runBlocking { client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 5000)) }

    @Test fun aRequestLeavesInOnePacket() = runBlocking {
        connect()
        val before = socketWrites.get()
        client.readMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, 4)
        assertEquals(1, socketWrites.get() - before) // header and body together (two writes before)
        val w = socketWrites.get()
        client.writeMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, byteArrayOf(1, 0, 0, 0))
        assertEquals(2, socketWrites.get() - w) // request, then the data after the PS4's go-ahead
    }

    @Test fun linkTimeIsMeasuredFromSmallRequests() = runBlocking {
        connect()
        server.faults.readDelayMs = 40
        repeat(4) { client.readMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, 4) }
        val ms = client.linkMillis.value
        assertNotNull(ms)
        assertTrue("link $ms ms", ms!! >= 20)
    }

    @Test fun userRequestsGoBeforeQueuedBackgroundRequests() = runBlocking {
        connect()
        server.faults.readDelayMs = 80
        val order = Collections.synchronizedList(ArrayList<String>())
        // Five background reads (live values, freezes…) queue up; the first one is already running.
        val background = (1..5).map { i ->
            async(Dispatchers.Default + BackgroundRequest) { client.readMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, 4); order += "bg$i" }
        }
        delay(30)
        val user = async(Dispatchers.Default) { client.readMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, 4); order += "user" }
        (background + user).awaitAll()
        // Only the read already in progress finishes before the user's; the queued ones wait.
        assertTrue(order.toString(), order.indexOf("user") <= 1)
        assertEquals(6, order.size) // and the background reads still all run
    }

    @Test fun backgroundWorkIsNotStarved() = runBlocking {
        connect()
        server.faults.readDelayMs = 20
        // A steady stream of user requests for 3 s; a background request still gets through (after at most MAX_YIELD_MS).
        val busy = async(Dispatchers.Default) {
            val until = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < until) client.readMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, 4)
        }
        delay(50)
        val t0 = System.currentTimeMillis()
        async(Dispatchers.Default + BackgroundRequest) { client.readMemory(DemoGame.PID, DemoGame.COINS_ADDRESS, 4) }.await()
        assertTrue(System.currentTimeMillis() - t0 < BackgroundRequest.MAX_YIELD_MS + 1500)
        busy.await()
    }
}

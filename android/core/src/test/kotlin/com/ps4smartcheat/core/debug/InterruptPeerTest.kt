package com.ps4smartcheat.core.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** The interrupt channel only accepts the PS4 (the command connection's peer). */
class InterruptPeerTest {
    @Test fun connectionsFromOtherHostsAreClosed() {
        val listener = InterruptListener.openExactly(0)
        val ps4 = InetAddress.getByName("127.0.0.1")
        val stranger = InetAddress.getByName("127.0.0.2") // another host on the network (Linux loopback range)
        val foreign = Socket().apply { bind(InetSocketAddress(stranger, 0)); connect(InetSocketAddress(ps4, listener.port)) }
        thread {
            Thread.sleep(300)
            Socket().apply { bind(InetSocketAddress(ps4, 0)); connect(InetSocketAddress(ps4, listener.port)) }
        }
        listener.use { l ->
            val channel = l.accept(5000, peer = ps4)
            assertEquals(1, l.rejected)
            channel.close()
        }
        // The stranger's socket was closed by the app.
        assertTrue(foreign.getInputStream().read() == -1)
        foreign.close()
    }
}

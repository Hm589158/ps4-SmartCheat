package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.CommandFailedException
import com.ps4smartcheat.core.protocol.Ps4DebugProtocol
import com.ps4smartcheat.core.protocol.ServerScanTransport
import com.ps4smartcheat.core.util.LE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/** The interrupt listener could not be opened (on Android: ports below 1024 need root). */
class ListenerUnavailableException(val port: Int, message: String, cause: Throwable? = null) : BackendException(message, cause)

class DebuggerException(message: String, status: Long = Ps4DebugProtocol.CMD_ERROR) : CommandFailedException(0, status, message)

/**
 * Listens for the debugger's interrupt connection. ps4debug-NG connects *from the PS4 to the client* on port 755
 * when attaching (debug.c `connect_debugger`), so this socket must be open before the attach request is sent.
 *
 * Android apps without root cannot bind ports below 1024. Then a relay on the local network can listen on 755 and
 * forward to this listener on a high port (see docs/WATCHPOINTS.md); the relay's address is announced in the attach
 * request instead of the phone's.
 */
class InterruptListener private constructor(private val server: ServerSocket) : Closeable {
    val port: Int get() = server.localPort

    /** Waits for the PS4's connection (normally already queued once the attach request succeeded). */
    fun accept(timeoutMs: Int): InterruptChannel {
        server.soTimeout = timeoutMs
        val s = try { server.accept() } catch (e: SocketTimeoutException) {
            throw BackendException("The PS4 did not open the interrupt connection within ${timeoutMs / 1000} s")
        }
        s.tcpNoDelay = true
        return InterruptChannel(s)
    }

    override fun close() { runCatching { server.close() } }

    companion object {
        fun open(port: Int = DebugProtocol.DEFAULT_INTERRUPT_PORT, bindAddress: InetAddress? = null): InterruptListener {
            require(port in 0..65535) { "port out of range" }
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(bindAddress, port), 1)
            } catch (e: BindException) {
                runCatching { s.close() }
                val why = if (port < 1024) "Android allows apps to listen on ports below 1024 only with root. " +
                    "Use an interrupt relay on your network (Settings → Debugger)." else "Port $port is in use or not allowed."
                throw ListenerUnavailableException(port, "Cannot listen on port $port for the debugger's interrupt connection. $why", e)
            } catch (e: SocketException) {
                runCatching { s.close() }
                throw ListenerUnavailableException(port, "Cannot listen on port $port: ${e.message}", e)
            }
            return InterruptListener(s)
        }
    }
}

/** The connected interrupt channel: a stream of fixed-size [InterruptEvent] packets. */
class InterruptChannel(private val socket: Socket) : Closeable {
    private val input = DataInputStream(socket.getInputStream().buffered(DebugProtocol.INTERRUPT_PACKET_SIZE * 2))
    private val buf = ByteArray(DebugProtocol.INTERRUPT_PACKET_SIZE)
    private var filled = 0

    /** Next stop, or null if none arrived within [timeoutMs]. Throws [EOFException] when the PS4 closed the channel. */
    fun read(timeoutMs: Int): InterruptEvent? {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        try {
            while (filled < buf.size) {
                val n = input.read(buf, filled, buf.size - filled)
                if (n < 0) throw EOFException("The PS4 closed the interrupt connection")
                filled += n
            }
        } catch (e: SocketTimeoutException) {
            return null // partial packets are kept and completed by the next call
        }
        filled = 0
        return InterruptEvent.parse(buf.copyOf())
    }

    override fun close() { runCatching { socket.close() } }
}

/**
 * One ps4debug-NG debug session on a process. Only one session can exist on the PS4 at a time; it ends when
 * [detach] is called or when the command connection closes (server.c `free_client` → `debug_full_teardown`, which
 * clears breakpoints and watchpoints and detaches). Every stop halts the game until [resume].
 */
class DebugSession private constructor(
    private val t: ServerScanTransport,
    val pid: Int,
    private val listener: InterruptListener,
    private val channel: InterruptChannel,
    private val epoch: Long,
) : Closeable {
    @Volatile private var closed = false

    /** False once detached or when the command connection was re-established (the server then detached). */
    val alive: Boolean get() = !closed && t.connectionEpoch == epoch

    suspend fun setWatchpoint(slot: Int, address: Long, size: Int, type: WatchType) {
        require(slot in 0 until DebugProtocol.MAX_WATCHPOINTS) { "slot must be 0..3" }
        require(address % size == 0L) { "A $size-byte watchpoint must be $size-byte aligned" }
        command("set watchpoint", DebugProtocol.CMD_DEBUG_SET_WATCHPOINT,
            LE.bytes32(slot.toLong()) + LE.bytes32(1) + LE.bytes32(DebugProtocol.lengthCode(size).toLong()) +
                LE.bytes32(type.wire.toLong()) + LE.bytes64(address))
    }

    suspend fun clearWatchpoint(slot: Int) {
        command("clear watchpoint", DebugProtocol.CMD_DEBUG_SET_WATCHPOINT,
            LE.bytes32(slot.toLong()) + LE.bytes32(0) + LE.bytes32(0) + LE.bytes32(0) + LE.bytes64(0))
    }

    /** Lets the stopped process run again (CMD_DEBUG_CONTINUE with stop = 0). */
    suspend fun resume() = command("continue", DebugProtocol.CMD_DEBUG_CONTINUE, LE.bytes32(0))

    /** Next stop within [timeoutMs], or null. */
    suspend fun nextEvent(timeoutMs: Int): InterruptEvent? {
        check(alive) { "debug session ended" }
        return withContext(Dispatchers.IO) { channel.read(timeoutMs) }
    }

    /** Ends the session: the server clears watchpoints, detaches and resumes the process. Best effort, idempotent. */
    suspend fun detach() {
        if (closed) return
        try {
            if (t.connectionEpoch == epoch) command("detach", DebugProtocol.CMD_DEBUG_DETACH, ByteArray(0))
        } catch (e: Exception) {
            // The session also ends when the connection closes.
        } finally {
            close()
        }
    }

    override fun close() {
        closed = true
        channel.close()
        listener.close()
    }

    private suspend fun command(what: String, cmd: Int, body: ByteArray) {
        if (!alive) throw DebuggerException("The debug session ended (connection re-established)")
        t.exclusive(what) { w ->
            w.send(w.header(cmd, body.size), body)
            val st = w.readU32()
            if (st != Ps4DebugProtocol.CMD_SUCCESS) {
                w.invalidate()
                throw DebuggerException("PS4 rejected $what: ${Ps4DebugProtocol.statusName(st)}", st)
            }
        }
    }

    companion object {
        /**
         * Attaches to [pid]. [listener] must already be open. [announce] is the IPv4 address the PS4 connects to
         * (default: this device's address on the command connection; a relay's address when one is used).
         */
        suspend fun attach(
            t: ServerScanTransport,
            pid: Int,
            listener: InterruptListener,
            announce: InetAddress? = null,
            acceptTimeoutMs: Int = 5000,
        ): DebugSession {
            if (!t.capabilities.isNg) { listener.close(); throw DebuggerException("Watchpoints need ps4debug-NG.") }
            val ip = (announce ?: t.localAddress) as? Inet4Address
                ?: run { listener.close(); throw DebuggerException("No IPv4 address to announce for the interrupt connection") }
            val epoch = t.connectionEpoch
            try {
                t.exclusive("debug attach") { w ->
                    w.send(w.header(DebugProtocol.CMD_DEBUG_ATTACH, DebugProtocol.ATTACH_PACKET_SIZE), LE.bytes32(pid.toLong()) + ip.address)
                    val st = w.readU32()
                    if (st != Ps4DebugProtocol.CMD_SUCCESS) {
                        w.invalidate() // the attach handler returns 1 on failure: the server closes the connection
                        throw DebuggerException(
                            when (st) {
                                Ps4DebugProtocol.CMD_ALREADY_DEBUG -> "Another debugger session is active on the PS4 (another tool, or a session that did not end). Close it or reload ps4debug-NG."
                                else -> "The PS4 could not attach to the process or could not connect back to ${ip.hostAddress}:${DebugProtocol.DEFAULT_INTERRUPT_PORT} (${Ps4DebugProtocol.statusName(st)}). " +
                                    "If the interrupt connection failed, restart the game before trying again."
                            },
                            st,
                        )
                    }
                }
            } catch (e: Exception) {
                listener.close()
                throw e
            }
            val channel = try {
                withContext(Dispatchers.IO) { listener.accept(acceptTimeoutMs) }
            } catch (e: IOException) {
                // Attached on the PS4 but no event channel here: end the session right away.
                runCatching { t.exclusive("detach") { w -> w.send(w.header(DebugProtocol.CMD_DEBUG_DETACH, 0)); w.readU32() } }
                listener.close()
                throw BackendException("Attached, but the interrupt connection did not arrive: ${e.message}. Detached again.", e)
            }
            return DebugSession(t, pid, listener, channel, epoch)
        }

        /** Sends SIGCONT to a process that was stopped (CMD_DEBUG_PROCESS_STOP state 0). Only without a debug session. */
        suspend fun continueProcess(t: ServerScanTransport, pid: Int) {
            require(pid > 0)
            t.exclusive("resume process") { w ->
                w.send(w.header(DebugProtocol.CMD_DEBUG_PROCESS_STOP, DebugProtocol.PROCESS_STOP_PACKET_SIZE), LE.bytes32(pid.toLong()) + byteArrayOf(0))
                val st = w.readU32()
                if (st != Ps4DebugProtocol.CMD_SUCCESS) {
                    w.invalidate()
                    throw DebuggerException("PS4 rejected resume: ${Ps4DebugProtocol.statusName(st)}", st)
                }
            }
        }
    }
}

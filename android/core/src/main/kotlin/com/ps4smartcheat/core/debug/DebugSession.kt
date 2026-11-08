package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.CommandFailedException
import com.ps4smartcheat.core.protocol.Ps4DebugProtocol
import com.ps4smartcheat.core.protocol.ServerScanTransport
import com.ps4smartcheat.core.util.LE
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.logging.EventLevel
import com.ps4smartcheat.core.logging.EventSink
import com.ps4smartcheat.core.logging.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/** No port could be opened for the debugger's interrupt connection. */
class ListenerUnavailableException(val port: Int, message: String, cause: Throwable? = null) : BackendException(message, cause)

open class DebuggerException(message: String, status: Long = Ps4DebugProtocol.CMD_ERROR) : CommandFailedException(0, status, message)

/** The payload is below capability level 1.2 and port 755 cannot be opened here; checked before attaching. */
class DebuggerUnsupportedException(message: String) : DebuggerException(message)

/** The session's command connection is gone (dropped or re-established); commands are not sent elsewhere. */
class DebuggerSessionLostException(message: String = "The debug session ended (connection re-established)") : DebuggerException(message)

/**
 * What [DebugSession.release] could confirm. `null` = step not needed. [confirmed] is true when the server
 * answered the DETACH (on the session's connection or on a new one), i.e. no debug session of ours is left.
 */
data class ReleaseReport(
    val watchpointCleared: Boolean?,
    val continued: Boolean?,
    val detached: Boolean,
    val recoveredFromNewConnection: Boolean,
    val notes: List<String>,
) {
    val confirmed: Boolean get() = detached
}

/**
 * Listens for the debugger's interrupt connection, which ps4debug-NG opens *from the PS4 to the client* while
 * attaching (debug.c `connect_debugger`), so it must exist before the attach request is sent.
 *
 * The app listens on a high port ([port], normally 7550 — no root needed) and announces it in the extended attach
 * request; ps4debug-NG with the configurable interrupt port connects there. When the device also allows binding the
 * legacy port 755 (root, desktop JVM), that is opened too, so payloads without the change still work.
 */
class InterruptListener private constructor(private val sockets: List<ServerSocket>) : Closeable {
    /** The port announced in the attach request. */
    val port: Int get() = sockets[0].localPort
    /** The legacy port (755) when it could be opened as well; null otherwise (normal on Android). */
    val legacyPort: Int? get() = sockets.getOrNull(1)?.localPort

    /**
     * Waits for the PS4's connection on any of the ports (normally already queued once the attach request
     * succeeded).
     */
    fun accept(timeoutMs: Int): InterruptChannel {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            for (server in sockets) {
                server.soTimeout = if (sockets.size == 1) timeoutMs.coerceAtLeast(1) else 50
                try {
                    val s = server.accept()
                    s.tcpNoDelay = true
                    return InterruptChannel(s)
                } catch (e: SocketTimeoutException) {
                    // try the next socket
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw BackendException("The PS4 did not open the interrupt connection within ${timeoutMs / 1000} s")
            }
        }
    }

    override fun close() { sockets.forEach { runCatching { it.close() } } }

    companion object {
        /**
         * Opens the listener automatically: [preferredPort] (7550), or any free port if it is taken, plus
         * [legacyPort] (755) when the system allows it. Throws [ListenerUnavailableException] only when no port at
         * all can be opened.
         */
        fun open(
            preferredPort: Int = DebugProtocol.PREFERRED_INTERRUPT_PORT,
            bindAddress: InetAddress? = null,
            legacyPort: Int? = DebugProtocol.DEFAULT_INTERRUPT_PORT,
        ): InterruptListener {
            val primary = tryBind(preferredPort, bindAddress) ?: tryBind(0, bindAddress)
                ?: throw ListenerUnavailableException(preferredPort, "Cannot open a network port for the debugger's interrupt connection.")
            val legacy = legacyPort?.takeIf { it != primary.localPort }?.let { tryBind(it, bindAddress) }
            return InterruptListener(listOfNotNull(primary, legacy))
        }

        /** Listens on exactly [port]; throws [ListenerUnavailableException] if it cannot be opened. */
        fun openExactly(port: Int, bindAddress: InetAddress? = null): InterruptListener {
            require(port in 0..65535) { "port out of range" }
            val s = tryBind(port, bindAddress)
                ?: throw ListenerUnavailableException(port, "Cannot listen on port $port for the debugger's interrupt connection (in use or not allowed).")
            return InterruptListener(listOf(s))
        }

        private fun tryBind(port: Int, bindAddress: InetAddress?): ServerSocket? {
            if (port !in 0..65535) return null
            val s = ServerSocket()
            return try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(bindAddress, port), 1)
                s
            } catch (e: IOException) {
                runCatching { s.close() }
                null
            } catch (e: SecurityException) {
                runCatching { s.close() }
                null
            }
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
    private val events: EventSink,
) : Closeable {
    @Volatile private var closed = false
    @Volatile private var released: ReleaseReport? = null
    private val armedSlots = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * True from the moment a stop was read until a CONTINUE was **confirmed** by the server. While true the game is
     * paused on the PS4 (a ptrace stop halts the whole process).
     */
    @Volatile var pendingStop: Boolean = false
        private set

    /** False once released or when the command connection was re-established (the server then detached). */
    val alive: Boolean get() = !closed && t.connectionEpoch == epoch

    /** Port the PS4 connected back to (for diagnostics). */
    val interruptPort: Int get() = listener.port

    suspend fun setWatchpoint(slot: Int, address: Long, size: Int, type: WatchType) {
        require(slot in 0 until DebugProtocol.MAX_WATCHPOINTS) { "slot must be 0..3" }
        require(address % size == 0L) { "A $size-byte watchpoint must be $size-byte aligned" }
        command("set watchpoint", DebugProtocol.CMD_DEBUG_SET_WATCHPOINT,
            LE.bytes32(slot.toLong()) + LE.bytes32(1) + LE.bytes32(DebugProtocol.lengthCode(size).toLong()) +
                LE.bytes32(type.wire.toLong()) + LE.bytes64(address), closesOnError = true)
        armedSlots += slot
    }

    suspend fun clearWatchpoint(slot: Int) {
        command("clear watchpoint", DebugProtocol.CMD_DEBUG_SET_WATCHPOINT,
            LE.bytes32(slot.toLong()) + LE.bytes32(0) + LE.bytes32(0) + LE.bytes32(0) + LE.bytes64(0), closesOnError = true)
        armedSlots -= slot
    }

    /**
     * CMD_DEBUG_CONTINUE (stop = 0) with the server's answer checked. Returns true only for `CMD_SUCCESS`; the
     * server answers `CMD_ERROR` without closing the connection when `PT_CONTINUE` fails, e.g. because the process
     * is not stopped (debug.c `debug_continue_handle` returns 0). Throws [DebuggerException] when the request could
     * not be completed (session lost, timeout, connection dropped) — the game may then still be paused.
     */
    suspend fun resume(): Boolean {
        val st = command("continue", DebugProtocol.CMD_DEBUG_CONTINUE, LE.bytes32(0), closesOnError = false)
        val ok = st == Ps4DebugProtocol.CMD_SUCCESS
        if (ok) pendingStop = false
        else events.log(EventLevel.WARN, EventCategory.PROCESS, "debugger continue refused", "pid" to pid, "status" to Ps4DebugProtocol.statusName(st))
        return ok
    }

    /** Next stop within [timeoutMs], or null. A returned stop keeps the game paused until [resume] succeeds. */
    suspend fun nextEvent(timeoutMs: Int): InterruptEvent? {
        if (!alive) throw DebuggerSessionLostException()
        val ev = try {
            withContext(Dispatchers.IO) { channel.read(timeoutMs) }
        } catch (e: IOException) {
            throw DebuggerSessionLostException("The interrupt connection closed (${e.message}).")
        }
        if (ev != null) pendingStop = true
        return ev
    }

    /** Same as [release], for callers that do not need the report. */
    suspend fun detach() { release() }

    /**
     * Ends the session and makes sure the game runs again. Every step is checked; nothing is assumed:
     *  1. a stop that was read but not confirmed resumed gets a verified CONTINUE (retried once);
     *  2. armed watchpoints are cleared, then stops that arrived meanwhile are drained and resumed;
     *  3. DETACH — the server clears all debug registers and breakpoints and `PT_DETACH`es, which also resumes a
     *     stopped process (debug.c `debug_full_teardown`);
     *  4. if the command connection was lost, the session may still exist on the PS4 (it only ends it when it
     *     notices the closed socket). The debug state there is global, so DETACH is sent again on a new
     *     connection: `CMD_SUCCESS` = released now, `CMD_ERROR` = no session left.
     * Idempotent; never throws; bounded by [timeoutMs]. Runs even when the caller was cancelled.
     */
    suspend fun release(timeoutMs: Long = RELEASE_TIMEOUT_MS): ReleaseReport = withContext(NonCancellable) {
        released?.let { return@withContext it }
        val notes = ArrayList<String>()
        var continued: Boolean? = null
        var cleared: Boolean? = null
        var detached = false
        var recovered = false
        val done = withTimeoutOrNull(timeoutMs) {
            if (alive && pendingStop) continued = tryResume(notes)
            if (alive && armedSlots.isNotEmpty()) {
                cleared = try {
                    for (slot in armedSlots.toList()) clearWatchpoint(slot)
                    true
                } catch (e: Exception) { notes += "clear watchpoint: ${e.message}"; false }
            }
            // A hit between the last CONTINUE and clearing the watchpoint leaves the game stopped: drain and resume.
            if (alive) drainStops(notes)?.let { continued = it }
            if (alive) {
                try {
                    val st = command("detach", DebugProtocol.CMD_DEBUG_DETACH, ByteArray(0), closesOnError = true, throwOnError = false)
                    detached = true
                    if (st != Ps4DebugProtocol.CMD_SUCCESS) notes += "no session left on the PS4 at detach (${Ps4DebugProtocol.statusName(st)})"
                } catch (e: Exception) { notes += "detach: ${e.message}" }
            }
            if (!detached) {
                // Connection lost or re-established: release from whatever connection the client has now.
                try {
                    val st = t.exclusive("debug recover", COMMAND_TIMEOUT_MS) { w ->
                        w.send(w.header(DebugProtocol.CMD_DEBUG_DETACH, 0))
                        w.readU32().also { if (it != Ps4DebugProtocol.CMD_SUCCESS) w.invalidate() }
                    }
                    detached = true
                    recovered = st == Ps4DebugProtocol.CMD_SUCCESS
                    notes += if (recovered) "session released from a new connection" else "the PS4 had already ended the session"
                } catch (e: Exception) { notes += "could not reach the PS4 to release the session: ${e.message}" }
            }
            true
        }
        if (done == null) notes += "release did not finish within ${timeoutMs / 1000} s"
        close()
        val report = ReleaseReport(cleared, continued, detached, recovered, notes)
        released = report
        events.log(
            if (report.confirmed) EventLevel.INFO else EventLevel.WARN, EventCategory.PROCESS, "debugger released",
            "pid" to pid, "detached" to detached, "recovered" to recovered, "continued" to continued, "cleared" to cleared,
            "notes" to notes.joinToString(" | "),
        )
        report
    }

    private suspend fun tryResume(notes: MutableList<String>): Boolean = try {
        resume() || run { delay(RESUME_RETRY_DELAY_MS); resume() }.also { if (!it) notes += "CONTINUE refused twice" }
    } catch (e: Exception) { notes += "continue: ${e.message}"; false }

    /** Reads stops that are already queued and resumes each one. Returns null when there was none. */
    private suspend fun drainStops(notes: MutableList<String>): Boolean? {
        var result: Boolean? = null
        repeat(MAX_DRAIN) {
            val ev = try { nextEvent(DRAIN_POLL_MS) } catch (e: Exception) { return result }
            if (ev == null) return result
            result = tryResume(notes)
            if (result == false) return result
        }
        return result
    }

    override fun close() {
        closed = true
        channel.close()
        listener.close()
    }

    /**
     * One debugger command on the connection that owns the session. Never sent on a re-established connection
     * (that one has no session; a reconnect happens inside the client before the block runs, so the epoch is
     * checked inside). [closesOnError]: the server's handler returns 1 for this command's errors and closes the
     * connection. Returns the status word.
     */
    private suspend fun command(what: String, cmd: Int, body: ByteArray, closesOnError: Boolean, throwOnError: Boolean = true): Long {
        if (!alive) throw DebuggerSessionLostException()
        val st = try {
            t.exclusive(what, COMMAND_TIMEOUT_MS) { w ->
                if (t.connectionEpoch != epoch) throw SessionMovedAbort()
                w.send(w.header(cmd, body.size), body)
                w.readU32().also { if (it != Ps4DebugProtocol.CMD_SUCCESS && closesOnError) w.invalidate() }
            }
        } catch (e: SessionMovedAbort) {
            throw DebuggerSessionLostException()
        } catch (e: com.ps4smartcheat.core.backend.ConnectionInterruptedException) {
            throw DebuggerSessionLostException("The connection dropped during $what; the PS4 ends the debug session when it notices.")
        }
        if (st != Ps4DebugProtocol.CMD_SUCCESS && throwOnError && cmd != DebugProtocol.CMD_DEBUG_CONTINUE) {
            throw DebuggerException("PS4 rejected $what: ${Ps4DebugProtocol.statusName(st)}", st)
        }
        return st
    }

    private class SessionMovedAbort : com.ps4smartcheat.core.protocol.InSyncAbort("connection re-established")

    companion object {
        /**
         * Attaches to [pid]. [listener] must already be open. [announce] is the IPv4 address the PS4 connects to
         * (default: this device's address on the command connection).
         *
         * The request form follows the payload's capability level (CMD_BRANDING, ps4debug-NG `capability.h`):
         * level ≥ 1.2 → 12-byte body with [InterruptListener.port] (high port, no root); below → legacy 8-byte body,
         * which makes the payload connect to 755, so it is only sent when [InterruptListener.legacyPort] is open.
         * Otherwise nothing is sent: an attach whose connect-back fails leaves the game attached on the PS4.
         */
        suspend fun attach(
            t: ServerScanTransport,
            pid: Int,
            listener: InterruptListener,
            announce: InetAddress? = null,
            acceptTimeoutMs: Int = 5000,
            events: EventSink = EventSink.NONE,
        ): DebugSession {
            if (!t.capabilities.isNg) { listener.close(); throw DebuggerException("Watchpoints need ps4debug-NG.") }
            val ip = (announce ?: t.localAddress) as? Inet4Address
                ?: run { listener.close(); throw DebuggerException("No IPv4 address to announce for the interrupt connection") }
            val extended = t.capabilities.attachPort
            if (!extended && listener.legacyPort == null) {
                listener.close()
                throw DebuggerUnsupportedException(unsupportedPayload(t.capabilities.capabilityLevel))
            }
            val body = if (extended) DebugProtocol.attachBody(pid, ip.address, listener.port) else LE.bytes32(pid.toLong()) + ip.address
            val connectPort = if (extended) listener.port else listener.legacyPort!!
            val epoch = t.connectionEpoch
            try {
                t.exclusive("debug attach") { w ->
                    w.send(w.header(DebugProtocol.CMD_DEBUG_ATTACH, body.size), body)
                    val st = w.readU32()
                    if (st != Ps4DebugProtocol.CMD_SUCCESS) {
                        w.invalidate() // the attach handler returns 1 on failure: the server closes the connection
                        throw DebuggerException(
                            when (st) {
                                Ps4DebugProtocol.CMD_ALREADY_DEBUG -> "Another debugger session is active on the PS4 (another tool, or a session that did not end). Close it or reload ps4debug-NG."
                                else -> attachFailure(ip.hostAddress ?: "?", connectPort, Ps4DebugProtocol.statusName(st))
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
            } catch (e: Throwable) {
                // Attached on the PS4 but no event channel here (or the caller was cancelled): end the session right
                // away, even when cancelled, so it does not stay on the PS4.
                withContext(NonCancellable) {
                    runCatching { t.exclusive("detach", COMMAND_TIMEOUT_MS) { w -> w.send(w.header(DebugProtocol.CMD_DEBUG_DETACH, 0)); w.readU32() } }
                }
                listener.close()
                if (e is IOException) throw BackendException("Attached, but the interrupt connection did not arrive: ${e.message}. Detached again.", e)
                throw e
            }
            events.log(EventLevel.INFO, EventCategory.PROCESS, "debugger attached", "pid" to pid, "interruptPort" to connectPort, "extended" to extended)
            return DebugSession(t, pid, listener, channel, epoch, events)
        }

        /** Read timeout for one debugger command: the game is paused while CONTINUE is outstanding. */
        const val COMMAND_TIMEOUT_MS = 3000
        const val RELEASE_TIMEOUT_MS = 15_000L
        const val RESUME_RETRY_DELAY_MS = 50L
        private const val DRAIN_POLL_MS = 100
        private const val MAX_DRAIN = 16

        /** Explains a refused or failed attach (after the payload may already have attached to the game). */
        internal fun attachFailure(ip: String, port: Int, status: String): String =
            "The PS4 could not attach to the game or could not connect back to this device ($ip:$port; $status). " +
                "Phone and PS4 must be on the same network. Restart the game before trying again: after a failed " +
                "connection the payload may leave it attached."

        /** Why nothing was sent to a payload below capability level 1.2 (checked before attaching). */
        internal fun unsupportedPayload(level: String?): String =
            "This ps4debug-NG reports capability level ${level ?: "(none)"}. Watchpoints need level " +
                "${com.ps4smartcheat.core.model.CapabilityLevel.DEBUG_ATTACH_PORT} or newer (configurable interrupt port, " +
                "Pharaoh2k/ps4debug-NG PR #1): older builds connect back on port ${DebugProtocol.DEFAULT_INTERRUPT_PORT}, " +
                "which Android apps cannot open. Nothing was attached — load a newer ps4debug-NG."

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

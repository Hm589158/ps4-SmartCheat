package com.ps4smartcheat.core.protocol

import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.CommandFailedException
import com.ps4smartcheat.core.backend.ConnectionInterruptedException
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.logging.EventLevel
import com.ps4smartcheat.core.logging.EventSink
import com.ps4smartcheat.core.logging.log
import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.backend.NotConnectedException
import com.ps4smartcheat.core.backend.ProtocolException
import com.ps4smartcheat.core.backend.Traffic
import com.ps4smartcheat.core.backend.WriteStatus
import com.ps4smartcheat.core.model.ConsoleInfo
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.ProcessEntry
import com.ps4smartcheat.core.model.ProcessInfo
import com.ps4smartcheat.core.model.ServerCapabilities
import com.ps4smartcheat.core.protocol.Ps4DebugProtocol as P
import com.ps4smartcheat.core.util.LE
import com.ps4smartcheat.core.util.cString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

/**
 * Client for ps4debug-NG (primary backend) over the ps4debug TCP protocol (port 744). The base command set is
 * shared with classic/GoldHEN ps4debug, which is supported in compatibility mode. On connect the client
 * identifies the payload (CMD_BRANDING) and, for ps4debug-NG, performs the CMD_PROC_AUTH challenge–response
 * automatically (again after every reconnect) to unlock server-side scanning.
 *
 * Important server behaviours (verified in ps4debug-NG server.c/proc.c):
 *  - When a handler fails it usually returns 1 and the server closes the connection. This client
 *    therefore drops the socket after a failure status and transparently reconnects on the next request.
 *  - CMD_PROC_READ never reports errors: unreadable pages are returned as zero bytes.
 *  - CMD_PROC_LIST answers CMD_DATA_NULL when there are no processes.
 */
class Ps4DebugClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val socketFactory: () -> Socket = { Socket() },
    /** Maximum bytes requested per CMD_PROC_READ round-trip. */
    val maxReadChunk: Int = 1 shl 20,
    /** Structured diagnostics (connection lifecycle, failures, reconnects). */
    @Volatile var events: EventSink = EventSink.NONE,
) : DebugBackend, ServerScanTransport {

    override val name: String get() = if (capabilities.isNg) "ps4debug-NG" else "ps4debug"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var options: ConnectOptions? = null
    private var consoleInfo: ConsoleInfo? = null
    /** Set when the server closed the session after an error status; the next request reconnects. */
    private var reconnectPending = false
    @Volatile private var userDisconnecting = false

    @Volatile private var lastActivity = System.currentTimeMillis()

    /** Number of successful automatic reconnects since creation. */
    val reconnectCount = java.util.concurrent.atomic.AtomicInteger()

    override fun idleMillis(): Long = System.currentTimeMillis() - lastActivity

    private val bytesIn = AtomicLong()
    private val bytesOut = AtomicLong()
    private val epoch = AtomicLong()

    /** Incremented whenever a new TCP session is opened; server-side scan sessions die with the old one. */
    override val connectionEpoch: Long get() = epoch.get()

    override val capabilities: ServerCapabilities get() = consoleInfo?.capabilities ?: ServerCapabilities.NONE

    override fun traffic(): Traffic = Traffic(bytesIn.get(), bytesOut.get())

    override suspend fun connect(options: ConnectOptions): ConsoleInfo = withContext(ioDispatcher) {
        mutex.withLock {
            closeSocketQuietly()
            userDisconnecting = false
            _state.value = ConnectionState.Connecting(options.host, options.port)
            try {
                val info = openSession(options, probe = true)
                this@Ps4DebugClient.options = options
                consoleInfo = info
                reconnectPending = false
                _state.value = ConnectionState.Connected(info)
                events.log(
                    EventLevel.INFO, EventCategory.CONNECTION, "connected",
                    "host" to info.host, "port" to info.port, "version" to info.debuggerVersion,
                    "brand" to info.capabilities.brand, "ng" to info.capabilities.isNg,
                    "scanAuth" to info.capabilities.scanAuthorized, "turbo" to info.capabilities.turboResident,
                    "fw" to info.firmwareText,
                )
                info
            } catch (e: Exception) {
                closeSocketQuietly()
                val be = translate(e, "connect")
                events.log(EventLevel.ERROR, EventCategory.CONNECTION, "connect failed", "host" to options.host, "port" to options.port, "error" to be.message)
                _state.value = ConnectionState.Failed(be.message ?: "Connection failed")
                throw be
            }
        }
    }

    override suspend fun disconnect() {
        userDisconnecting = true
        // Closing first unblocks any request that is stuck in a blocking read.
        val s = socket
        if (s != null && !mutex.isLocked) {
            // Polite goodbye; the server closes the client on CMD_CONSOLE_END.
            runCatching { s.getOutputStream().write(header(P.CMD_CONSOLE_END, 0)); s.getOutputStream().flush() }
        }
        closeSocketQuietly()
        mutex.withLock {
            closeSocketQuietly()
            options = null
            consoleInfo = null
            reconnectPending = false
            _state.value = ConnectionState.Disconnected
        }
    }

    override suspend fun getProcesses(): List<ProcessEntry> = request("process list") {
        send(header(P.CMD_PROC_LIST, 0))
        val status = readStatus()
        if (status == P.CMD_DATA_NULL) {
            // No processes: server replies DATA_NULL and closes the connection.
            reconnectPending = true
            closeSocketQuietly()
            return@request emptyList()
        }
        expectSuccess(P.CMD_PROC_LIST, status)
        val count = readU32()
        if (count < 0 || count > MAX_PROCESSES) throw ProtocolException("Implausible process count $count")
        val data = readExactly(count.toInt() * P.PROC_LIST_ENTRY_SIZE)
        parseProcessList(data, count.toInt())
    }

    override suspend fun getProcessInfo(pid: Int): ProcessInfo = request("process info") {
        val body = LE.buffer(P.CMD_PROC_INFO_PACKET_SIZE).putInt(pid).array()
        send(header(P.CMD_PROC_INFO, body.size), body)
        expectSuccess(P.CMD_PROC_INFO, readStatus())
        parseProcessInfo(readExactly(P.PROC_INFO_RESPONSE_SIZE))
    }

    override suspend fun getMemoryRegions(pid: Int): List<MemoryRegion> = request("memory map") {
        val body = LE.buffer(P.CMD_PROC_MAPS_PACKET_SIZE).putInt(pid).array()
        send(header(P.CMD_PROC_MAPS, body.size), body)
        expectSuccess(P.CMD_PROC_MAPS, readStatus())
        val count = readU32()
        if (count < 0 || count > MAX_MAP_ENTRIES) throw ProtocolException("Implausible memory map entry count $count")
        parseMaps(readExactly(count.toInt() * P.PROC_MAP_ENTRY_SIZE), count.toInt())
    }

    override suspend fun readMemory(pid: Int, address: Long, length: Int): ByteArray {
        require(length >= 0) { "length must be >= 0" }
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = minOf(maxReadChunk, length - done)
            request("memory read") {
                val body = LE.buffer(P.CMD_PROC_READ_PACKET_SIZE)
                    .putInt(pid).putLong(address + done).putInt(n).array()
                send(header(P.CMD_PROC_READ, body.size), body)
                expectSuccess(P.CMD_PROC_READ, readStatus())
                readFully(out, done, n)
            }
            done += n
        }
        return out
    }

    override suspend fun writeMemory(pid: Int, address: Long, data: ByteArray): WriteStatus = request("memory write", idempotent = false) {
        val body = LE.buffer(P.CMD_PROC_WRITE_PACKET_SIZE).putInt(pid).putLong(address).putInt(data.size).array()
        send(header(P.CMD_PROC_WRITE, body.size), body)
        val ack = readStatus()
        expectSuccess(P.CMD_PROC_WRITE, ack)
        send(data)
        val final = readStatus()
        if (final == P.CMD_SUCCESS) {
            WriteStatus.Accepted
        } else {
            // ps4debug-NG reports CMD_ERROR when its own read-back does not match. The session stays open
            // in that case (handler returns 0), so no reconnect is needed.
            WriteStatus.Rejected(final)
        }
    }

    /**
     * Runs a multi-step exchange (server-side scans) with exclusive access to the connection.
     * Same error handling as normal requests; [readTimeoutMs] temporarily replaces the socket timeout.
     */
    override suspend fun <T> exclusive(what: String, readTimeoutMs: Int?, block: (Wire) -> T): T = request(what, idempotent = false) {
        val s = socket ?: throw NotConnectedException()
        val saved = s.soTimeout
        if (readTimeoutMs != null) s.soTimeout = readTimeoutMs
        try {
            block(wire)
        } finally {
            runCatching { if (!s.isClosed) s.soTimeout = saved }
        }
    }

    override val serverScanTimeoutMs: Int get() = options?.serverScanTimeoutMs ?: 600_000

    /**
     * Sends one command on a separate, short-lived connection (e.g. Turbo CANCEL while the main
     * connection is busy). ps4debug-NG's scan authorisation is server-global, so no handshake is needed.
     * @return the status word, or null if the side channel failed.
     */
    override fun sendSideChannel(cmd: Int, body: ByteArray): Long? {
        val opts = options ?: return null
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 3000
                s.connect(InetSocketAddress(InetAddress.getByName(opts.host.trim()), opts.port), opts.connectTimeoutMs)
                val out = s.getOutputStream()
                out.write(header(cmd, body.size)); out.write(body); out.flush()
                bytesOut.addAndGet((P.CMD_PACKET_SIZE + body.size).toLong())
                val st = ByteArray(4)
                var n = 0
                while (n < 4) { val r = s.getInputStream().read(st, n, 4 - n); if (r < 0) return null; n += r }
                bytesIn.addAndGet(4)
                runCatching { out.write(header(P.CMD_CONSOLE_END, 0)); out.flush() }
                LE.u32(st)
            }
        } catch (e: IOException) {
            null
        }
    }

    private val wire = object : Wire {
        override fun send(vararg parts: ByteArray) = this@Ps4DebugClient.send(*parts)
        override fun readFully(dst: ByteArray, off: Int, len: Int) = this@Ps4DebugClient.readFully(dst, off, len)
        override fun readExactly(len: Int): ByteArray = this@Ps4DebugClient.readExactly(len)
        override fun readU32(): Long = this@Ps4DebugClient.readU32()
        override fun readU64(): Long = LE.i64(readExactly(8))
        override fun header(cmd: Int, dataLen: Int): ByteArray = this@Ps4DebugClient.header(cmd, dataLen)
        override fun expectSuccess(cmd: Int, status: Long) = this@Ps4DebugClient.expectSuccess(cmd, status)
        /** Marks the session as unusable so the next request reconnects (e.g. after an aborted stream). */
        override fun invalidate() { reconnectPending = true; closeSocketQuietly() }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Runs one exchange under the connection mutex.
     *
     * Connection loss (EOF, reset, timeout, desynchronised stream) triggers automatic reconnection when
     * enabled. An [idempotent] request (reads, lists, maps, info) is then repeated once; a non-idempotent one
     * (writes, server scans) is never repeated and fails with [ConnectionInterruptedException] while the
     * connection itself is restored.
     */
    /*
     * The lock is taken and released on the IO dispatcher. (It used to be taken on the caller's dispatcher and
     * released only after resuming there: a request started from the UI thread kept every other request waiting
     * until the UI thread got to run that resumption.)
     */
    private suspend fun <T> request(what: String, idempotent: Boolean = true, block: () -> T): T = withContext(ioDispatcher) {
        mutex.withLock {
            val opts = options ?: throw NotConnectedException()
            if (reconnectPending || socket == null) {
                if (userDisconnecting) throw NotConnectedException()
                try {
                    openSession(opts, probe = false)
                    reconnectPending = false
                } catch (e: Exception) {
                    if (!(opts.autoReconnect && reconnect(opts, "session reopen failed: ${e.message}"))) {
                        failConnection(translate(e, "reconnect"))
                    }
                }
            }
            try {
                block().also { lastActivity = System.currentTimeMillis() }
            } catch (e: InSyncAbort) {
                throw e
            } catch (e: CommandFailedException) {
                // The server closes the session after most failure statuses.
                reconnectPending = true
                closeSocketQuietly()
                events.log(EventLevel.WARN, EventCategory.PROTOCOL, "command rejected", "op" to what, "status" to P.statusName(e.status))
                throw e
            } catch (e: Exception) {
                if (userDisconnecting) throw NotConnectedException()
                val be = translate(e, what)
                events.log(EventLevel.WARN, EventCategory.CONNECTION, "connection lost", "op" to what, "error" to be.message)
                if (!opts.autoReconnect || !reconnect(opts, be.message ?: "connection lost")) failConnection(be)
                if (!idempotent) {
                    throw ConnectionInterruptedException(
                        "The connection dropped during $what and was re-established. The operation was not repeated automatically.", e,
                    )
                }
                try {
                    block().also { lastActivity = System.currentTimeMillis() }
                } catch (e2: InSyncAbort) {
                    throw e2
                } catch (e2: CommandFailedException) {
                    reconnectPending = true
                    closeSocketQuietly()
                    throw e2
                } catch (e2: Exception) {
                    // Failed again right after reconnecting: give up instead of looping.
                    failConnection(translate(e2, what))
                }
            }
        }
    }

    /** Blocking reconnect with exponential backoff; updates [state]. @return true when a new session is open. */
    private fun reconnect(opts: ConnectOptions, reason: String): Boolean {
        closeSocketQuietly()
        val max = opts.reconnectAttempts.coerceIn(1, 10)
        for (attempt in 1..max) {
            if (userDisconnecting) return false
            _state.value = ConnectionState.Reconnecting(opts.host, opts.port, attempt, max, reason)
            Thread.sleep(opts.reconnectBackoffMs.coerceIn(0, 10_000) shl (attempt - 1))
            if (userDisconnecting) return false
            try {
                openSession(opts, probe = false)
                reconnectPending = false
                reconnectCount.incrementAndGet()
                consoleInfo?.let { _state.value = ConnectionState.Connected(it) }
                events.log(EventLevel.INFO, EventCategory.CONNECTION, "reconnected", "attempt" to attempt, "reason" to reason)
                return true
            } catch (e: Exception) {
                closeSocketQuietly()
                events.log(EventLevel.WARN, EventCategory.CONNECTION, "reconnect attempt failed", "attempt" to attempt, "error" to e.message)
            }
        }
        return false
    }

    /** Keep-alive: CMD_VERSION is answered by every ps4debug variant without side effects. */
    override suspend fun ping() {
        request("keep-alive") {
            send(header(P.CMD_VERSION, 0))
            val len = readU32()
            if (len < 0 || len > 256) throw ProtocolException("Invalid CMD_VERSION length $len")
            readExactly(len.toInt())
        }
    }

    private fun failConnection(e: BackendException): Nothing {
        closeSocketQuietly()
        events.log(EventLevel.ERROR, EventCategory.CONNECTION, "connection failed", "error" to e.message)
        options = null
        _state.value = ConnectionState.Failed(e.message ?: "Connection lost")
        throw e
    }

    private fun openSession(options: ConnectOptions, probe: Boolean): ConsoleInfo {
        NetworkPolicy.validateHostSyntax(options.host)?.let { throw BackendException(it) }
        NetworkPolicy.validatePort(options.port)?.let { throw BackendException(it) }
        val addr = InetAddress.getByName(options.host.trim())
        if (!options.allowNonLocalHosts && !NetworkPolicy.isLocal(addr)) {
            throw BackendException(
                "${addr.hostAddress} is not a local-network address. ps4debug is unauthenticated; " +
                    "only private/link-local addresses are allowed (see Settings).",
            )
        }
        val version = openRaw(options, addr)
        if (!probe) {
            val prev = consoleInfo
            // The NG scan authorisation is global on the server, but a payload restart clears it: re-auth cheaply.
            if (prev != null && prev.capabilities.scanAuthorized) {
                if (!runCatching { authenticate(options) }.getOrDefault(false)) {
                    closeSocketQuietly()
                    openRaw(options, addr)
                    consoleInfo = prev.copy(capabilities = prev.capabilities.copy(scanAuthorized = false))
                }
            }
            return consoleInfo ?: ConsoleInfo(options.host.trim(), options.port, version, null)
        }

        var firmware: Int? = null
        if (options.queryFirmware) {
            firmware = probeOptional(options, addr) { readU16() }?.let { fw ->
                if (fw == P.FW_UNKNOWN || fw == 0) null else fw.takeIf { it / 100 in 1..99 }
            }
        }
        var caps = ServerCapabilities.NONE
        if (options.probeServerScan) {
            val branding = probeOptional(options, addr, NgProtocol.CMD_BRANDING) {
                val len = readU32()
                if (len < 0 || len > 1024) throw ProtocolException("Invalid branding length $len")
                readExactly(len.toInt())
            }
            if (branding != null) {
                val nul = branding.indexOf(0)
                val brand = String(branding, 0, if (nul >= 0) nul else branding.size, Charsets.US_ASCII).trim()
                val level = if (nul >= 0) String(branding, nul + 1, branding.size - nul - 1, Charsets.US_ASCII).trim() else null
                caps = ServerCapabilities(brand = brand.ifEmpty { null }, capabilityLevel = level?.ifEmpty { null })
            }
            // Server-side scanning is only attempted on an identified ps4debug-NG build: other payloads use
            // overlapping opcodes (0xBDAACCxx) with unknown semantics.
            if (caps.isNg) {
                val authed = try { authenticate(options) } catch (e: IOException) { false }
                if (!authed) { closeSocketQuietly(); openRaw(options, addr) }
                caps = caps.copy(scanAuthorized = authed)
                if (authed) {
                    val turbo = probeOptional(options, addr, NgProtocol.TURBO_CAPS) {
                        val st = readU32()
                        if (st != P.CMD_SUCCESS) throw ProtocolException("CAPS status")
                        val b = readExactly(16)
                        LE.i32(b, 4)
                    }
                    caps = caps.copy(turboEngines = turbo)
                }
            }
        }
        return ConsoleInfo(options.host.trim(), options.port, version, firmware, caps)
    }

    /** Opens the TCP session and performs CMD_VERSION. */
    private fun openRaw(options: ConnectOptions, addr: InetAddress): String {
        val s = socketFactory()
        s.tcpNoDelay = true
        s.soTimeout = options.readTimeoutMs
        s.connect(InetSocketAddress(addr, options.port), options.connectTimeoutMs)
        socket = s
        input = CountingInput(s.getInputStream(), bytesIn).buffered(64 * 1024)
        output = CountingOutput(s.getOutputStream(), bytesOut)
        epoch.incrementAndGet()

        // CMD_VERSION: no status word; u32 length + ASCII string.
        send(header(P.CMD_VERSION, 0))
        val len = readU32()
        if (len < 0 || len > 256) throw ProtocolException("Invalid CMD_VERSION length $len — is this a ps4debug server?")
        return String(readExactly(len.toInt()), Charsets.US_ASCII).trimEnd('\u0000')
    }

    /**
     * Sends an optional extension command. A server that lacks it may stay silent (timeout) or close the
     * connection; in both cases the session is reopened so a late reply can never desynchronise the stream.
     */
    private fun <T> probeOptional(options: ConnectOptions, addr: InetAddress, cmd: Int = P.CMD_FW_VERSION, read: () -> T): T? {
        val s = socket ?: return null
        return try {
            send(header(cmd, 0))
            s.soTimeout = options.firmwareProbeTimeoutMs
            read().also { s.soTimeout = options.readTimeoutMs }
        } catch (e: IOException) {
            closeSocketQuietly(); openRaw(options, addr); null
        } catch (e: ProtocolException) {
            closeSocketQuietly(); openRaw(options, addr); null
        }
    }

    /** ps4debug-NG CMD_PROC_AUTH handshake requesting the scan bit. */
    private fun authenticate(options: ConnectOptions): Boolean {
        val s = socket ?: return false
        val body = LE.buffer(8).putInt(NgProtocol.AUTH_MAGIC).putInt(NgProtocol.AUTH_FLAG_SCAN).array()
        send(header(NgProtocol.CMD_PROC_AUTH, body.size), body)
        s.soTimeout = options.firmwareProbeTimeoutMs.coerceAtLeast(1000)
        try {
            if (readStatus() != P.CMD_SUCCESS) return false
            val chlen = readU16()
            if (chlen <= 0 || chlen > 1024) return false
            val challenge = readExactly(chlen)
            send(NgProtocol.authResponse(challenge))
            return readStatus() == P.CMD_SUCCESS
        } finally {
            runCatching { s.soTimeout = options.readTimeoutMs }
        }
    }

    private fun header(cmd: Int, dataLen: Int): ByteArray =
        LE.buffer(P.CMD_PACKET_SIZE).putInt(P.PACKET_MAGIC).putInt(cmd).putInt(dataLen).array()

    private fun send(vararg parts: ByteArray) {
        val out = output ?: throw NotConnectedException()
        for (p in parts) out.write(p)
        out.flush()
    }

    private fun readFully(dst: ByteArray, off: Int, len: Int) {
        val inp = input ?: throw NotConnectedException()
        var n = 0
        while (n < len) {
            val r = inp.read(dst, off + n, len - n)
            if (r < 0) throw EOFException("Connection closed by the PS4 (expected ${len - n} more bytes)")
            n += r
        }
    }

    private fun readExactly(len: Int): ByteArray = ByteArray(len).also { readFully(it, 0, len) }
    private fun readU32(): Long = LE.u32(readExactly(4))
    private fun readU16(): Int = LE.u16(readExactly(2))
    private fun readStatus(): Long = readU32()

    private fun expectSuccess(cmd: Int, status: Long) {
        if (status != P.CMD_SUCCESS) {
            if (status ushr 28 != 0xFL) {
                throw ProtocolException("Unexpected status word 0x${status.toString(16)} — stream out of sync")
            }
            throw CommandFailedException(
                cmd.toLong() and 0xFFFFFFFFL, status,
                "PS4 rejected ${commandName(cmd)}: ${P.statusName(status)}",
            )
        }
    }

    private fun closeSocketQuietly() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun translate(e: Exception, what: String): BackendException = when (e) {
        is BackendException -> e
        is SocketTimeoutException -> BackendException("Timed out during $what. Check the PS4 is on and ps4debug is loaded.", e)
        is UnknownHostException -> BackendException("Unknown host: ${e.message}", e)
        is java.net.ConnectException -> BackendException("Connection refused during $what — is the ps4debug payload running?", e)
        is EOFException -> BackendException("The PS4 closed the connection during $what.", e)
        is IOException -> BackendException("Network error during $what: ${e.message}", e)
        else -> BackendException("Unexpected error during $what: ${e.message}", e)
    }

    private class CountingInput(inp: InputStream, private val counter: AtomicLong) : FilterInputStream(inp) {
        override fun read(): Int = super.read().also { if (it >= 0) counter.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) counter.addAndGet(it.toLong()) }
    }

    private class CountingOutput(out: OutputStream, private val counter: AtomicLong) : FilterOutputStream(out) {
        override fun write(b: Int) { out.write(b); counter.incrementAndGet() }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); counter.addAndGet(len.toLong()) }
    }

    companion object {
        const val MAX_PROCESSES = 4096L
        const val MAX_MAP_ENTRIES = 1_000_000L

        fun commandName(cmd: Int): String = when (cmd) {
            P.CMD_PROC_LIST -> "CMD_PROC_LIST"
            P.CMD_PROC_READ -> "CMD_PROC_READ"
            P.CMD_PROC_WRITE -> "CMD_PROC_WRITE"
            P.CMD_PROC_MAPS -> "CMD_PROC_MAPS"
            P.CMD_PROC_INFO -> "CMD_PROC_INFO"
            else -> "0x" + (cmd.toLong() and 0xFFFFFFFFL).toString(16).uppercase()
        }

        fun parseProcessList(data: ByteArray, count: Int): List<ProcessEntry> = List(count) { i ->
            val off = i * P.PROC_LIST_ENTRY_SIZE
            ProcessEntry(pid = LE.i32(data, off + 32), name = data.cString(off, 32))
        }

        fun parseMaps(data: ByteArray, count: Int): List<MemoryRegion> = List(count) { i ->
            val off = i * P.PROC_MAP_ENTRY_SIZE
            MemoryRegion(
                name = data.cString(off, 32),
                start = LE.i64(data, off + 32),
                end = LE.i64(data, off + 40),
                offset = LE.i64(data, off + 48),
                prot = LE.u16(data, off + 56),
            )
        }

        fun parseProcessInfo(d: ByteArray): ProcessInfo = ProcessInfo(
            pid = LE.i32(d, 0),
            name = d.cString(4, 40),
            path = d.cString(44, 64),
            titleId = d.cString(108, 16),
            contentId = d.cString(124, 64),
        )
    }
}

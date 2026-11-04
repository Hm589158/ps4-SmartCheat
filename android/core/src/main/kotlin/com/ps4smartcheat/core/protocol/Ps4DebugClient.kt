package com.ps4smartcheat.core.protocol

import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.CommandFailedException
import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.backend.NotConnectedException
import com.ps4smartcheat.core.backend.ProtocolException
import com.ps4smartcheat.core.backend.WriteStatus
import com.ps4smartcheat.core.model.ConsoleInfo
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.ProcessEntry
import com.ps4smartcheat.core.model.ProcessInfo
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Client for the standard ps4debug TCP protocol (port 744), compatible with GoldHEN's ps4debug
 * and ps4debug-NG for the command subset used here.
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
) : DebugBackend {

    override val name: String = "ps4debug"

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

    override suspend fun connect(options: ConnectOptions): ConsoleInfo = mutex.withLock {
        withContext(ioDispatcher) {
            closeSocketQuietly()
            userDisconnecting = false
            _state.value = ConnectionState.Connecting(options.host, options.port)
            try {
                val info = openSession(options, probeFirmware = options.queryFirmware)
                this@Ps4DebugClient.options = options
                consoleInfo = info
                reconnectPending = false
                _state.value = ConnectionState.Connected(info)
                info
            } catch (e: Exception) {
                closeSocketQuietly()
                val be = translate(e, "connect")
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

    override suspend fun writeMemory(pid: Int, address: Long, data: ByteArray): WriteStatus = request("memory write") {
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

    // ---------------------------------------------------------------------------------------------

    private suspend fun <T> request(what: String, block: () -> T): T = mutex.withLock {
        withContext(ioDispatcher) {
            val opts = options ?: throw NotConnectedException()
            if (reconnectPending || socket == null) {
                if (userDisconnecting) throw NotConnectedException()
                try {
                    openSession(opts, probeFirmware = false)
                    reconnectPending = false
                } catch (e: Exception) {
                    failConnection(translate(e, "reconnect"))
                }
            }
            try {
                block()
            } catch (e: CommandFailedException) {
                // The server closes the session after most failure statuses.
                reconnectPending = true
                closeSocketQuietly()
                throw e
            } catch (e: Exception) {
                if (userDisconnecting) throw NotConnectedException()
                failConnection(translate(e, what))
            }
        }
    }

    private fun failConnection(e: BackendException): Nothing {
        closeSocketQuietly()
        options = null
        _state.value = ConnectionState.Failed(e.message ?: "Connection lost")
        throw e
    }

    private fun openSession(options: ConnectOptions, probeFirmware: Boolean): ConsoleInfo {
        NetworkPolicy.validateHostSyntax(options.host)?.let { throw BackendException(it) }
        NetworkPolicy.validatePort(options.port)?.let { throw BackendException(it) }
        val addr = InetAddress.getByName(options.host.trim())
        if (!options.allowNonLocalHosts && !NetworkPolicy.isLocal(addr)) {
            throw BackendException(
                "${addr.hostAddress} is not a local-network address. ps4debug is unauthenticated; " +
                    "only private/link-local addresses are allowed (see Settings).",
            )
        }
        val s = socketFactory()
        s.tcpNoDelay = true
        s.soTimeout = options.readTimeoutMs
        s.connect(InetSocketAddress(addr, options.port), options.connectTimeoutMs)
        socket = s
        input = s.getInputStream().buffered(64 * 1024)
        output = s.getOutputStream()

        // CMD_VERSION: no status word; u32 length + ASCII string.
        send(header(P.CMD_VERSION, 0))
        val len = readU32()
        if (len < 0 || len > 256) throw ProtocolException("Invalid CMD_VERSION length $len — is this a ps4debug server?")
        val version = String(readExactly(len.toInt()), Charsets.US_ASCII).trimEnd('\u0000')

        var firmware: Int? = consoleInfo?.firmware
        if (probeFirmware) {
            firmware = probeFirmware(options)
        }
        return ConsoleInfo(options.host.trim(), options.port, version, firmware)
    }

    /**
     * CMD_FW_VERSION is an extension. A server that does not implement it sends nothing, so the probe
     * uses a short timeout; on timeout the session is reopened so a late reply cannot desynchronise
     * the stream.
     */
    private fun probeFirmware(options: ConnectOptions): Int? {
        val s = socket ?: return null
        send(header(P.CMD_FW_VERSION, 0))
        s.soTimeout = options.firmwareProbeTimeoutMs
        val fw = try {
            readU16()
        } catch (e: SocketTimeoutException) {
            null
        } finally {
            runCatching { s.soTimeout = options.readTimeoutMs }
        }
        if (fw == null) {
            closeSocketQuietly()
            openSession(options, probeFirmware = false)
            return null
        }
        if (fw == P.FW_UNKNOWN || fw == 0) return null
        val major = fw / 100
        val minor = fw % 100
        return if (major in 1..99 && minor in 0..99) fw else null
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

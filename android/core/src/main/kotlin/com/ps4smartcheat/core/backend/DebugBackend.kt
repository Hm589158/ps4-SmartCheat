package com.ps4smartcheat.core.backend

import com.ps4smartcheat.core.model.ConsoleInfo
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.ProcessEntry
import com.ps4smartcheat.core.model.ProcessInfo
import kotlinx.coroutines.flow.StateFlow

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val host: String, val port: Int) : ConnectionState
    data class Connected(val info: ConsoleInfo) : ConnectionState
    /** The link dropped; the client is re-establishing it automatically. */
    data class Reconnecting(val host: String, val port: Int, val attempt: Int, val maxAttempts: Int, val reason: String) : ConnectionState
    /** The connection ended unexpectedly. */
    data class Failed(val message: String) : ConnectionState
}

data class ConnectOptions(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val connectTimeoutMs: Int = 5_000,
    val readTimeoutMs: Int = 10_000,
    /** Probe the CMD_FW_VERSION extension (GoldHEN ps4debug / ps4debug-NG). */
    val queryFirmware: Boolean = true,
    val firmwareProbeTimeoutMs: Int = 1_500,
    /** Allow hosts outside private/link-local ranges. Off by default: ps4debug is unauthenticated. */
    val allowNonLocalHosts: Boolean = false,
    /** Probe CMD_BRANDING / ps4debug-NG auth / Turbo CAPS to enable server-side scanning. */
    val probeServerScan: Boolean = true,
    /** Read timeout while the PS4 scans (the server may be silent for a long time). */
    val serverScanTimeoutMs: Int = 600_000,
    /** Re-establish a dropped connection automatically (writes are never repeated). */
    val autoReconnect: Boolean = true,
    val reconnectAttempts: Int = 3,
    /** First retry delay; doubles per attempt. */
    val reconnectBackoffMs: Long = 500,
) {
    companion object {
        /** ps4debug command server port (server.h, PS4DBG_PORT). */
        const val DEFAULT_PORT = 744
    }
}

/** Outcome reported by the debugger for a write. */
sealed interface WriteStatus {
    /** Written; the caller verifies the content with a fresh read. */
    data object Accepted : WriteStatus
    /**
     * Written and read back on the PS4 itself, bytes equal (ps4debug-NG capability 1.1 `CMD_PROC_WRITE`, or a
     * `0xBDAACC04` entry status 0: proc.c `proc_write_mem_status`). That read-back is the fresh read: no second one
     * over the network is needed.
     */
    data object VerifiedOnConsole : WriteStatus
    data class Rejected(val statusCode: Long) : WriteStatus
}

/**
 * Abstraction over the PS4 debugging transport. All functions are main-safe and serialised internally.
 * Implementations must never return simulated data.
 */
interface DebugBackend {
    /** Smoothed round-trip time of small requests in ms (null when unknown). See Ps4DebugClient.linkMillis. */
    val linkMillis: StateFlow<Long?> get() = UNKNOWN_LINK
    val name: String
    val state: StateFlow<ConnectionState>

    suspend fun connect(options: ConnectOptions): ConsoleInfo
    suspend fun disconnect()

    suspend fun getProcesses(): List<ProcessEntry>
    suspend fun getProcessInfo(pid: Int): ProcessInfo
    suspend fun getMemoryRegions(pid: Int): List<MemoryRegion>

    /**
     * Reads [length] bytes. Note: ps4debug does not report read failures; unmapped memory is
     * returned as zeros. Callers must restrict reads to mapped, readable regions.
     */
    suspend fun readMemory(pid: Int, address: Long, length: Int): ByteArray

    suspend fun writeMemory(pid: Int, address: Long, data: ByteArray): WriteStatus

    /**
     * Writes several independent [entries] (address → bytes) of [pid] in one exchange (ps4debug-NG `0xBDAACC04`), each
     * read back on the PS4; one status per entry, in order. A failed entry does not stop the others (upstream: "best
     * effort and non-atomic"), so callers only batch writes that do not depend on each other. Null when unsupported.
     */
    suspend fun writeMany(pid: Int, entries: List<Pair<Long, ByteArray>>): List<WriteStatus>? = null

    /**
     * The [AobQuery.nth] match of each query in `[start, start + length)` of [pid], searched on the PS4 in one pass
     * (ps4debug-NG `CMD_PROC_SCAN_AOB_MULTI`, 6 threads); 0 where there are fewer matches. Null when unsupported.
     */
    suspend fun findPatterns(pid: Int, start: Long, length: Long, queries: List<AobQuery>): LongArray? = null

    /** Shows [text] as a notification on the PS4 (CMD_CONSOLE_NOTIFY); false when the payload is not known to support it. */
    suspend fun notify(text: String): Boolean = false

    /** The game in the foreground, when the payload can tell (ps4debug-NG); null otherwise or when there is none. */
    suspend fun foregroundApp(): com.ps4smartcheat.core.model.ForegroundApp? = null

    /** Total bytes received/sent on the debug connection(s) since creation. */
    fun traffic(): Traffic = Traffic(0, 0)

    /** Cheap liveness check (keep-alive); throws if the console cannot be reached. */
    suspend fun ping() {}

    /** Milliseconds since the last completed request (for idle detection). */
    fun idleMillis(): Long = 0
}

/** A byte pattern for [DebugBackend.findPatterns]: bytes where [fixed] is set must equal [values]; others are wildcards. */
class AobQuery(val values: ByteArray, val fixed: BooleanArray, val nth: Int) {
    init {
        require(values.isNotEmpty() && values.size == fixed.size) { "pattern and mask differ in length" }
        require(nth in 1..255) { "nth must be 1..255 (u8 on the wire)" }
    }
}

data class Traffic(val bytesIn: Long, val bytesOut: Long) {
    operator fun minus(o: Traffic) = Traffic(bytesIn - o.bytesIn, bytesOut - o.bytesOut)
    val total: Long get() = bytesIn + bytesOut
}

/** Thrown for connection/protocol problems. The connection is closed when this is thrown. */
open class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NotConnectedException : BackendException("Not connected to a PS4")

/**
 * The connection dropped during a non-repeatable operation (e.g. a write) and was re-established.
 * The operation was NOT repeated automatically; the caller decides what to do.
 */
class ConnectionInterruptedException(message: String, cause: Throwable? = null) : BackendException(message, cause)

class ProtocolException(message: String, cause: Throwable? = null) : BackendException(message, cause)

/** The debugger answered a command with a non-success status (e.g. 0xF0000001). */
open class CommandFailedException(val command: Long, val status: Long, message: String) : BackendException(message)

private val UNKNOWN_LINK: StateFlow<Long?> = kotlinx.coroutines.flow.MutableStateFlow(null)

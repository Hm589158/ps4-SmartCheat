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
) {
    companion object {
        /** ps4debug command server port (server.h, PS4DBG_PORT). */
        const val DEFAULT_PORT = 744
    }
}

/** Outcome reported by the debugger for a write. Content verification is done separately by the caller. */
sealed interface WriteStatus {
    data object Accepted : WriteStatus
    data class Rejected(val statusCode: Long) : WriteStatus
}

/**
 * Abstraction over the PS4 debugging transport. All functions are main-safe and serialised internally.
 * Implementations must never return simulated data.
 */
interface DebugBackend {
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

    /** Total bytes received/sent on the debug connection(s) since creation. */
    fun traffic(): Traffic = Traffic(0, 0)
}

data class Traffic(val bytesIn: Long, val bytesOut: Long) {
    operator fun minus(o: Traffic) = Traffic(bytesIn - o.bytesIn, bytesOut - o.bytesOut)
    val total: Long get() = bytesIn + bytesOut
}

/** Thrown for connection/protocol problems. The connection is closed when this is thrown. */
open class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NotConnectedException : BackendException("Not connected to a PS4")

class ProtocolException(message: String, cause: Throwable? = null) : BackendException(message, cause)

/** The debugger answered a command with a non-success status (e.g. 0xF0000001). */
class CommandFailedException(val command: Long, val status: Long, message: String) : BackendException(message)

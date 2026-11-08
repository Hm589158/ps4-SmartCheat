package com.ps4smartcheat.core.protocol

import com.ps4smartcheat.core.model.ServerCapabilities

/** Low-level framed access used by server-side scan strategies. Only valid inside [ServerScanTransport.exclusive]. */
interface Wire {
    fun send(vararg parts: ByteArray)
    fun readFully(dst: ByteArray, off: Int, len: Int)
    fun readExactly(len: Int): ByteArray
    fun readU32(): Long
    fun readU64(): Long
    fun header(cmd: Int, dataLen: Int): ByteArray
    fun expectSuccess(cmd: Int, status: Long)
    /** Forces a reconnect before the next request (used after aborting a stream midway). */
    fun invalidate()
}

/** Implemented by [Ps4DebugClient]; lets scan strategies run ps4debug-NG server-side scan exchanges. */
interface ServerScanTransport {
    val capabilities: ServerCapabilities
    val connectionEpoch: Long
    val serverScanTimeoutMs: Int
    suspend fun <T> exclusive(what: String, readTimeoutMs: Int? = null, block: (Wire) -> T): T
    fun sendSideChannel(cmd: Int, body: ByteArray): Long?
    /** This device's address on the command connection (what the PS4 sees); null when not connected. */
    val localAddress: java.net.InetAddress? get() = null
}

/**
 * Thrown from inside [ServerScanTransport.exclusive] when an exchange finished cleanly but its outcome is
 * unusable (e.g. the server could not keep results). The connection stays open and in sync.
 */
open class InSyncAbort(message: String) : Exception(message)

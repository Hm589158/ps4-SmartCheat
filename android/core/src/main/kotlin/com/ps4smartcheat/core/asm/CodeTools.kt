package com.ps4smartcheat.core.asm

import com.ps4smartcheat.core.backend.CommandFailedException
import com.ps4smartcheat.core.protocol.Ps4DebugProtocol
import com.ps4smartcheat.core.protocol.ServerScanTransport
import com.ps4smartcheat.core.util.LE
import java.io.EOFException
import java.io.IOException

/**
 * ps4debug-NG code commands. Verified in Pharaoh2k/ps4debug-ng v1.3.4 (see docs/PROTOCOL.md §Code tools):
 *  - `0xBDAA0024` assemble (debugger/source/assemble.c `proc_assemble_handle`, raw literal in proc.c `proc_handle`)
 *  - `CMD_PROC_FIND_XREFS_TO = 0xBDAA0022` (proc.c `proc_find_xrefs_to_handle`)
 */
object NgCodeProtocol {
    const val CMD_PROC_FIND_XREFS_TO: Int = 0xBDAA0022.toInt()
    const val CMD_PROC_ASSEMBLE: Int = 0xBDAA0024.toInt()

    /** `struct cmd_proc_assemble_packet { u64 base_addr; u32 ks_opt_syntax; }` */
    const val ASSEMBLE_HEADER_SIZE = 12
    /** `struct cmd_proc_xrefs_to_packet { u32 pid; u64 scan_address; u32 scan_length; u64 target_address; }` */
    const val XREFS_TO_PACKET_SIZE = 24
    /** Keystone KS_OPT_SYNTAX: 0 keeps the default (Intel). */
    const val SYNTAX_DEFAULT = 0
    const val STREAM_SENTINEL: Long = -1L
}

/** Machine code produced by the PS4's Keystone assembler. */
class Assembled(val bytes: ByteArray, val instructionCount: Int)

/** Keystone rejected the source text (`ks_errno` and its message from `ks_strerror`). */
class AssemblerException(val ksErrno: Long, val detail: String) :
    CommandFailedException(NgCodeProtocol.CMD_PROC_ASSEMBLE.toLong() and 0xFFFFFFFFL, Ps4DebugProtocol.CMD_ERROR, "Assembler error: $detail")

/** The connected debugger does not provide a code command (classic ps4debug, or an ps4debug-NG build without it). */
class CodeToolUnavailableException(message: String) :
    CommandFailedException(0, Ps4DebugProtocol.CMD_ERROR, message)

class CodeTools(private val transport: ServerScanTransport) {

    val available: Boolean get() = transport.capabilities.isNg

    /**
     * Assembles x86-64 [source] (Intel syntax, one instruction per line or separated by `;`) at [address] on the
     * PS4, so RIP-relative operands and branches are encoded for that location.
     */
    suspend fun assemble(source: String, address: Long): Assembled {
        requireNg("The assembler")
        val text = normalise(source)
        require(text.isNotBlank()) { "Enter at least one instruction" }
        val body = text.toByteArray(Charsets.US_ASCII)
        require(body.size <= MAX_SOURCE_BYTES) { "Assembly text is limited to $MAX_SOURCE_BYTES characters" }
        return transport.exclusive("assemble") { w ->
            w.send(
                w.header(NgCodeProtocol.CMD_PROC_ASSEMBLE, NgCodeProtocol.ASSEMBLE_HEADER_SIZE + body.size),
                LE.bytes64(address) + LE.bytes32(NgCodeProtocol.SYNTAX_DEFAULT.toLong()),
                body,
            )
            val status = w.readU32()
            if (status == Ps4DebugProtocol.CMD_SUCCESS) {
                val len = w.readU32()
                val count = w.readU32()
                if (len > MAX_OUTPUT_BYTES) throw CommandFailedException(NgCodeProtocol.CMD_PROC_ASSEMBLE.toLong(), status, "Assembler output too large ($len bytes)")
                Assembled(w.readExactly(len.toInt()), count.toInt())
            } else if (status == Ps4DebugProtocol.CMD_ERROR) {
                // assemble.c send_err(): u32 ks_errno, u32 msg_len, message. A server without the command sends
                // CMD_ERROR alone and closes the connection (proc_handle default branch).
                val err = try {
                    val errno = w.readU32()
                    val mlen = w.readU32().coerceAtMost(4096)
                    errno to String(w.readExactly(mlen.toInt()), Charsets.US_ASCII).trimEnd('\u0000')
                } catch (e: IOException) {
                    null
                }
                w.invalidate() // the handler returned 1: the server closes this connection
                if (err == null) throw CodeToolUnavailableException(UNAVAILABLE)
                throw AssemblerException(err.first, err.second.ifBlank { "error ${err.first}" })
            } else {
                w.invalidate()
                w.expectSuccess(NgCodeProtocol.CMD_PROC_ASSEMBLE, status)
                error("unreachable")
            }
        }
    }

    /**
     * Instructions in `[scanStart, scanStart+scanLength)` whose RIP-relative memory operand resolves to [target]
     * (server-side Zydis sweep). The reply is a stream of u64 addresses ended by 0xFFFFFFFFFFFFFFFF; it is always
     * drained completely so the connection stays in sync, keeping at most [maxResults].
     */
    suspend fun findReferences(pid: Int, scanStart: Long, scanLength: Long, target: Long, maxResults: Int = 1000): List<Long> {
        requireNg("Reference search")
        require(scanLength in 1..0xFFFFFFFFL) { "Scan length out of range" }
        return transport.exclusive("find references", transport.serverScanTimeoutMs) { w ->
            w.send(
                w.header(NgCodeProtocol.CMD_PROC_FIND_XREFS_TO, NgCodeProtocol.XREFS_TO_PACKET_SIZE),
                LE.bytes32(pid.toLong()) + LE.bytes64(scanStart) + LE.bytes32(scanLength) + LE.bytes64(target),
            )
            val status = try { w.readU32() } catch (e: EOFException) { throw CodeToolUnavailableException(UNAVAILABLE) }
            if (status != Ps4DebugProtocol.CMD_SUCCESS) {
                w.invalidate()
                if (status == Ps4DebugProtocol.CMD_ERROR) throw CodeToolUnavailableException(UNAVAILABLE)
                w.expectSuccess(NgCodeProtocol.CMD_PROC_FIND_XREFS_TO, status)
            }
            val out = ArrayList<Long>()
            while (true) {
                val a = w.readU64()
                if (a == NgCodeProtocol.STREAM_SENTINEL) break
                if (out.size < maxResults) out += a
            }
            out
        }
    }

    private fun requireNg(what: String) {
        if (!available) throw CodeToolUnavailableException("$what runs on the PS4 and needs ps4debug-NG.")
    }

    companion object {
        const val MAX_SOURCE_BYTES = 4096
        const val MAX_OUTPUT_BYTES = 4096L
        const val UNAVAILABLE = "This ps4debug-NG build does not provide the command. Update ps4debug-NG (v1.3.4 or newer)."

        /** One statement per line; `;` also separates statements; `//` and `#` start comments. */
        fun normalise(source: String): String = source
            .lineSequence()
            .map { it.substringBefore("//").substringBefore('#').trim() }
            .flatMap { it.split(';').asSequence() }
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}

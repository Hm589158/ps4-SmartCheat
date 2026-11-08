package com.ps4smartcheat.core.diag

import com.ps4smartcheat.core.asm.AssemblerException
import com.ps4smartcheat.core.asm.CodeToolUnavailableException
import com.ps4smartcheat.core.backend.CommandFailedException
import com.ps4smartcheat.core.backend.ConnectionInterruptedException
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.backend.NotConnectedException
import com.ps4smartcheat.core.debug.ListenerUnavailableException
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.protocol.Ps4DebugProtocol
import kotlinx.coroutines.delay
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A user-facing explanation of an error: what probably happened and what to try. */
data class Diagnosis(val title: String, val cause: String, val actions: List<String>) {
    fun format(): String = buildString {
        append(title).append('\n').append(cause)
        actions.forEach { append("\n • ").append(it) }
    }
}

/** Maps connection, protocol and debugger errors to likely causes and next steps. Pure. */
object ErrorAdvisor {
    fun diagnose(error: Throwable): Diagnosis {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        fun has(type: Class<*>) = chain.any { type.isInstance(it) }
        val msg = error.message.orEmpty()
        return when {
            error is ListenerUnavailableException -> Diagnosis(
                "Interrupt connection unavailable",
                "ps4debug-NG reports watchpoint hits by connecting back to this device on port 755. ${error.message}",
                listOf(
                    "On a rooted device, allow the app to listen on port 755.",
                    "Or run a relay on a PC in the same network that listens on 755 and forwards to this phone (Settings → Debugger → Interrupt relay).",
                ),
            )
            error is AssemblerException -> Diagnosis("Assembly not accepted", error.detail, listOf("Use Intel syntax, one instruction per line, e.g. `mov eax, 999` or `nop`."))
            error is CodeToolUnavailableException -> Diagnosis("Feature not available on this debugger", msg, listOf("Load ps4debug-NG v1.3.4 or newer (see README → Required downloads)."))
            error is CommandFailedException && error.status == Ps4DebugProtocol.CMD_ALREADY_DEBUG -> Diagnosis(
                "Debugger busy", "ps4debug-NG already has a debug session (another tool or a session that did not end).",
                listOf("Close PS4CheaterNeo or other debuggers.", "Reload ps4debug-NG or restart the game."),
            )
            error is CommandFailedException -> Diagnosis(
                "The PS4 rejected the command", msg,
                listOf("Check that the game process is still running (Processes).", "Status ${Ps4DebugProtocol.statusName(error.status)} usually means an invalid address, pid or a command this payload does not support."),
            )
            error is NotConnectedException -> Diagnosis("Not connected", "There is no connection to ps4debug-NG.", listOf("Open Connect and connect to the PS4 on port 744."))
            error is ConnectionInterruptedException -> Diagnosis(
                "Connection interrupted", msg,
                listOf("The connection was re-established; the operation was not repeated. Check the result and try again."),
            )
            has(ConnectException::class.java) -> Diagnosis(
                "Connection refused", "The PS4 answered but nothing listens on the port.",
                listOf("Load ps4debug-NG with GoldHEN's payload loader (port 9020/9021) and wait for its notification.", "Use port 744.", "If the game was restarted, the payload may need to be loaded again."),
            )
            has(SocketTimeoutException::class.java) -> Diagnosis(
                "No answer from the PS4", "The PS4 did not answer in time.",
                listOf("Check the IP address in PS4 Settings → Network → View Connection Status.", "Phone and PS4 must be on the same network (no guest Wi-Fi / client isolation).", "Wake the PS4 from rest mode."),
            )
            has(NoRouteToHostException::class.java) || has(UnknownHostException::class.java) -> Diagnosis(
                "PS4 not reachable", "The network has no route to that address.", listOf("Connect the phone to the same Wi-Fi as the PS4.", "Check the IP address."),
            )
            has(EOFException::class.java) -> Diagnosis(
                "Connection closed by the PS4", "ps4debug-NG closed the connection (it does this after a failed command) or the payload stopped.",
                listOf("Reconnect. If it keeps happening, reload ps4debug-NG."),
            )
            else -> Diagnosis("Error", msg.ifBlank { error.javaClass.simpleName }, emptyList())
        }
    }
}

/** Result of [ProcessActivity.probe]. */
enum class Activity(val label: String) {
    RUNNING("running (memory changes)"),
    /** No byte changed in the sampled writable memory: the game is probably paused, stopped or in a menu. */
    STILL("no memory changes — paused or stopped?"),
    UNKNOWN("could not tell"),
}

/**
 * Heuristic check whether a process is executing: samples a few pages of its largest writable regions twice
 * [intervalMs] apart. A running game changes some of them (timers, frame counters); a stopped process (SIGSTOP,
 * a debugger halt) changes none. Read-only.
 */
object ProcessActivity {
    suspend fun probe(backend: DebugBackend, pid: Int, regions: List<MemoryRegion>, intervalMs: Long = 1000, samples: Int = 8, pageBytes: Int = 4096): Activity {
        val pages = regions.filter { it.isReadable && it.isWritable && it.size >= pageBytes }
            .sortedByDescending { it.size }.take(samples)
            .map { it.start }
        if (pages.isEmpty()) return Activity.UNKNOWN
        val first = pages.map { backend.readMemory(pid, it, pageBytes) }
        delay(intervalMs)
        val second = pages.map { backend.readMemory(pid, it, pageBytes) }
        return if (first.indices.any { !first[it].contentEquals(second[it]) }) Activity.RUNNING else Activity.STILL
    }
}

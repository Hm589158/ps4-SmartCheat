package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.asm.AsmInstruction
import com.ps4smartcheat.core.asm.X86
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.logging.EventLevel
import com.ps4smartcheat.core.logging.EventSink
import com.ps4smartcheat.core.logging.log
import com.ps4smartcheat.core.model.MemoryRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** One instruction that touched the watched address. */
data class AccessRecord(
    /** Address of the accessing instruction (the trap RIP points just after it). */
    val instructionAddress: Long,
    /** RIP reported by the stop (the next instruction). */
    val trapRip: Long,
    /** Decoded instruction, or null if the bytes before the trap RIP could not be matched to one. */
    val instruction: AsmInstruction?,
    val count: Int,
    /** Registers at the most recent hit. */
    val registers: Registers,
    val threadName: String,
    /** Address the instruction accessed, computed from the registers (may differ when a register was overwritten). */
    val effectiveAddress: Long?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    /** Callers at the first hit of this instruction (stack read while the game was paused); null when not captured. */
    val callStack: List<StackFrame>? = null,
)

data class MonitorOptions(
    val type: WatchType,
    /** Stop after this many stops in total (each one pauses the game for a round trip). */
    val maxHits: Int = 500,
    val maxMillis: Long = 60_000,
    /** Record the first hit with its registers, then end the session (the game pauses exactly once). */
    val captureOnce: Boolean = false,
    /**
     * Stack bytes read at the first hit of each instruction for its call stack, while the game is still paused
     * (one extra read of at most this many bytes before CONTINUE). 0 = no call stacks.
     */
    val stackBytes: Int = CallStack.DEFAULT_STACK_BYTES,
    /**
     * Flood guard: more stops than this within one second means the game spends most of its time paused (a value
     * read every frame, several threads…); monitoring stops before the game stalls.
     */
    val maxHitsPerSecond: Int = 40,
)

/** Why monitoring ended. */
enum class StopKind { CAPTURED, HIT_LIMIT, TIME_LIMIT, FLOOD, USER, SESSION_LOST, RESUME_FAILED, SIGNAL, ERROR }

data class MonitorResult(
    val records: List<AccessRecord>,
    val hits: Int,
    val stopReason: String,
    val stopKind: StopKind,
    /** What the cleanup could confirm (watchpoint cleared, game resumed, session detached). */
    val release: ReleaseReport,
)

/**
 * "Find out what writes / accesses this address": a hardware watchpoint (DR0) on the address.
 *
 * Every stop pauses the whole game until CMD_DEBUG_CONTINUE is confirmed, so the loop resumes **before** doing
 * anything else (decoding needs memory reads), checks the server's answer, retries once and otherwise ends the
 * session — DETACH also resumes the process. Whatever happens (limits, flood guard, errors, cancellation) the session
 * is ended through [DebugSession.release], which verifies each step and can release a session left on the PS4 from
 * a new connection. Memory is only read, never written.
 */
class AccessMonitor(
    private val backend: DebugBackend,
    private val clock: () -> Long = System::currentTimeMillis,
    private val events: EventSink = EventSink.NONE,
) {

    suspend fun run(
        session: DebugSession,
        address: Long,
        size: Int,
        options: MonitorOptions,
        /** Polled about every [POLL_MS]: true ends monitoring normally (results are returned). */
        shouldStop: () -> Boolean = { false },
        onUpdate: (List<AccessRecord>, Int) -> Unit = { _, _ -> },
    ): MonitorResult {
        val records = LinkedHashMap<Long, AccessRecord>()
        val decoded = HashMap<Long, AsmInstruction?>()
        val accessed = address until address + size
        val recentHits = ArrayDeque<Long>()
        val stackTaken = HashSet<Long>()
        var hits = 0
        val start = clock()
        var kind = StopKind.USER
        var reason = "stopped"
        val regions = try { backend.getMemoryRegions(session.pid) } catch (e: BackendException) { emptyList() }
        try {
            session.setWatchpoint(0, address, size, options.type)
            events.log(EventLevel.INFO, EventCategory.PROCESS, "watchpoint armed", "address" to "0x" + java.lang.Long.toHexString(address),
                "size" to size, "type" to options.type.name, "captureOnce" to options.captureOnce)
            while (true) {
                if (!currentCoroutineContext().isActive || shouldStop()) { kind = StopKind.USER; reason = "stopped"; break }
                if (hits >= options.maxHits) { kind = StopKind.HIT_LIMIT; reason = "hit limit (${options.maxHits}) reached"; break }
                if (clock() - start >= options.maxMillis) { kind = StopKind.TIME_LIMIT; reason = "time limit reached"; break }
                if (!session.alive) { kind = StopKind.SESSION_LOST; reason = "the debug session ended (connection re-established)"; break }
                val ev = session.nextEvent(POLL_MS) ?: continue
                if (ev.signal != DebugProtocol.SIGTRAP) {
                    // Not a watchpoint (e.g. the game crashed or got a signal). Monitoring ends; release() resumes and detaches.
                    kind = StopKind.SIGNAL; reason = "the game stopped with signal ${ev.signal}; monitoring ended"
                    break
                }
                // Call stack: the stack is only valid while the game is paused, so it is read now — once per instruction,
                // bounded, and never blocking the resume below (a failed read just means no call stack).
                val stack = if (options.stackBytes > 0 && ev.registers.rip !in stackTaken) readStack(session.pid, ev.registers, regions, options.stackBytes) else null
                // The game is paused right now: resume first, verified.
                if (!resumeVerified(session)) {
                    kind = StopKind.RESUME_FAILED
                    reason = "the PS4 refused to resume the game twice; the session was ended to release it"
                    break
                }
                val now = clock()
                recentHits.addLast(now)
                while (recentHits.isNotEmpty() && now - recentHits.first() > 1000) recentHits.removeFirst()
                if (ev.firedSlots.isNotEmpty() && 0 !in ev.firedSlots) continue
                hits++
                val rip = ev.registers.rip
                val ins = if (decoded.containsKey(rip)) decoded[rip] else locate(session.pid, rip, accessed, ev.registers, regions).also { decoded[rip] = it }
                val key = ins?.address ?: rip
                val ea = ins?.let { X86.effectiveAddress(it, ev.registers.values) }
                val frames = stack?.let { bytes ->
                    stackTaken += rip
                    try {
                        CallStack.unwindWithReader(rip, ev.registers.rsp, ev.registers.values["rbp"] ?: 0L, bytes, regions) { a, n -> backend.readMemory(session.pid, a, n) }
                    } catch (e: BackendException) {
                        null
                    }
                }
                val old = records[key]
                records[key] = old?.copy(
                    count = old.count + 1, registers = ev.registers, threadName = ev.threadName, effectiveAddress = ea, lastSeenMillis = now,
                    callStack = old.callStack ?: frames,
                ) ?: AccessRecord(key, rip, ins, 1, ev.registers, ev.threadName, ea, now, now, frames)
                onUpdate(records.values.sortedByDescending { it.count }, hits)
                if (options.captureOnce) { kind = StopKind.CAPTURED; reason = "first hit captured"; break }
                if (recentHits.size > options.maxHitsPerSecond) {
                    kind = StopKind.FLOOD
                    reason = "too many hits (${recentHits.size} in one second) — stopped so the game does not stall. Use Capture once or watch writes only."
                    events.log(EventLevel.WARN, EventCategory.PROCESS, "watchpoint flood guard", "hitsPerSecond" to recentHits.size)
                    break
                }
            }
        } catch (e: CancellationException) {
            kind = StopKind.USER; reason = "stopped"
            throw e
        } catch (e: DebuggerSessionLostException) {
            kind = StopKind.SESSION_LOST; reason = e.message ?: "the debug session ended"
        } catch (e: BackendException) {
            kind = StopKind.ERROR; reason = "error: ${e.message}"
        } finally {
            withContext(NonCancellable) {
                events.log(EventLevel.INFO, EventCategory.PROCESS, "watchpoint monitoring ended", "kind" to kind.name, "hits" to hits, "reason" to reason)
                session.release()
            }
        }
        val report = session.release() // already done: returns the stored report
        return MonitorResult(records.values.sortedByDescending { it.count }, hits, reason, kind, report)
    }

    /** CONTINUE with the server's answer checked; one retry after a short pause. */
    private suspend fun resumeVerified(session: DebugSession): Boolean {
        if (session.resume()) return true
        delay(DebugSession.RESUME_RETRY_DELAY_MS)
        return session.resume()
    }

    /** Stack bytes at rsp while the thread is stopped; null when rsp is not in readable memory or the read fails. */
    private suspend fun readStack(pid: Int, regs: Registers, regions: List<MemoryRegion>, want: Int): ByteArray? {
        val rsp = regs.values["rsp"] ?: return null
        val n = CallStack.readableLength(rsp, regions, want)
        if (n < 8) return null
        return try { backend.readMemory(pid, rsp, n) } catch (e: BackendException) { null }
    }

    private suspend fun locate(pid: Int, rip: Long, accessed: LongRange, regs: Registers, regions: List<MemoryRegion>): AsmInstruction? = try {
        // Only read inside the code region holding the trap RIP.
        val region = regions.firstOrNull { it.contains(rip - 1) && it.isReadable }
        val windowStart = maxOf(rip - 2 * X86.MAX_INSTRUCTION_LENGTH, region?.start ?: Long.MAX_VALUE)
        if (region == null || windowStart >= rip) null else {
            val window = backend.readMemory(pid, windowStart, (rip - windowStart).toInt())
            X86.instructionEndingAt(window, windowStart, rip, accessed, regs.values)
        }
    } catch (e: BackendException) {
        null
    }

    companion object {
        const val POLL_MS = 250
    }
}

package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.asm.AsmInstruction
import com.ps4smartcheat.core.asm.Flow
import com.ps4smartcheat.core.asm.X86
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.logging.EventLevel
import com.ps4smartcheat.core.logging.EventSink
import com.ps4smartcheat.core.logging.log
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** One point of a register trace: the thread is about to execute [instruction] at [address] with [registers]. */
data class TraceStep(
    val index: Int,
    val address: Long,
    val instruction: AsmInstruction?,
    val registers: Registers,
    /** Registers whose value differs from the previous step (never `rip`). Empty for the first step. */
    val changed: List<String>,
    val threadName: String,
    /** True when the instruction before this one ran without a step of its own (a call that was stepped over). */
    val afterCall: Boolean = false,
)

/** The stop at an execution breakpoint. */
data class BreakpointHit(
    val address: Long,
    val lwpid: Int,
    val threadName: String,
    /** Registers right before the breakpoint instruction ran. */
    val registers: Registers,
    val instruction: AsmInstruction?,
    /** Callers at the hit (see [CallStack]); null when the stack could not be read. */
    val callStack: List<StackFrame>?,
    val atMillis: Long,
)

/** Why a wait, a step or a trace ended. */
enum class TraceEnd(val label: String) {
    /** The requested number of steps was recorded. */
    COMPLETED("completed"),
    /** The function that contains the breakpoint returned to its caller. */
    LEFT_FUNCTION("the function returned"),
    USER("stopped"),
    /** The breakpoint was not reached in time. */
    NOT_HIT("the breakpoint was not reached"),
    /** A step or a stepped-over call did not report back in time (e.g. the thread is waiting in a system call). */
    TIMEOUT("no stop was reported in time"),
    STEP_REFUSED("the PS4 refused to step"),
    /** The game stopped for another reason (signal, another thread). */
    OTHER_STOP("the game stopped for another reason"),
    SESSION_LOST("the debug session ended"),
    ERROR("error"),
}

data class TraceOptions(
    /** Instructions to record after the current one (1..[ExecutionDebugger.MAX_TRACE_STEPS]). */
    val maxSteps: Int = 32,
    /** Run called functions without recording them (a temporary breakpoint at the return address). */
    val stepOverCalls: Boolean = true,
    /** End the trace when the function the trace started in returns. */
    val stayInFunction: Boolean = true,
    val stepTimeoutMs: Int = ExecutionDebugger.STEP_TIMEOUT_MS,
    val callTimeoutMs: Int = ExecutionDebugger.CALL_TIMEOUT_MS,
)

data class TraceResult(val steps: List<TraceStep>, val end: TraceEnd, val reason: String)

/**
 * Execution breakpoints, single-stepping and register traces on top of a [DebugSession] (ps4debug-NG only).
 *
 *  - **Breakpoint**: a hardware execution breakpoint (debug register, no code bytes changed). When a thread reaches it,
 *    ps4debug-NG steps the thread past the instruction and reports the stop with the registers from before it. The
 *    breakpoint is removed at the first hit, the stack is read for the call stack and the registers after the
 *    instruction are fetched (CMD_DEBUG_GETREGS). The game stays paused until [resume] / [DebugSession.release].
 *  - **Step**: CMD_DEBUG_STEP_THREAD for the thread that hit; its next stop is one recorded [TraceStep].
 *  - **Trace**: repeated steps with the changed registers per instruction; calls can be stepped over with a temporary
 *    execution breakpoint at the return address (slot [CALL_SLOT]).
 *
 * The game is paused between a hit and the resume, so callers must always end with [DebugSession.release] (it resumes,
 * clears the debug registers and detaches, with every step verified) — also on errors and cancellation.
 */
class ExecutionDebugger(
    private val backend: DebugBackend,
    private val session: DebugSession,
    private val events: EventSink = EventSink.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val steps = ArrayList<TraceStep>()
    private val code = HashMap<Long, ByteArray?>()
    private var regions: List<MemoryRegion> = emptyList()
    private var lwpid = 0
    private var threadName = ""
    /** The instruction the thread executes next, and the registers it will see. Null when not paused at a known point. */
    private var current: Pair<Long, Registers>? = null
    /** Call depth relative to the function the hit was in (negative = it returned). */
    private var depth = 0

    /** Everything recorded since the hit, oldest first. */
    val recorded: List<TraceStep> get() = steps.toList()
    var hit: BreakpointHit? = null
        private set
    /** True while the game is paused at a point this object knows (after a hit or a step). */
    val paused: Boolean get() = current != null && session.alive && session.pendingStop

    /**
     * Arms the breakpoint at [address] and waits for a thread to reach it. Returns the hit, or null with the reason in
     * the pair's second value. On a hit the breakpoint is already removed and the game is paused.
     */
    suspend fun awaitHit(address: Long, timeoutMs: Long, shouldStop: () -> Boolean = { false }): Pair<BreakpointHit?, TraceEnd> {
        regions = try { backend.getMemoryRegions(session.pid) } catch (e: BackendException) { emptyList() }
        val region = regions.firstOrNull { it.contains(address) }
        require(region == null || region.isExecutable) { "${Address.format(address)} is not executable code (${region?.name?.ifBlank { "anon" }} ${region?.protString()})." }
        session.setExecBreakpoint(HIT_SLOT, address)
        events.log(EventLevel.INFO, EventCategory.PROCESS, "execution breakpoint armed", "address" to Address.format(address))
        val start = clock()
        while (true) {
            if (!currentCoroutineContext().isActive || shouldStop()) return null to TraceEnd.USER
            if (clock() - start >= timeoutMs) return null to TraceEnd.NOT_HIT
            if (!session.alive) return null to TraceEnd.SESSION_LOST
            val ev = session.nextEvent(POLL_MS) ?: continue
            if (ev.signal != DebugProtocol.SIGTRAP) return null to TraceEnd.OTHER_STOP
            if (ev.registers.rip != address) {
                // Some other trap: let the game go on and keep waiting.
                if (!resumeVerified()) return null to TraceEnd.ERROR
                continue
            }
            // Paused. Remove the breakpoint first so that nothing below (steps, resume) can meet it again.
            session.clearWatchpoint(HIT_SLOT)
            lwpid = ev.lwpid
            threadName = ev.threadName
            val before = ev.registers
            // The server already stepped the thread past the instruction: its current state is one instruction later.
            val after = try { session.readRegisters(lwpid) } catch (e: DebuggerSessionLostException) { throw e } catch (e: DebuggerException) { null }
            val ins = decodeAt(address)
            val basis = after ?: before
            val stack = readStack(basis)
            val frames = stack?.let {
                try {
                    CallStack.unwindWithReader(basis.rip, basis.rsp, basis.values["rbp"] ?: 0L, it, regions) { a, n -> backend.readMemory(session.pid, a, n) }
                        .filter { f -> f.callAddress != address }
                } catch (e: BackendException) {
                    null
                }
            }
            val h = BreakpointHit(address, lwpid, threadName, before, ins, frames, clock())
            hit = h
            steps.clear()
            depth = 0
            steps += TraceStep(0, address, ins, before, emptyList(), threadName)
            if (after != null) {
                account(ins)
                current = after.rip to after
                steps += TraceStep(1, after.rip, decodeAt(after.rip), after, diff(before, after), threadName)
            } else {
                current = null
            }
            events.log(EventLevel.INFO, EventCategory.PROCESS, "execution breakpoint hit", "address" to Address.format(address), "thread" to threadName)
            return h to TraceEnd.COMPLETED
        }
    }

    /** One single step (into calls). Returns the new step, or null with the reason. */
    suspend fun step(timeoutMs: Int = STEP_TIMEOUT_MS): Pair<TraceStep?, TraceEnd> {
        val (rip, regs) = current ?: return null to TraceEnd.ERROR
        val ins = decodeAt(rip)
        if (!session.stepThread(lwpid)) return null to TraceEnd.STEP_REFUSED
        val ev = session.nextEvent(timeoutMs) ?: run { current = null; return null to TraceEnd.TIMEOUT }
        if (ev.signal != DebugProtocol.SIGTRAP || ev.lwpid != lwpid) { current = null; return null to TraceEnd.OTHER_STOP }
        account(ins)
        return record(ev.registers, regs, afterCall = false) to TraceEnd.COMPLETED
    }

    /**
     * Steps over the instruction at the current point: a `call` runs to its return address (temporary execution
     * breakpoint in slot [CALL_SLOT]); anything else is a normal [step].
     */
    suspend fun stepOver(stepTimeoutMs: Int = STEP_TIMEOUT_MS, callTimeoutMs: Int = CALL_TIMEOUT_MS): Pair<TraceStep?, TraceEnd> {
        val (rip, regs) = current ?: return null to TraceEnd.ERROR
        val ins = decodeAt(rip)
        if (ins == null || ins.flow != Flow.CALL) return step(stepTimeoutMs)
        val returnAddress = rip + ins.length
        session.setExecBreakpoint(CALL_SLOT, returnAddress)
        if (!session.resume()) { runCatching { session.clearWatchpoint(CALL_SLOT) }; return null to TraceEnd.STEP_REFUSED }
        val start = clock()
        while (true) {
            val left = callTimeoutMs - (clock() - start)
            if (left <= 0) {
                // The call is still running (the game is not paused): give up on the trace and remove the breakpoint.
                current = null
                runCatching { session.clearWatchpoint(CALL_SLOT) }
                return null to TraceEnd.TIMEOUT
            }
            val ev = session.nextEvent(minOf(left, POLL_MS.toLong()).toInt()) ?: continue
            if (ev.signal != DebugProtocol.SIGTRAP || ev.lwpid != lwpid) {
                current = null
                runCatching { session.clearWatchpoint(CALL_SLOT) }
                return null to TraceEnd.OTHER_STOP
            }
            // A recursive call reaches the same address deeper in the stack: not our return yet.
            if (ev.registers.rip != returnAddress || ev.registers.rsp != regs.rsp) {
                if (!resumeVerified()) { current = null; return null to TraceEnd.ERROR }
                continue
            }
            session.clearWatchpoint(CALL_SLOT)
            // Reported with the registers before the instruction at the return address; the server stepped past it.
            val atReturn = record(ev.registers, regs, afterCall = true, setCurrent = false)
            val after = try { session.readRegisters(lwpid) } catch (e: DebuggerSessionLostException) { throw e } catch (e: DebuggerException) { null }
            if (after == null) { current = null; return atReturn to TraceEnd.ERROR }
            account(atReturn.instruction)
            return record(after, ev.registers, afterCall = false) to TraceEnd.COMPLETED
        }
    }

    /**
     * Records up to [TraceOptions.maxSteps] further instructions. The game stays paused when the trace ends with
     * [TraceEnd.COMPLETED], [TraceEnd.LEFT_FUNCTION] or [TraceEnd.USER]; check [paused].
     */
    suspend fun trace(options: TraceOptions, shouldStop: () -> Boolean = { false }, onStep: (List<TraceStep>) -> Unit = {}): TraceResult {
        val max = options.maxSteps.coerceIn(1, MAX_TRACE_STEPS)
        val first = steps.size
        fun done(end: TraceEnd, reason: String = end.label) = TraceResult(steps.toList(), end, reason).also {
            events.log(EventLevel.INFO, EventCategory.PROCESS, "register trace ended", "steps" to steps.size - first, "end" to end.name)
        }
        if (current == null) return done(TraceEnd.ERROR, "the game is not paused at a known instruction")
        try {
            while (steps.size - first < max) {
                if (!currentCoroutineContext().isActive || shouldStop()) return done(TraceEnd.USER)
                if (options.stayInFunction && depth < 0) return done(TraceEnd.LEFT_FUNCTION)
                val (s, end) = if (options.stepOverCalls) stepOver(options.stepTimeoutMs, options.callTimeoutMs) else step(options.stepTimeoutMs)
                if (s != null) onStep(steps.toList())
                if (end != TraceEnd.COMPLETED) return done(end)
            }
            return done(if (options.stayInFunction && depth < 0) TraceEnd.LEFT_FUNCTION else TraceEnd.COMPLETED)
        } catch (e: DebuggerSessionLostException) {
            current = null
            return done(TraceEnd.SESSION_LOST, e.message ?: TraceEnd.SESSION_LOST.label)
        } catch (e: BackendException) {
            current = null
            return done(TraceEnd.ERROR, "error: ${e.message}")
        }
    }

    /** CMD_DEBUG_CONTINUE, verified (one retry). The recorded steps are kept; stepping is over. */
    suspend fun resume(): Boolean {
        current = null
        return resumeVerified()
    }

    private suspend fun resumeVerified(): Boolean {
        if (session.resume()) return true
        kotlinx.coroutines.delay(DebugSession.RESUME_RETRY_DELAY_MS)
        return session.resume()
    }

    /** Tracks calls and returns of an executed instruction for [TraceOptions.stayInFunction]. */
    private fun account(ins: AsmInstruction?) {
        when (ins?.flow) {
            Flow.CALL -> depth++
            Flow.RETURN -> depth--
            else -> Unit
        }
    }

    private suspend fun record(regs: Registers, previous: Registers, afterCall: Boolean, setCurrent: Boolean = true): TraceStep {
        val s = TraceStep(steps.size, regs.rip, decodeAt(regs.rip), regs, diff(previous, regs), threadName, afterCall)
        steps += s
        if (setCurrent) current = regs.rip to regs
        return s
    }

    private fun diff(a: Registers, b: Registers): List<String> = TRACKED.filter { a.values[it] != b.values[it] }

    /** Decodes the instruction at [address] from cached 256-byte blocks (a straight run of code costs one read). */
    private suspend fun decodeAt(address: Long): AsmInstruction? {
        if (address == 0L) return null
        val out = ByteArray(X86.MAX_INSTRUCTION_LENGTH)
        var n = 0
        while (n < out.size) {
            val a = address + n
            val b = a and (BLOCK - 1).inv()
            val data = block(b) ?: break
            val o = (a - b).toInt()
            if (o >= data.size) break
            val k = minOf(out.size - n, data.size - o)
            data.copyInto(out, n, o, o + k)
            n += k
        }
        if (n == 0) return null
        return X86.decodeOne(out.copyOf(n), 0, address).takeIf { it.valid }
    }

    private suspend fun block(b: Long): ByteArray? {
        if (code.containsKey(b)) return code[b]
        val r = regions.firstOrNull { it.contains(b) && it.isReadable }
        val data = if (r == null) null else try {
            backend.readMemory(session.pid, b, minOf(BLOCK, r.end - b).toInt())
        } catch (e: BackendException) {
            null
        }
        code[b] = data
        return data
    }

    private suspend fun readStack(regs: Registers): ByteArray? {
        val n = CallStack.readableLength(regs.rsp, regions)
        if (n < 8) return null
        return try { backend.readMemory(session.pid, regs.rsp, n) } catch (e: BackendException) { null }
    }

    companion object {
        /** Debug-register slot of the user's breakpoint. */
        const val HIT_SLOT = 0
        /** Debug-register slot of the temporary breakpoint used to step over a call. */
        const val CALL_SLOT = 1
        const val POLL_MS = 250
        const val STEP_TIMEOUT_MS = 2000
        const val CALL_TIMEOUT_MS = 3000
        const val MAX_TRACE_STEPS = 512
        private const val BLOCK = 256L
        /** Registers compared between steps. */
        val TRACKED = listOf("rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp", "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15", "rflags")
    }
}

/** Plain-text forms of a trace, for the clipboard. */
object TraceFormat {
    /**
     * One line per step: `#3  0x000402009  eboot.bin+0x2009  mov [0x500120], eax   rax=0x0000000000001387 (4999)`.
     * The first step lists no changes; `(after call)` marks the instruction a stepped-over call returned to.
     */
    fun format(steps: List<TraceStep>, relative: (Long) -> String = { "" }): String = steps.joinToString("\n") { s ->
        buildString {
            append('#').append(s.index).append("  ").append(Address.format(s.address))
            relative(s.address).takeIf { it.isNotBlank() }?.let { append("  ").append(it) }
            append("  ").append(s.instruction?.text ?: "?")
            if (s.afterCall) append("  (after call)")
            if (s.changed.isNotEmpty()) {
                append("   ")
                append(s.changed.joinToString("  ") { n -> "$n=" + RegisterFormat.both(s.registers.values[n] ?: 0L, 64) })
            }
        }
    }
}

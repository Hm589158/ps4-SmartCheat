package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.asm.AsmInstruction
import com.ps4smartcheat.core.asm.X86
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.DebugBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
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
)

data class MonitorOptions(
    val type: WatchType,
    /** Stop after this many stops in total (each one pauses the game for a round trip). */
    val maxHits: Int = 500,
    val maxMillis: Long = 60_000,
)

data class MonitorResult(val records: List<AccessRecord>, val hits: Int, val stopReason: String)

/**
 * "Find out what writes / accesses this address": a hardware watchpoint (DR0) on the address; every stop is
 * recorded and the game is resumed immediately; the responsible instruction is decoded from the bytes before the
 * trap RIP (data watchpoints trap after the instruction executed). Memory is only read, never written.
 */
class AccessMonitor(private val backend: DebugBackend, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun run(
        session: DebugSession,
        address: Long,
        size: Int,
        options: MonitorOptions,
        onUpdate: (List<AccessRecord>, Int) -> Unit = { _, _ -> },
    ): MonitorResult {
        val records = LinkedHashMap<Long, AccessRecord>()
        val decoded = HashMap<Long, AsmInstruction?>()
        val accessed = address until address + size
        var hits = 0
        val start = clock()
        var reason = "stopped"
        val regions = try { backend.getMemoryRegions(session.pid) } catch (e: BackendException) { emptyList() }
        try {
            session.setWatchpoint(0, address, size, options.type)
            while (true) {
                if (!currentCoroutineContext().isActive) { reason = "stopped"; break }
                if (hits >= options.maxHits) { reason = "hit limit (${options.maxHits}) reached"; break }
                if (clock() - start >= options.maxMillis) { reason = "time limit reached"; break }
                if (!session.alive) { reason = "debug session ended (connection re-established)"; break }
                val ev = session.nextEvent(250) ?: continue
                if (ev.signal != DebugProtocol.SIGTRAP) {
                    // Not our watchpoint (e.g. the game crashed or got a signal). Never swallow it: detach and let the
                    // process handle it.
                    reason = "the game stopped with signal ${ev.signal}; monitoring ended and the debugger detached"
                    break
                }
                session.resume() // keep the pause as short as possible
                if (ev.firedSlots.isNotEmpty() && 0 !in ev.firedSlots) continue
                hits++
                val rip = ev.registers.rip
                val ins = if (decoded.containsKey(rip)) decoded[rip] else locate(session.pid, rip, accessed, ev.registers, regions).also { decoded[rip] = it }
                val key = ins?.address ?: rip
                val now = clock()
                val ea = ins?.let { X86.effectiveAddress(it, ev.registers.values) }
                records[key] = records[key]?.copy(count = records[key]!!.count + 1, registers = ev.registers, threadName = ev.threadName, effectiveAddress = ea, lastSeenMillis = now)
                    ?: AccessRecord(key, rip, ins, 1, ev.registers, ev.threadName, ea, now, now)
                onUpdate(records.values.sortedByDescending { it.count }, hits)
            }
        } catch (e: CancellationException) {
            reason = "stopped"
            throw e
        } catch (e: BackendException) {
            reason = "error: ${e.message}"
        } finally {
            withContext(NonCancellable) {
                runCatching { if (session.alive) session.clearWatchpoint(0) }
                session.detach()
            }
        }
        return MonitorResult(records.values.sortedByDescending { it.count }, hits, reason)
    }

    private suspend fun locate(pid: Int, rip: Long, accessed: LongRange, regs: Registers, regions: List<com.ps4smartcheat.core.model.MemoryRegion>): AsmInstruction? = try {
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
}

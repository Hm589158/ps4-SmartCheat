package com.ps4smartcheat.core.asm

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.edit.EditDeniedException
import com.ps4smartcheat.core.edit.MAX_PATCH_BYTES
import com.ps4smartcheat.core.edit.MemoryEditor
import com.ps4smartcheat.core.edit.WriteOutcome
import com.ps4smartcheat.core.edit.WritePlan
import com.ps4smartcheat.core.util.Address

/** One confirmed write of an injection. */
data class InjectionStep(val label: String, val plan: WritePlan)

data class InjectionResult(
    val ok: Boolean,
    val message: String,
    /** Steps verified before the failure (or all). */
    val written: Int,
    /** Steps undone (verified) after a failed enable. */
    val rolledBack: Int = 0,
    val stateAfter: InjectionState? = null,
)

/**
 * Writes an injection through [MemoryEditor] (policy checks, journal, verified by a fresh read), in an order that
 * never lets the game jump into incomplete code:
 *  - enable: cave code in chunks of ≤ [MAX_PATCH_BYTES], each verified, then the jump at the hook;
 *  - disable: the hook's original bytes first, then (for caves in the executable) the cave's original padding.
 * A failed enable undoes what it wrote (verified). A failed disable stops: after the hook was restored the cave code
 * can no longer run, so leaving it is harmless (state [InjectionState.CAVE_ONLY]).
 */
class InjectionWriter(private val backend: DebugBackend, private val editor: MemoryEditor) {

    suspend fun state(pid: Int, inj: CodeInjection): InjectionState =
        inj.state(backend.readMemory(pid, inj.hookAddress, inj.hookOn.size), backend.readMemory(pid, inj.caveAddress, inj.caveOn.size))

    /**
     * The writes for switching ON ([enable]) or OFF, after checking the current bytes. For a cave in allocated memory
     * OFF only restores the hook (the memory is freed separately). Throws [EditDeniedException] when not allowed now.
     */
    suspend fun prepare(pid: Int, processName: String, inj: CodeInjection, enable: Boolean): List<InjectionStep> {
        if (enable && inj.usesFlag && inj.flagAddress == null) {
            throw EditDeniedException("The one-shot flag is not allocated yet: allocate it before switching ON. Nothing written.")
        }
        val now = state(pid, inj)
        // One map for every part (a 1 KiB cave is 16 writes): the map is the slowest request on a PS4.
        val regions = backend.getMemoryRegions(pid)
        val steps = ArrayList<InjectionStep>()
        fun cave(on: Boolean) = (inj.caveOn.indices step MAX_PATCH_BYTES).map { off ->
            val end = minOf(off + MAX_PATCH_BYTES, inj.caveOn.size)
            Triple(inj.caveAddress + off, (if (on) inj.caveOn else inj.caveOff).copyOfRange(off, end), "cave ${off / MAX_PATCH_BYTES + 1}")
        }
        val total = (inj.caveOn.size + MAX_PATCH_BYTES - 1) / MAX_PATCH_BYTES
        if (enable) {
            // The flag must read 0 before the code that tests it can run (new memory does; anything else is reset, confirmed).
            inj.flagAddress?.let { flag ->
                if (backend.readMemory(pid, flag, OneShotFlag.SIZE).any { it != 0.toByte() }) {
                    steps += InjectionStep("flag reset", editor.planBytes(pid, processName, flag, ByteArray(OneShotFlag.SIZE), regions))
                }
            }
            when (now) {
                InjectionState.OFF -> cave(true).forEach { (a, b, l) -> steps += InjectionStep("$l/$total", editor.planBytes(pid, processName, a, b, regions)) }
                InjectionState.CAVE_ONLY -> Unit // cave already verified in place: only the jump is missing
                else -> throw EditDeniedException("The injection is ${now.label} — it can only be switched ON from \"off\". Nothing written.")
            }
            steps += InjectionStep("jump", editor.planBytes(pid, processName, inj.hookAddress, inj.hookOn, regions))
        } else {
            when (now) {
                InjectionState.ON -> steps += InjectionStep("jump", editor.planBytes(pid, processName, inj.hookAddress, inj.hookOff, regions))
                InjectionState.CAVE_ONLY -> Unit
                else -> throw EditDeniedException("The injection is ${now.label} — it can only be switched OFF from \"on\". Nothing written.")
            }
            if (inj.caveKind == CaveKind.MODULE_PADDING) {
                cave(false).forEach { (a, b, l) -> steps += InjectionStep("$l/$total", editor.planBytes(pid, processName, a, b, regions)) }
            }
        }
        return steps
    }

    /** Executes confirmed [steps] in order (see the class comment for failure handling). */
    suspend fun execute(pid: Int, inj: CodeInjection, steps: List<InjectionStep>, enable: Boolean): InjectionResult {
        val done = ArrayList<InjectionStep>()
        for (s in steps) {
            val out = editor.execute(s.plan)
            if (out is WriteOutcome.Verified) { done += s; continue }
            val what = "${s.label} at ${Address.format(s.plan.target.address)}: ${out.message}"
            if (!enable) {
                return InjectionResult(false, "Disable stopped at $what", done.size, stateAfter = runCatching { state(pid, inj) }.getOrNull())
            }
            // Undo the verified writes in reverse (the jump is last, so the game never reached the cave).
            var undone = 0
            val regions = runCatching { backend.getMemoryRegions(pid) }.getOrNull()
            for (d in (done + s).asReversed()) {
                val back = runCatching {
                    editor.execute(editor.planBytes(pid, d.plan.target.processName, d.plan.target.address, d.plan.original, regions))
                }.getOrNull()
                if (back is WriteOutcome.Verified) undone++
            }
            return InjectionResult(
                false, "Enable failed at $what. Undid $undone write(s).", done.size, undone,
                runCatching { state(pid, inj) }.getOrNull(),
            )
        }
        val after = runCatching { state(pid, inj) }.getOrNull()
        val label = if (enable) "ON" else "OFF"
        return InjectionResult(true, "Injection $label: ${done.size} write(s) verified", done.size, stateAfter = after)
    }
}

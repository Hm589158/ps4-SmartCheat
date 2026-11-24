package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.asm.CaveAllocation
import com.ps4smartcheat.core.asm.CaveAllocator
import com.ps4smartcheat.core.asm.OneShotFlag
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.edit.EditDeniedException
import com.ps4smartcheat.core.edit.MemoryEditor
import com.ps4smartcheat.core.edit.WriteOutcome
import com.ps4smartcheat.core.edit.WritePlan

/** The mod cannot be switched now ([check] says why); nothing was written or allocated. */
class SwitchRefusedException(val check: ModCheck, message: String) : EditDeniedException(message)

/**
 * Switches a library (or GoldHEN file) mod ON/OFF in the running game: read-only check first, then confirmed writes in
 * a safe order (ON in file order, OFF in reverse: an injection's jump goes in last and comes out first), parts already
 * in the target state skipped.
 *
 * One-shot injections ([OneShotFlag]): ON binds the cave to this session's flag — the one parts already ON address
 * (an interrupted switch), the one the caller kept for this mod in this session, or a new allocation next to the cave
 * ([Prepared.newFlag]; freed again by [cancel] when the switch is not confirmed).
 */
class LibrarySwitch(
    private val backend: DebugBackend,
    private val editor: MemoryEditor,
    private val allocator: CaveAllocator?,
) {
    /** A switch ready for confirmation: the writes and, for a one-shot mod, the flag allocated for it (if new). */
    class Prepared(
        val check: ModCheck, val enable: Boolean, val plans: List<WritePlan>,
        val newFlag: CaveAllocation?, val flag: Long?,
    )

    /** Result of [execute]: [written] parts verified; [message] says where it stopped when not [ok]. */
    class Result(val ok: Boolean, val written: Int, val message: String)

    /**
     * Checks the mod and plans its writes. Nothing is written. Throws [SwitchRefusedException] when the mod is not in a
     * state that allows the switch, other exceptions (allocation, policy, connection) as they come — after freeing a
     * flag allocated here.
     */
    suspend fun prepare(
        pid: Int, processName: String, file: GhImportFile, mod: GhImportMod, enable: Boolean, sessionFlag: Long? = null,
    ): Prepared {
        val regions = backend.getMemoryRegions(pid)
        val check = GoldHenImport.check(backend, pid, file, mod, regions)
        if (if (enable) !check.canEnable else !check.canDisable) {
            throw SwitchRefusedException(
                check,
                "\"${mod.name}\" cannot be switched ${if (enable) "on" else "off"}: " +
                    (mod.problems.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: check.parts.joinToString("; ") { "${it.label} is ${it.state.label}" }) +
                    " — nothing written.",
            )
        }
        var newFlag: CaveAllocation? = null
        try {
            val flag: Long? = if (enable && mod.usesFlag) {
                check.boundFlag() ?: sessionFlag ?: run {
                    val a = allocator?.takeIf { it.available }
                        ?: throw EditDeniedException("\"${mod.name}\" uses a one-shot flag, which needs ps4debug-NG (memory allocation).")
                    val hint = check.flagHint() ?: throw EditDeniedException("\"${mod.name}\": the code cave is not mapped.")
                    a.allocateNear(pid, hint, OneShotFlag.SIZE).also { newFlag = it }.address
                }
            } else null
            // One map for every part (re-read: an allocation above changed it).
            val now = if (newFlag != null) backend.getMemoryRegions(pid) else regions
            val plans = check.writeOrder(enable).map { i ->
                val p = mod.patches[i]
                editor.planBytes(pid, processName, check.addresses[i], if (enable) p.onFor(check.addresses[i], flag) else p.off, now)
            }
            return Prepared(check, enable, plans, newFlag, flag)
        } catch (e: Throwable) {
            newFlag?.let { f -> runCatching { allocator?.free(f) } }
            throw e
        }
    }

    /**
     * Writes [p]'s parts in order, each verified; stops at the first one that is not. Parts that are all data (no code)
     * go in one bulk write on ps4debug-NG, every part read back on the PS4 ([MemoryEditor.executeBatch]).
     */
    suspend fun execute(p: Prepared): Result {
        editor.executeBatch(p.plans)?.let { outs ->
            val ok = outs.count { it is WriteOutcome.Verified }
            val bad = outs.withIndex().filter { it.value !is WriteOutcome.Verified }
            return if (bad.isEmpty()) Result(true, ok, "${p.plans.size} patch(es) verified")
            else Result(false, ok, "${bad.joinToString("; ") { "part ${it.index + 1}: ${it.value.message}" }} ($ok of ${p.plans.size} part(s) verified; they stay written)")
        }
        var done = 0
        for (plan in p.plans) {
            val out = editor.execute(plan)
            if (out !is WriteOutcome.Verified) {
                return Result(false, done, "${out.message} (stopped after $done of ${p.plans.size} part(s); the parts written stay)")
            }
            done++
        }
        return Result(true, done, "${p.plans.size} patch(es) verified")
    }

    /** The switch was not confirmed: frees the flag allocated for it (nothing was written, so nothing addresses it). */
    suspend fun cancel(p: Prepared) {
        p.newFlag?.let { f -> runCatching { allocator?.free(f) } }
    }
}

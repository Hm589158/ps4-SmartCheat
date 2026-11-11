package com.ps4smartcheat.core.asm

import com.ps4smartcheat.core.cheat.CheatProfile
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex

/** A patch the app knows about: a library entry resolved in the running game, or an injection of this session. */
class AppliedPatch(val name: String, val address: Long, val on: ByteArray, val off: ByteArray) {
    val end: Long get() = address + on.size
}

/**
 * Finds the bytes of a known patch in memory that is about to be used as "original" code.
 *
 * A new code patch or injection takes its OFF bytes from memory. When another patch is ON at that address, those
 * bytes are the other patch (e.g. an injection's jump), not the game's code: saved as OFF they would later write that
 * jump back with nothing behind it, and the new cave would copy the jump as an "original" instruction. Planning is
 * refused instead until the other patch is OFF (or the game restarted).
 */
object AppliedPatches {
    /**
     * The first [known] patch that is ON in [current] (bytes read at [address]) within [address, address + [length]).
     * Only the bytes a patch actually changes (ON ≠ OFF) are compared, and at least one must be in the range.
     */
    fun covering(address: Long, current: ByteArray, length: Int, known: List<AppliedPatch>): AppliedPatch? {
        val end = address + minOf(length, current.size)
        return known.firstOrNull { p ->
            val from = maxOf(address, p.address)
            val to = minOf(end, p.end)
            if (from >= to) return@firstOrNull false
            val changed = (from until to).filter { a -> p.on[(a - p.address).toInt()] != p.off.getOrNull((a - p.address).toInt()) }
            changed.isNotEmpty() && changed.all { a -> current[(a - address).toInt()] == p.on[(a - p.address).toInt()] }
        }
    }

    /** The message of [covering]. */
    fun refusal(p: AppliedPatch, what: String): String =
        "$what at ${Address.format(p.address)} is currently changed by \"${p.name}\" (ON). Switch it OFF first (or restart " +
            "the game): otherwise its bytes would be saved as the original code (OFF) and writing them later would crash the game."

    /** The library's patch entries of [profile] that resolve in this game ([regions]). */
    fun fromProfile(profile: CheatProfile?, regions: List<MemoryRegion>): List<AppliedPatch> =
        profile?.cheats.orEmpty().filter { it.isPatch && !it.originalBytes.isNullOrBlank() }.mapNotNull { c ->
            val at = c.module?.let { ModuleResolver.resolve(it, regions) } ?: return@mapNotNull null
            val on = runCatching { Hex.decode(c.patchBytes!!) }.getOrNull() ?: return@mapNotNull null
            val off = runCatching { Hex.decode(c.originalBytes!!) }.getOrNull() ?: return@mapNotNull null
            AppliedPatch(c.name, at, on, off)
        }

    /** The jump and cave of an injection. */
    fun of(name: String, inj: CodeInjection): List<AppliedPatch> = listOf(
        AppliedPatch("$name (jump)", inj.hookAddress, inj.hookOn, inj.hookOff),
        AppliedPatch("$name (cave)", inj.caveAddress, inj.caveOn, inj.caveOff),
    )
}

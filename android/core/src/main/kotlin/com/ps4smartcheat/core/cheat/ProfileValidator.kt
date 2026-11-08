package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.ProcessInfo
import com.ps4smartcheat.core.model.regionFor

enum class CheatStatus(val label: String, val usable: Boolean) {
    OK("OK", true),
    RELOCATED("Module moved — address re-resolved", true),
    ABSOLUTE_UNVERIFIED("Absolute address from another session — may be stale", true),
    NOT_MAPPED("Address not mapped", false),
    NOT_WRITABLE("Address not writable", false),
    MODULE_MISSING("Module not loaded", false),
    NO_VALUE("No replacement value", false),
}

data class CheatCheck(val entry: CheatEntry, val status: CheatStatus, val resolvedAddress: Long?)

data class ProfileCheck(
    val profile: CheatProfile,
    val processMatches: Boolean,
    /** null when either side has no title id. */
    val titleMatches: Boolean?,
    val cheats: List<CheatCheck>,
) {
    val warnings: List<String> = buildList {
        if (!processMatches) add("Profile is for process \"${profile.processName}\"")
        if (titleMatches == false) add("Title ID mismatch: profile is for ${profile.titleId}")
        if (titleMatches == null) add("Title ID could not be compared")
        if (profile.gameVersion == null) add("Game version not recorded; addresses may differ between game versions")
    }

    /** Re-applying values requires the right game and at least one usable entry. */
    val canApply: Boolean get() = processMatches && titleMatches != false && cheats.any { it.status.usable }
}

/** Checks a saved profile against the currently running process before any value is reapplied. */
object ProfileValidator {
    fun validate(profile: CheatProfile, pid: Int, info: ProcessInfo?, processName: String, regions: List<MemoryRegion>): ProfileCheck {
        val processMatches = profile.processName == processName
        val titleMatches = if (profile.titleId.isNullOrBlank() || info?.titleId.isNullOrBlank()) null
        else profile.titleId.equals(info!!.titleId, ignoreCase = true)
        val checks = profile.cheats.map { checkEntry(it, pid, regions) }
        return ProfileCheck(profile, processMatches, titleMatches, checks)
    }

    fun checkEntry(e: CheatEntry, pid: Int, regions: List<MemoryRegion>): CheatCheck {
        if (!e.isPatch && e.replacementValue.isNullOrBlank()) return CheatCheck(e, CheatStatus.NO_VALUE, null)
        val size = if (e.isPatch) e.patchBytes!!.filterNot { it.isWhitespace() }.length / 2 else e.type.size
        val (address, relocated) = if (e.module != null) {
            val resolved = ModuleResolver.resolve(e.module, regions)
                ?: return CheatCheck(e, CheatStatus.MODULE_MISSING, null)
            resolved to (resolved != e.address)
        } else {
            e.address to false
        }
        val region = regions.regionFor(address, size) ?: return CheatCheck(e, CheatStatus.NOT_MAPPED, address)
        // Code patches live in read-only executable memory; they are toggled through the code-patch policy.
        if (!region.isWritable && !(e.isPatch && region.isReadable)) return CheatCheck(e, CheatStatus.NOT_WRITABLE, address)
        val status = when {
            relocated -> CheatStatus.RELOCATED
            e.module == null && e.discoveredPid != pid -> CheatStatus.ABSOLUTE_UNVERIFIED
            else -> CheatStatus.OK
        }
        return CheatCheck(e, status, address)
    }
}

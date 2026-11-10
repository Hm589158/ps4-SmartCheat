package com.ps4smartcheat.core.validate

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.cheat.CheatEntry
import com.ps4smartcheat.core.cheat.CheatProfile
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.cheat.PatchState
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.tools.BytePattern
import com.ps4smartcheat.core.tools.PatternSearch
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex
import com.ps4smartcheat.core.watch.WatchEntry
import com.ps4smartcheat.core.watch.WatchResolver
import com.ps4smartcheat.core.watch.WatchStatus

enum class ItemKind(val label: String) { ADDRESS("address"), POINTER("pointer"), VALUE_CHEAT("value cheat"), CODE_PATCH("code patch") }

enum class Validity(val label: String, val ok: Boolean) {
    VALID("valid", true),
    /** Patch currently applied (memory holds the "on" bytes). */
    ACTIVE("valid — currently ON", true),
    /** Found again at another offset through its signature (game update?). Not changed automatically. */
    MOVED("moved — signature found elsewhere", false),
    /** Can be used, but nothing proves it still points at the same thing (absolute address from an older session). */
    UNVERIFIED("mapped, not verifiable", true),
    INVALID("invalid in this session", false),
}

data class ValidationItem(
    val kind: ItemKind,
    val name: String,
    val validity: Validity,
    val detail: String,
    val address: Long? = null,
    /** For [Validity.MOVED]: the executable offset where the signature was found. */
    val suggestedOffset: Long? = null,
)

data class ValidationReport(val pid: Int, val items: List<ValidationItem>) {
    val valid: Int get() = items.count { it.validity.ok }
    val invalid: Int get() = items.count { !it.validity.ok }
}

/**
 * Checks after a game restart (or update) which saved references still hold — read-only, never writes:
 *  - address-list entries: module-relative and pointer entries are re-resolved; pointers that break are reported;
 *  - value cheats: still mapped/writable at the resolved address;
 *  - code patches: the bytes at the offset must be the saved "off" (or "on") bytes; if not, the saved signature is
 *    searched in the executable to detect that the code moved (e.g. a game update) — the offset is never rewritten
 *    automatically.
 */
object SessionValidator {
    suspend fun validate(
        backend: DebugBackend,
        pid: Int,
        regions: List<MemoryRegion>,
        addresses: List<WatchEntry> = emptyList(),
        profile: CheatProfile? = null,
        processName: String? = null,
    ): ValidationReport {
        val items = ArrayList<ValidationItem>()
        for (r in WatchResolver.resolveLive(backend, pid, addresses, regions)) {
            val e = r.entry
            val kind = if (e.pointer != null) ItemKind.POINTER else ItemKind.ADDRESS
            val (v, d) = when (r.status) {
                WatchStatus.MODULE_RELATIVE -> Validity.VALID to "module-relative: ${e.module?.name}+0x${java.lang.Long.toHexString(e.module?.offset ?: 0).uppercase()}"
                WatchStatus.SAME_SESSION -> Validity.VALID to "same session"
                WatchStatus.POINTER -> Validity.VALID to "${e.pointer!!.describe()} → ${Address.format(r.address)}"
                WatchStatus.ABSOLUTE_UNVERIFIED -> Validity.UNVERIFIED to "absolute address from another session; find it with a pointer scan"
                WatchStatus.POINTER_BROKEN -> Validity.INVALID to "${e.pointer!!.describe()} does not resolve now"
                WatchStatus.UNMAPPED -> Validity.INVALID to "not mapped in this process"
                WatchStatus.POINTER_UNRESOLVED -> Validity.INVALID to "pointer not resolved"
            }
            items += ValidationItem(kind, e.label, v, d, r.address.takeIf { r.status.readable })
        }
        if (profile != null) {
            if (processName != null && profile.processName != processName) {
                items += ValidationItem(ItemKind.VALUE_CHEAT, profile.name, Validity.INVALID, "profile is for ${profile.processName}, the process is $processName")
            } else {
                for (c in profile.cheats) items += if (c.isPatch) checkPatch(backend, pid, c, regions) else checkValue(pid, c, regions)
            }
        }
        return ValidationReport(pid, items)
    }

    private fun checkValue(pid: Int, c: CheatEntry, regions: List<MemoryRegion>): ValidationItem {
        val module = c.module
        val address = if (module != null) ModuleResolver.resolve(module, regions) else c.address
        if (address == null) return ValidationItem(ItemKind.VALUE_CHEAT, c.name, Validity.INVALID, "${module?.name} is not loaded")
        val region = regions.regionFor(address, c.type.size)
        return when {
            region == null -> ValidationItem(ItemKind.VALUE_CHEAT, c.name, Validity.INVALID, "${Address.format(address)} not mapped", address)
            !region.isWritable -> ValidationItem(ItemKind.VALUE_CHEAT, c.name, Validity.INVALID, "${Address.format(address)} not writable", address)
            module == null && c.discoveredPid != pid -> ValidationItem(ItemKind.VALUE_CHEAT, c.name, Validity.UNVERIFIED, "absolute address from another session", address)
            else -> ValidationItem(ItemKind.VALUE_CHEAT, c.name, Validity.VALID, "mapped and writable", address)
        }
    }

    private suspend fun checkPatch(backend: DebugBackend, pid: Int, c: CheatEntry, regions: List<MemoryRegion>): ValidationItem {
        val module = c.module ?: return ValidationItem(ItemKind.CODE_PATCH, c.name, Validity.INVALID, "absolute address; patches need an executable offset")
        val base = ModuleResolver.baseOf(module.name, regions)
            ?: return ValidationItem(ItemKind.CODE_PATCH, c.name, Validity.INVALID, "${module.name} is not loaded")
        val on = Hex.decode(c.patchBytes!!)
        val off = c.originalBytes?.let { Hex.decode(it) }
            ?: return ValidationItem(ItemKind.CODE_PATCH, c.name, Validity.INVALID, "original bytes unknown")
        val address = base + module.offset
        // Same comparison as the library ON/OFF switches (GoldHenImport.check): one implementation, one answer.
        val state = com.ps4smartcheat.core.cheat.GoldHenImport.inspect(backend, pid, 0, c.name, module.name, address, on, off, regions).state
        return when (state) {
            PatchState.OFF -> ValidationItem(ItemKind.CODE_PATCH, c.name, Validity.VALID, "original code found (OFF)", address)
            PatchState.ON -> ValidationItem(ItemKind.CODE_PATCH, c.name, Validity.ACTIVE, "patched code found (ON)", address)
            PatchState.UNMAPPED, PatchState.MISMATCH -> {
                val why = if (state == PatchState.UNMAPPED) "offset not mapped" else "bytes differ (other game version?)"
                val sig = c.signature?.takeIf { it.isNotBlank() }
                    ?: return ValidationItem(ItemKind.CODE_PATCH, c.name, Validity.INVALID, "$why; no signature saved", address)
                val code = regions.filter { (it.name == module.name || ModuleResolver.sameModule(it.name, module.name)) && it.isExecutable && it.isReadable }
                val hits = PatternSearch.search(backend, pid, code, BytePattern.hex(sig), maxHits = 3).hits
                when (hits.size) {
                    0 -> ValidationItem(ItemKind.CODE_PATCH, c.name, Validity.INVALID, "$why; signature not found", address)
                    1 -> ValidationItem(
                        ItemKind.CODE_PATCH, c.name, Validity.MOVED,
                        "$why; signature found at ${module.name}+0x${java.lang.Long.toHexString(hits[0].address - base).uppercase()} — re-create the patch there",
                        hits[0].address, hits[0].address - base,
                    )
                    else -> ValidationItem(ItemKind.CODE_PATCH, c.name, Validity.INVALID, "$why; signature matches ${hits.size}+ places (ambiguous)", address)
                }
            }
        }
    }
}

package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.util.Hex

/**
 * Code patches saved in the library are switched ON/OFF exactly like a GoldHEN checkbox mod: this turns a profile's
 * patch entries into the in-memory GoldHEN model so the same read-only state check ([GoldHenImport.check]) and the
 * confirmed, verified writes are used. Entries sharing a group become one mod.
 */
object CodeCheats {
    fun asGoldHenFile(profile: CheatProfile): GhImportFile? {
        val patches = profile.cheats.filter { it.isPatch }
        if (patches.isEmpty()) return null
        val safetyParts = ArrayList<Pair<Int, List<GoldHenSafety.Part>>>()
        val mods = CheatGroups.of(patches).mapIndexed { index, g ->
            val members = g.members
            val name = g.name
            val problems = ArrayList<String>()
            val list = members.mapNotNull { c ->
                val module = c.module
                when {
                    module == null -> { problems += "${c.name}: absolute address — save patches from the game executable"; null }
                    !ModuleResolver.sameModule(module.name, profile.processName) -> { problems += "${c.name}: in ${module.name}, not ${profile.processName}"; null }
                    c.originalBytes.isNullOrBlank() -> { problems += "${c.name}: original bytes unknown"; null }
                    else -> GhPatch(module.offset, Hex.decode(c.patchBytes!!), Hex.decode(c.originalBytes), module, c.name, c.flagFields.orEmpty())
                }
            }
            // Two parts of one mod at the same bytes cannot both match: the usual cause is a cheat saved twice under
            // one name (e.g. an injection that was edited and saved again before this version replaced the old parts).
            val sorted = list.sortedBy { it.offset }
            sorted.zipWithNext().firstOrNull { (a, b) -> a.module?.name == b.module?.name && a.offset + a.on.size > b.offset }?.let { (a, b) ->
                problems += "\"${a.label}\" and \"${b.label}\" overlap — the cheat was saved twice; delete the older entries"
            }
            if (problems.isEmpty()) {
                safetyParts += index to members.zip(list).map { (c, p) -> GoldHenSafety.Part(c.name, p.offset, p.on, p.off, GoldHenSafety.kindOf(c, members)) }
            }
            GhImportMod(name, "checkbox", list, problems)
        }.toMutableList()
        // The same safety check as the GoldHEN export: a mod whose OFF bytes are not the original code is never switched.
        val report = GoldHenSafety.check(safetyParts.map { (i, parts) -> GoldHenSafety.Mod(mods[i].name, parts) })
        safetyParts.forEachIndexed { k, (i, _) ->
            if (!report.safe(k)) mods[i] = mods[i].copy(problems = mods[i].problems + report.errors[k].map { "unsafe: $it" })
        }
        return GhImportFile(profile.name, profile.titleId.orEmpty(), profile.gameVersion.orEmpty(), profile.processName, mods, emptyList(), emptyList())
    }
}

/**
 * Library form of a code-cave injection in the game executable: the cave code as chunks of ≤ 64 bytes, then the jump
 * at the hook, all in one group so they are switched together (in this order ON, reversed OFF) and exported as one
 * GoldHEN mod. Injections into allocated memory have no stable address and cannot be stored.
 */
object InjectionEntries {
    fun build(
        inj: com.ps4smartcheat.core.asm.CodeInjection, name: String, group: String?,
        regions: List<com.ps4smartcheat.core.model.MemoryRegion>, newId: () -> String,
    ): List<CheatEntry> {
        require(name.isNotBlank()) { "Name is required" }
        require(inj.caveKind == com.ps4smartcheat.core.asm.CaveKind.MODULE_PADDING) {
            "Injections into allocated memory last only for this session and cannot be saved. Use a cave in the game executable."
        }
        val hookRef = ModuleResolver.refFor(inj.hookAddress, regions)
            ?: throw IllegalArgumentException("The hook ${com.ps4smartcheat.core.util.Address.format(inj.hookAddress)} is not inside a module image.")
        val caveRef = ModuleResolver.refFor(inj.caveAddress, regions)
            ?: throw IllegalArgumentException("The cave is not inside a module image.")
        require(ModuleResolver.sameModule(hookRef.name, caveRef.name)) { "The hook and the cave must be in the same module." }
        val g = group?.trim()?.ifEmpty { null } ?: name.trim()
        val size = com.ps4smartcheat.core.edit.MAX_PATCH_BYTES
        // A one-shot cave is saved with its flag displacements at 0 (never this session's address) and their positions;
        // parts are cut so that no displacement is split.
        val on = inj.caveTemplate
        val chunks = com.ps4smartcheat.core.asm.OneShotFlag.chunkStarts(on.size, size, inj.flagFields)
        val out = ArrayList<CheatEntry>()
        chunks.forEachIndexed { i, off ->
            val end = chunks.getOrNull(i + 1) ?: on.size
            val fields = com.ps4smartcheat.core.asm.OneShotFlag.fieldsIn(inj.flagFields, off, end)
            out += CheatEntry(
                newId(), "${name.trim()} · cave ${i + 1}/${chunks.size}", com.ps4smartcheat.core.scan.ValueType.INT8,
                inj.caveAddress + off, ModuleRef(caveRef.name, caveRef.offset + off),
                originalBytes = Hex.encode(inj.caveOff.copyOfRange(off, end)), patchBytes = Hex.encode(on.copyOfRange(off, end)),
                notes = "Code cave of \"${name.trim()}\"" + if (inj.usesFlag) " (one-shot flag)" else "", group = g,
                flagFields = fields.ifEmpty { null },
            )
        }
        out += CheatEntry(
            newId(), "${name.trim()} · jump", com.ps4smartcheat.core.scan.ValueType.INT8, inj.hookAddress, hookRef,
            originalBytes = Hex.encode(inj.hookOff), patchBytes = Hex.encode(inj.hookOn),
            notes = "Jump to the code cave at ${com.ps4smartcheat.core.util.Address.format(inj.caveAddress)}; " +
                (if (inj.replaced.isEmpty()) "original code kept" else "replaces: " + inj.replaced.joinToString("; ") { it.text }),
            assembly = inj.source, group = g, signature = inj.signature,
        )
        return out
    }

    /**
     * Parts of an injection saved earlier in [profile] under the same [name] and group: they are replaced when the
     * injection is saved again (edited Target Code), otherwise one mod would hold two caves and two jumps with
     * different bytes at the same addresses and could never match memory.
     */
    fun previousParts(profile: CheatProfile?, name: String, group: String?): List<CheatEntry> {
        val n = name.trim()
        val g = group?.trim()?.ifEmpty { null } ?: n
        return profile?.cheats.orEmpty().filter { it.isPatch && it.group == g && (it.name == "$n · jump" || it.name.startsWith("$n · cave ")) }
    }

    /** Address ranges of caves stored in [profile] (so a new injection does not reuse them). */
    fun reservedCaves(profile: CheatProfile?, regions: List<com.ps4smartcheat.core.model.MemoryRegion>): List<LongRange> =
        profile?.cheats.orEmpty().filter { it.isPatch && it.notes.startsWith("Code cave of") }.mapNotNull { c ->
            val a = c.module?.let { ModuleResolver.resolve(it, regions) } ?: return@mapNotNull null
            a until a + c.patchBytes!!.length / 2
        }
}

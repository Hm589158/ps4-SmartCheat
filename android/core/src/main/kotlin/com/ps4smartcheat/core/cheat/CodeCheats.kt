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
        val units = LinkedHashMap<String, MutableList<CheatEntry>>()
        for (c in patches) units.getOrPut(c.group?.trim()?.ifEmpty { null }?.let { "g:$it" } ?: "c:${c.id}") { ArrayList() } += c
        val mods = units.map { (key, members) ->
            val name = if (key.startsWith("g:")) key.removePrefix("g:") else members.single().name
            val problems = ArrayList<String>()
            val list = members.mapNotNull { c ->
                val module = c.module
                when {
                    module == null -> { problems += "${c.name}: absolute address — save patches from the game executable"; null }
                    !ModuleResolver.sameModule(module.name, profile.processName) -> { problems += "${c.name}: in ${module.name}, not ${profile.processName}"; null }
                    c.originalBytes.isNullOrBlank() -> { problems += "${c.name}: original bytes unknown"; null }
                    else -> GhPatch(module.offset, Hex.decode(c.patchBytes!!), Hex.decode(c.originalBytes))
                }
            }
            GhImportMod(name, "checkbox", list, problems)
        }
        return GhImportFile(profile.name, profile.titleId.orEmpty(), profile.gameVersion.orEmpty(), profile.processName, mods, emptyList(), emptyList())
    }
}

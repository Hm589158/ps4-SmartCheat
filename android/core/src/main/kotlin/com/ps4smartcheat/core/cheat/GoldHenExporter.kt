package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.util.Hex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Export to the GoldHEN cheat JSON layout used by github.com/GoldHEN/GoldHEN_Cheat_Repository:
 *
 * ```
 * { "name", "id" (title id), "version" (e.g. "01.00"), "process" (e.g. "eboot.bin"),
 *   "mods": [ { "name", "type": "checkbox", "memory": [ { "offset": hex, "on": hex bytes, "off": hex bytes } ] } ],
 *   "credits": [ ... ] }
 * ```
 *
 * Address semantics: "offset" is hexadecimal and relative to the start of the game executable image.
 * This interpretation is taken from PS4CheaterNeo's reader/writer (GoldHEN's own loader is closed source),
 * so files produced here are marked UNVERIFIED on hardware in docs/COMPATIBILITY.md.
 * Fields with unknown semantics in the repository ("section", "master", "hint") are never emitted.
 */
object GoldHenExporter {
    @Serializable
    data class GhMemory(val offset: String, val on: String, val off: String)

    @Serializable
    data class GhMod(val name: String, val type: String, val memory: List<GhMemory>)

    @Serializable
    data class GhFile(
        val name: String,
        val id: String,
        val version: String,
        val process: String,
        val mods: List<GhMod>,
        val credits: List<String>,
    )

    data class Result(val json: String?, val exported: Int, val skipped: List<String>, val errors: List<String>)

    private val TITLE_ID = Regex("^[A-Z]{4}\\d{5}$")
    private val VERSION = Regex("^\\d{2}\\.\\d{2}$")
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun export(profile: CheatProfile, gameName: String, credits: List<String>): Result {
        val errors = ArrayList<String>()
        val titleId = profile.titleId?.uppercase()
        if (titleId == null || !TITLE_ID.matches(titleId)) errors += "A title ID like CUSA12345 is required (profile has \"${profile.titleId ?: ""}\")."
        val version = profile.gameVersion
        if (version == null || !VERSION.matches(version)) errors += "A game version like 01.00 is required (profile has \"${version ?: ""}\")."
        if (gameName.isBlank()) errors += "A game name is required."
        if (errors.isNotEmpty()) return Result(null, 0, emptyList(), errors)

        val skipped = ArrayList<String>()
        val mods = ArrayList<GhMod>()
        for (c in profile.cheats) {
            val module = c.module
            when {
                module == null -> skipped += "${c.name}: absolute address (not inside the game executable) — GoldHEN needs an executable offset."
                module.name != profile.processName -> skipped += "${c.name}: address is in module ${module.name}, not ${profile.processName}."
                c.replacementValue.isNullOrBlank() -> skipped += "${c.name}: no replacement value."
                c.originalBytes.isNullOrBlank() -> skipped += "${c.name}: original bytes unknown — \"off\" cannot be filled without inventing data."
                else -> {
                    val on = Hex.encode(ValueCodec.encode(ValueCodec.parse(c.type, c.replacementValue)))
                    val off = c.originalBytes.uppercase().filterNot { it.isWhitespace() }
                    mods += GhMod(c.name, "checkbox", listOf(GhMemory(java.lang.Long.toHexString(module.offset).uppercase(), on, off)))
                }
            }
        }
        if (mods.isEmpty()) return Result(null, 0, skipped, listOf("No cheat in this profile can be exported to GoldHEN format."))
        val file = GhFile(gameName.trim(), titleId!!, version!!, profile.processName, mods, credits.map { it.trim() }.filter { it.isNotEmpty() })
        return Result(json.encodeToString(GhFile.serializer(), file), mods.size, skipped, emptyList())
    }

    /** File name convention of the GoldHEN repository: `<TITLEID>_<VERSION>.json`. */
    fun fileName(profile: CheatProfile): String = "${profile.titleId?.uppercase()}_${profile.gameVersion}.json"
}

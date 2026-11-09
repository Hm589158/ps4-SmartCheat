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

    fun isValidTitleId(id: String?): Boolean = id != null && TITLE_ID.matches(id.uppercase())
    fun isValidVersion(v: String?): Boolean = v != null && VERSION.matches(v)

    /**
     * Builds the GoldHEN file. Value cheats and code patches inside the game executable become checkbox mods;
     * entries sharing a [CheatEntry.group] become one mod (toggled together). Everything GoldHEN cannot express
     * (heap/absolute addresses, other modules, unknown original bytes) is skipped with a reason; a group with any
     * skipped member is skipped as a whole so a mod is never exported half.
     *
     * @param runningTitleId title ID of the connected game, when known: exporting a profile for another game fails.
     */
    fun export(profile: CheatProfile, gameName: String, credits: List<String>, runningTitleId: String? = null): Result {
        val errors = ArrayList<String>()
        val titleId = profile.titleId?.uppercase()
        if (!isValidTitleId(titleId)) errors += "A title ID like CUSA12345 is required (profile has \"${profile.titleId ?: ""}\")."
        val version = profile.gameVersion
        if (!isValidVersion(version)) errors += "A game version like 01.00 is required (profile has \"${version ?: ""}\")."
        if (gameName.isBlank()) errors += "A game name is required."
        if (titleId != null && !runningTitleId.isNullOrBlank() && runningTitleId.uppercase() != titleId) {
            errors += "The profile is for $titleId but the running game is ${runningTitleId.uppercase()}."
        }
        if (errors.isNotEmpty()) return Result(null, 0, emptyList(), errors)

        val skipped = ArrayList<String>()
        // Preserve the profile order of first appearance for groups.
        val units = LinkedHashMap<String, MutableList<CheatEntry>>()
        for (c in profile.cheats) units.getOrPut(c.group?.trim()?.ifEmpty { null }?.let { "g:$it" } ?: "c:${c.id}") { ArrayList() } += c
        val mods = ArrayList<GhMod>()
        for ((key, members) in units) {
            val memory = ArrayList<GhMemory>()
            val reasons = ArrayList<String>()
            for (c in members) {
                val r = memoryFor(c, profile.processName)
                if (r.isSuccess) memory += r.getOrThrow() else reasons += "${c.name}: ${r.exceptionOrNull()!!.message}"
            }
            val name = if (key.startsWith("g:")) key.removePrefix("g:") else members.single().name
            if (reasons.isNotEmpty()) {
                skipped += if (key.startsWith("g:")) "Group \"$name\" skipped — " + reasons.joinToString("; ") else reasons.single()
                continue
            }
            val overlap = memory.sortedBy { it.offset.toLong(16) }.zipWithNext().firstOrNull { (a, b) -> a.offset.toLong(16) + a.on.length / 2 > b.offset.toLong(16) }
            if (overlap != null) { skipped += "\"$name\": two patches overlap at offset ${overlap.second.offset}."; continue }
            mods += GhMod(name, "checkbox", memory)
        }
        if (mods.isEmpty()) return Result(null, 0, skipped, listOf("No cheat in this profile can be exported to GoldHEN format."))
        val file = GhFile(gameName.trim(), titleId!!, version!!, profile.processName, mods, credits.map { it.trim() }.filter { it.isNotEmpty() })
        return Result(json.encodeToString(GhFile.serializer(), file), mods.size, skipped, emptyList())
    }

    private fun memoryFor(c: CheatEntry, processName: String): kotlin.Result<GhMemory> = runCatching {
        val module = c.module ?: error("absolute address (not inside the game executable) — GoldHEN needs an executable offset.")
        if (!ModuleResolver.sameModule(module.name, processName)) error("address is in module ${module.name}, not $processName.")
        if (module.offset < 0) error("negative executable offset.")
        val off = c.originalBytes?.uppercase()?.filterNot { it.isWhitespace() }
            ?.takeIf { it.isNotEmpty() } ?: error("original bytes unknown — \"off\" cannot be filled without inventing data.")
        val on = if (c.isPatch) {
            c.patchBytes!!.uppercase().filterNot { it.isWhitespace() }
        } else {
            if (c.replacementValue.isNullOrBlank()) error("no replacement value.")
            Hex.encode(ValueCodec.encode(ValueCodec.parse(c.type, c.replacementValue)))
        }
        if (on.length != off.length) error("on and off bytes have different lengths.")
        if (on.length / 2 > com.ps4smartcheat.core.edit.MAX_PATCH_BYTES) error("patch longer than ${com.ps4smartcheat.core.edit.MAX_PATCH_BYTES} bytes.")
        if (on == off) error("on and off bytes are identical.")
        GhMemory(java.lang.Long.toHexString(module.offset).uppercase(), on, off)
    }

    /** File name convention of the GoldHEN repository: `<TITLEID>_<VERSION>.json`. */
    fun fileName(profile: CheatProfile): String = "${profile.titleId?.uppercase()}_${profile.gameVersion}.json"
}

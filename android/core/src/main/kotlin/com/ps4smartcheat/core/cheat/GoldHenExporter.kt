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

    /** Where GoldHEN reads JSON cheat files (GoldHEN_Cheat_Repository README, "Storage"). */
    const val CHEATS_DIR = "/user/data/GoldHEN/cheats/json/"
    /** Process of games without a per-process suffix in the file name. */
    const val MAIN_PROCESS = "eboot.bin"

    fun isValidTitleId(id: String?): Boolean = id != null && TITLE_ID.matches(id.uppercase())
    fun isValidVersion(v: String?): Boolean = v != null && VERSION.matches(v)

    /** Result of [normalizeVersion]. */
    sealed interface VersionCheck {
        /** [version] is in GoldHEN form ("01.25"); [changed] when the input was written differently ("1.25"). */
        data class Ok(val version: String, val changed: Boolean) : VersionCheck
        /** Not usable as typed; [suggestions] are the GoldHEN forms it may have meant (e.g. "1.5" → 01.05 / 01.50). */
        data class Invalid(val reason: String, val suggestions: List<String> = emptyList()) : VersionCheck
    }

    /**
     * Brings a game version into the "XX.YY" form used by GoldHEN file names and the `version` field
     * (the PS4's APP_VER format): `1.25` → `01.25`, `v01.25` / `1,25` / ` 01.25 ` → `01.25`, `2` → `02.00`.
     * A one-digit minor part other than 0 (`1.5`) is ambiguous (01.05 or 01.50) and is never guessed.
     */
    fun normalizeVersion(input: String?): VersionCheck {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return VersionCheck.Invalid("A game version like 01.25 is required (shown on the game's start screen or in its Information).")
        val t = raw.removePrefix("v").removePrefix("V").replace(',', '.').trim()
        Regex("^(\\d{1,2})(?:\\.(\\d{1,2}))?$").matchEntire(t)?.let { m ->
            val major = m.groupValues[1].padStart(2, '0')
            val minor = m.groupValues[2]
            val v = when {
                minor.isEmpty() -> "$major.00"
                minor.length == 2 -> "$major.$minor"
                minor == "0" -> "$major.00"
                else -> return VersionCheck.Invalid(
                    "\"$raw\" is ambiguous: GoldHEN versions have two digits after the dot.",
                    listOf("$major.0$minor", "$major.${minor}0"),
                )
            }
            return VersionCheck.Ok(v, v != raw)
        }
        return VersionCheck.Invalid("\"$raw\" is not a game version like 01.25.")
    }

    /** Title ID in upper case without spaces or dashes ("cusa-05294" → "CUSA05294"); null when it is not one. */
    fun normalizeTitleId(input: String?): String? =
        input?.uppercase()?.filterNot { it.isWhitespace() || it == '-' || it == '_' }?.takeIf { TITLE_ID.matches(it) }

    /** Why [process] cannot be part of a file name, or null. */
    fun processProblem(process: String): String? = when {
        process.isBlank() -> "The profile has no process name."
        process.any { it == '/' || it == '\\' || it.isISOControl() } || process == "." || process == ".." -> "Process name \"$process\" cannot be used in a file name."
        else -> null
    }

    /**
     * GoldHEN file name, per the official repository README: `{titleid}_{version}.json` for cheats on eboot.bin and
     * `{titleid}_{version}_{process}.json` for any other process (e.g. `CUSA05294_01.25_default.elf.json`).
     * The game name is never part of it.
     */
    fun fileName(titleId: String, version: String, process: String): String =
        if (process == MAIN_PROCESS) "${titleId}_$version.json" else "${titleId}_${version}_$process.json"

    /** Everything the export dialog shows before anything is written. */
    data class Preview(
        val titleId: String?,
        val version: VersionCheck,
        val process: String,
        /** Null while the title ID or version is not usable. */
        val fileName: String?,
        val exportable: Int,
        val skipped: List<String>,
        /** Blocking problems (empty → Export is allowed). */
        val problems: List<String>,
    ) {
        val uploadPath: String? get() = fileName?.let { CHEATS_DIR + it }
        val ok: Boolean get() = problems.isEmpty() && fileName != null && exportable > 0
    }

    /**
     * Checks an export with the values typed in the dialog ([titleIdInput], [versionInput]; normalised here) without
     * building the file, so the dialog can show the file name, what is exported and what blocks it.
     */
    fun preview(profile: CheatProfile, titleIdInput: String?, versionInput: String?, gameName: String, runningTitleId: String? = null): Preview {
        val problems = ArrayList<String>()
        val title = normalizeTitleId(titleIdInput)
        if (title == null) problems += "A title ID like CUSA05294 is required" + (titleIdInput?.takeIf { it.isNotBlank() }?.let { " (\"$it\" is not one)." } ?: ".")
        val version = normalizeVersion(versionInput)
        if (version is VersionCheck.Invalid) problems += version.reason
        processProblem(profile.processName)?.let { problems += it }
        if (gameName.isBlank()) problems += "A game name is required."
        if (title != null && !runningTitleId.isNullOrBlank() && runningTitleId.uppercase() != title) {
            problems += "The profile is for $title but the running game is ${runningTitleId.uppercase()}."
        }
        val units = units(profile)
        val skipped = ArrayList<String>()
        var exportable = 0
        for ((key, members) in units) {
            val r = modFor(key, members, profile.processName)
            if (r.isSuccess) exportable++ else skipped += r.exceptionOrNull()!!.message!!
        }
        if (exportable == 0) problems += "No cheat in this profile can be exported to GoldHEN format."
        val name = if (title != null && version is VersionCheck.Ok && processProblem(profile.processName) == null) fileName(title, version.version, profile.processName) else null
        return Preview(title, version, profile.processName, name, exportable, skipped, problems)
    }

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
        val titleId = normalizeTitleId(profile.titleId)
        if (titleId == null) errors += "A title ID like CUSA12345 is required (profile has \"${profile.titleId ?: ""}\")."
        val version = when (val v = normalizeVersion(profile.gameVersion)) {
            is VersionCheck.Ok -> v.version
            is VersionCheck.Invalid -> { errors += v.reason + if (v.suggestions.isNotEmpty()) " Use ${v.suggestions.joinToString(" or ")}." else ""; null }
        }
        processProblem(profile.processName)?.let { errors += it }
        if (gameName.isBlank()) errors += "A game name is required."
        if (titleId != null && !runningTitleId.isNullOrBlank() && runningTitleId.uppercase() != titleId) {
            errors += "The profile is for $titleId but the running game is ${runningTitleId.uppercase()}."
        }
        if (errors.isNotEmpty()) return Result(null, 0, emptyList(), errors)

        val skipped = ArrayList<String>()
        val mods = ArrayList<GhMod>()
        for ((key, members) in units(profile)) {
            val r = modFor(key, members, profile.processName)
            if (r.isSuccess) mods += r.getOrThrow() else skipped += r.exceptionOrNull()!!.message!!
        }
        if (mods.isEmpty()) return Result(null, 0, skipped, listOf("No cheat in this profile can be exported to GoldHEN format."))
        val file = GhFile(gameName.trim(), titleId!!, version!!, profile.processName, mods, credits.map { it.trim() }.filter { it.isNotEmpty() })
        return Result(json.encodeToString(GhFile.serializer(), file), mods.size, skipped, emptyList())
    }

    /** Cheats grouped into mods, in profile order of first appearance (ungrouped cheats are their own mod). */
    private fun units(profile: CheatProfile): Map<String, List<CheatEntry>> {
        val units = LinkedHashMap<String, MutableList<CheatEntry>>()
        for (c in profile.cheats) units.getOrPut(c.group?.trim()?.ifEmpty { null }?.let { "g:$it" } ?: "c:${c.id}") { ArrayList() } += c
        return units
    }

    /** One mod, or a failure whose message says why it is skipped (a group is skipped as a whole). */
    private fun modFor(key: String, members: List<CheatEntry>, processName: String): kotlin.Result<GhMod> {
        val memory = ArrayList<GhMemory>()
        val reasons = ArrayList<String>()
        for (c in members) {
            val r = memoryFor(c, processName)
            if (r.isSuccess) memory += r.getOrThrow() else reasons += "${c.name}: ${r.exceptionOrNull()!!.message}"
        }
        val name = if (key.startsWith("g:")) key.removePrefix("g:") else members.single().name
        if (reasons.isNotEmpty()) {
            return kotlin.Result.failure(IllegalStateException(if (key.startsWith("g:")) "Group \"$name\" skipped — " + reasons.joinToString("; ") else reasons.single()))
        }
        val overlap = memory.sortedBy { it.offset.toLong(16) }.zipWithNext().firstOrNull { (a, b) -> a.offset.toLong(16) + a.on.length / 2 > b.offset.toLong(16) }
        if (overlap != null) return kotlin.Result.failure(IllegalStateException("\"$name\": two patches overlap at offset ${overlap.second.offset}."))
        return kotlin.Result.success(GhMod(name, "checkbox", memory))
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

    /** [fileName] for a profile (title ID and version normalised); null when they are not usable. */
    fun fileName(profile: CheatProfile): String? {
        val t = normalizeTitleId(profile.titleId) ?: return null
        val v = (normalizeVersion(profile.gameVersion) as? VersionCheck.Ok)?.version ?: return null
        if (processProblem(profile.processName) != null) return null
        return fileName(t, v, profile.processName)
    }
}

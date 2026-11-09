package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.edit.MAX_PATCH_BYTES
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.util.Hex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One `memory` entry: bytes at executable-relative [offset]. */
data class GhPatch(val offset: Long, val on: ByteArray, val off: ByteArray)

/** One `mods` entry. [problems] non-empty → the mod cannot be applied (it is still listed). */
data class GhImportMod(val name: String, val type: String, val patches: List<GhPatch>, val problems: List<String>) {
    val applicable: Boolean get() = problems.isEmpty() && patches.isNotEmpty()
}

data class GhImportFile(
    val name: String,
    val titleId: String,
    val version: String,
    val process: String,
    val mods: List<GhImportMod>,
    val credits: List<String>,
    /** File-level notes (ignored fields etc.). */
    val notes: List<String>,
)

enum class PatchState(val label: String) {
    /** Memory holds the "off" bytes: the cheat is not active and the file matches this game build. */
    OFF("off"),
    /** Memory holds the "on" bytes: the cheat is active. */
    ON("on"),
    /** Neither: another game version/region, or the game changed the memory. Never written. */
    MISMATCH("does not match this game build"),
    UNMAPPED("address not mapped"),
}

data class ModCheck(val mod: GhImportMod, val addresses: List<Long>, val states: List<PatchState>) {
    val state: PatchState? get() = states.distinct().singleOrNull()
    private val known: Boolean get() = mod.applicable && states.isNotEmpty() && states.all { it == PatchState.OFF || it == PatchState.ON }
    /**
     * Enabling is allowed when every patch is OFF or already ON (an interrupted toggle) and at least one is OFF;
     * disabling likewise. A patch in any other state (MISMATCH, UNMAPPED) blocks both.
     */
    val canEnable: Boolean get() = known && states.any { it == PatchState.OFF }
    val canDisable: Boolean get() = known && states.any { it == PatchState.ON }
    /** True when only some patches are ON (left by an interrupted toggle). */
    val partial: Boolean get() = known && states.distinct().size == 2

    /**
     * Indices of the patches to write, in write order: ON in file order, OFF in reverse. Patches already in the
     * target state are skipped. The reverse order matters for code-cave injections (cave chunks first, jump last):
     * switching OFF removes the jump before the cave code.
     */
    fun writeOrder(enable: Boolean): List<Int> {
        val want = if (enable) PatchState.OFF else PatchState.ON
        val idx = states.indices.filter { states[it] == want }
        return if (enable) idx else idx.asReversed()
    }
}

/**
 * Reader for GoldHEN cheat JSON files (layout as in GoldHEN_Cheat_Repository; see [GoldHenExporter]). Offsets are
 * taken as relative to the start of the game executable image, the interpretation used by PS4CheaterNeo.
 * Entries with fields whose meaning is unknown ("section" other than 0) are listed but not applicable.
 */
object GoldHenImport {
    class FormatException(message: String) : Exception(message)

    private const val MAX_FILE_CHARS = 2 * 1024 * 1024

    fun parse(text: String): GhImportFile {
        if (text.length > MAX_FILE_CHARS) throw FormatException("File is too large for a cheat file")
        val root = try { CheatJson.json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
            throw FormatException("Not a JSON object")
        }
        if (root["format"]?.let { (it as? JsonPrimitive)?.content } == CheatLibraryFile.FORMAT_ID) {
            throw FormatException("This is a PS4 SmartCheat library file; use Import in the cheat library instead.")
        }
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val mods = root["mods"] as? JsonArray ?: throw FormatException("Not a GoldHEN cheat file (no \"mods\" list)")
        val process = str(root, "process")?.trim().orEmpty()
        if (process.isEmpty()) throw FormatException("The file does not name the game process (\"process\")")
        val notes = ArrayList<String>()
        for (k in listOf("master", "hint")) if (root.containsKey(k)) notes += "Field \"$k\" is ignored (meaning not verified)."
        val parsed = mods.mapIndexed { i, m ->
            val o = m as? JsonObject ?: return@mapIndexed GhImportMod("mod ${i + 1}", "?", emptyList(), listOf("not an object"))
            val name = str(o, "name")?.trim()?.ifEmpty { null } ?: "mod ${i + 1}"
            val type = str(o, "type") ?: "checkbox"
            val problems = ArrayList<String>()
            if (type != "checkbox") problems += "type \"$type\" is not supported (only checkbox)"
            val patches = ArrayList<GhPatch>()
            val mem = o["memory"] as? JsonArray
            if (mem == null || mem.isEmpty()) problems += "no memory entries"
            mem?.forEachIndexed { j, e ->
                val eo = e as? JsonObject
                if (eo == null) { problems += "memory ${j + 1}: not an object"; return@forEachIndexed }
                val section = eo["section"]?.let { (it as? JsonPrimitive)?.content }
                if (section != null && section != "0") { problems += "memory ${j + 1}: \"section\" $section is not supported"; return@forEachIndexed }
                val offText = str(eo, "offset")?.trim()?.removePrefix("0x")?.removePrefix("0X")
                val offset = offText?.toLongOrNull(16)
                val on = str(eo, "on")?.let { runCatching { Hex.decode(it) }.getOrNull() }
                val off = str(eo, "off")?.let { runCatching { Hex.decode(it) }.getOrNull() }
                when {
                    offset == null || offset < 0 -> problems += "memory ${j + 1}: invalid offset"
                    on == null || off == null || on.isEmpty() -> problems += "memory ${j + 1}: invalid on/off bytes"
                    on.size != off.size -> problems += "memory ${j + 1}: on and off have different lengths"
                    on.size > MAX_PATCH_BYTES -> problems += "memory ${j + 1}: ${on.size} bytes (max $MAX_PATCH_BYTES per patch)"
                    else -> patches += GhPatch(offset, on, off)
                }
            }
            GhImportMod(name, type, patches, problems)
        }
        val credits = (root["credits"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        return GhImportFile(
            str(root, "name")?.trim().orEmpty(), str(root, "id")?.trim()?.uppercase().orEmpty(),
            str(root, "version")?.trim().orEmpty(), process, parsed, credits, notes,
        )
    }

    /** Checks whether the file belongs to the running game (title ID and process name). Null when it does. */
    fun mismatch(file: GhImportFile, titleId: String?, processName: String): String? = when {
        titleId != null && file.titleId.isNotEmpty() && file.titleId != titleId.uppercase() -> "The file is for ${file.titleId}, the running game is $titleId."
        file.process != processName -> "The file patches ${file.process}, the selected process is $processName."
        else -> null
    }

    /**
     * Reads the current bytes of every patch of [mod] (mapped memory only) and classifies them. Read-only.
     * The executable base is the lowest mapping of [GhImportFile.process].
     */
    suspend fun check(backend: DebugBackend, pid: Int, file: GhImportFile, mod: GhImportMod, regions: List<MemoryRegion>): ModCheck {
        val base = ModuleResolver.baseOf(file.process, regions)
        val addresses = ArrayList<Long>()
        val states = ArrayList<PatchState>()
        for (p in mod.patches) {
            val a = base?.plus(p.offset)
            addresses += a ?: -1L
            if (a == null || regions.regionFor(a, p.on.size)?.isReadable != true) { states += PatchState.UNMAPPED; continue }
            val now = backend.readMemory(pid, a, p.on.size)
            states += when {
                now.contentEquals(p.off) -> PatchState.OFF
                now.contentEquals(p.on) -> PatchState.ON
                else -> PatchState.MISMATCH
            }
        }
        return ModCheck(mod, addresses, states)
    }
}

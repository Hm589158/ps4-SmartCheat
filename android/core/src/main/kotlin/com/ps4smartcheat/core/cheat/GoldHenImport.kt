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

/**
 * One `memory` entry: bytes at executable-relative [offset].
 *
 * Patches built from the cheat library also carry the saved [module] reference (the map name the address was in when
 * it was saved, e.g. `executable`) and are resolved through it — exactly like the session check — instead of through
 * the process name. [label] names the part in the UI ("… · cave 1/1", "… · jump").
 */
data class GhPatch(val offset: Long, val on: ByteArray, val off: ByteArray, val module: ModuleRef? = null, val label: String? = null)

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

/** Content identity of a file's mods (names, locations, bytes): two files with the same fingerprint check the same. */
fun GhImportFile.fingerprint(): String = process + "|" + mods.joinToString("|") { m ->
    m.name + ":" + m.problems.size + ":" + m.patches.joinToString(",") { p ->
        "${p.module?.name}+${p.offset}=${Hex.encode(p.on)}/${Hex.encode(p.off)}"
    }
}

enum class PatchState(val label: String) {
    /** Memory holds the "off" bytes: the cheat is not active and the file matches this game build. */
    OFF("off"),
    /** Memory holds the "on" bytes: the cheat is active. */
    ON("on"),
    /** Neither: another game version/region, or the game changed the memory. Never written. */
    MISMATCH("does not match this game build"),
    UNMAPPED("address not mapped"),
}

/** What was found for one patch of a mod: where it was looked for, what was expected and what memory holds. */
class PartCheck(
    val index: Int,
    val label: String,
    /** How the address was computed, e.g. `executable+0x2006` (base 0x000400000). */
    val location: String,
    /** -1 when the module is not loaded. */
    val address: Long,
    val state: PatchState,
    val on: ByteArray,
    val off: ByteArray,
    /** Bytes read now (null when unmapped). */
    val actual: ByteArray?,
) {
    /** Why this part is not simply ON or OFF; null when it is. */
    val reason: String? get() = when (state) {
        PatchState.ON, PatchState.OFF -> null
        PatchState.UNMAPPED -> if (address == -1L) "the module is not loaded in this process" else "the address is not mapped (readable) in this process"
        PatchState.MISMATCH -> {
            val a = actual ?: ByteArray(0)
            val firstOn = a.indices.firstOrNull { a[it] != on.getOrNull(it) }
            val firstOff = a.indices.firstOrNull { a[it] != off.getOrNull(it) }
            "memory holds neither the saved ON nor the OFF bytes (first difference: byte ${firstOff ?: 0} from OFF, byte ${firstOn ?: 0} from ON). " +
                "Another game version, another patch at this address, or the cheat was saved again with different code."
        }
    }
}

data class ModCheck(val mod: GhImportMod, val addresses: List<Long>, val states: List<PatchState>, val parts: List<PartCheck> = emptyList()) {
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
        val parts = mod.patches.mapIndexed { i, p ->
            // Library patches: the saved module reference (same resolution as SessionValidator). GoldHEN files: the
            // game image of the file's process.
            val moduleName = p.module?.name ?: file.process
            val base = if (p.module != null) ModuleResolver.baseOf(p.module.name, regions) else ModuleResolver.imageBaseOf(file.process, regions)
            val offset = p.module?.offset ?: p.offset
            val location = "$moduleName+0x${java.lang.Long.toHexString(offset).uppercase()}" +
                (base?.let { " (base ${com.ps4smartcheat.core.util.Address.format(it)})" } ?: "")
            inspect(backend, pid, i, p.label ?: "patch ${i + 1}", location, base?.plus(offset), p.on, p.off, regions)
        }
        return ModCheck(mod, parts.map { it.address }, parts.map { it.state }, parts)
    }

    /**
     * Reads `on.size` bytes at [address] and classifies them. The one place where saved code bytes are compared with
     * memory: used by the library switches, by imported GoldHEN files and by [com.ps4smartcheat.core.validate.SessionValidator].
     */
    suspend fun inspect(
        backend: DebugBackend, pid: Int, index: Int, label: String, location: String, address: Long?,
        on: ByteArray, off: ByteArray, regions: List<MemoryRegion>,
    ): PartCheck {
        if (address == null) return PartCheck(index, label, location, -1L, PatchState.UNMAPPED, on, off, null)
        if (regions.regionFor(address, on.size)?.isReadable != true) return PartCheck(index, label, location, address, PatchState.UNMAPPED, on, off, null)
        val now = backend.readMemory(pid, address, on.size)
        val state = when {
            now.contentEquals(off) -> PatchState.OFF
            now.contentEquals(on) -> PatchState.ON
            else -> PatchState.MISMATCH
        }
        return PartCheck(index, label, location, address, state, on, off, now)
    }
}

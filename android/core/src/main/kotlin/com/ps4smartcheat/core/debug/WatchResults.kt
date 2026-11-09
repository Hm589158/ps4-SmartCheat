package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.cheat.HexAddressSerializer
import com.ps4smartcheat.core.cheat.ModuleRef
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.util.AtomicFiles
import com.ps4smartcheat.core.util.Hex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One instruction from a finished watch session, stored so it can be opened later without the debugger. */
@Serializable
data class SavedAccess(
    @Serializable(with = HexAddressSerializer::class) val instructionAddress: Long,
    /** Executable-relative location, so the instruction can be found again after a restart. */
    val module: ModuleRef? = null,
    /** Instruction bytes (hex) and Intel text at capture time; null when the instruction was not decoded. */
    val bytes: String? = null,
    val text: String? = null,
    val count: Int,
    val thread: String,
    /** Register name → value (hex) at the most recent hit. */
    val registers: Map<String, String> = emptyMap(),
    @Serializable(with = HexAddressSerializer::class) val effectiveAddress: Long? = null,
    /** Callers at the first hit (see [CallStack]); empty when none was captured. */
    val callStack: List<StackFrame> = emptyList(),
) {
    /** Address in the current session: via the module when known, else the address from capture time. */
    fun resolve(regions: List<MemoryRegion>): Long = module?.let { ModuleResolver.resolve(it, regions) } ?: instructionAddress
}

@Serializable
data class SavedWatchSession(
    val id: String,
    val gameKey: String,
    val processName: String,
    val pid: Int,
    @Serializable(with = HexAddressSerializer::class) val address: Long,
    val size: Int,
    val type: WatchType,
    val captureOnce: Boolean,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val hits: Int,
    val stopReason: String,
    /** True when the cleanup confirmed that the session was released on the PS4. */
    val released: Boolean,
    val records: List<SavedAccess>,
)

object WatchResults {
    fun toSaved(r: AccessRecord, regions: List<MemoryRegion>): SavedAccess = SavedAccess(
        instructionAddress = r.instructionAddress,
        module = ModuleResolver.refFor(r.instructionAddress, regions),
        bytes = r.instruction?.let { Hex.encode(it.bytes) },
        text = r.instruction?.text,
        count = r.count,
        thread = r.threadName,
        registers = r.registers.general.associate { (n, v) -> n to "0x" + java.lang.Long.toHexString(v).uppercase() },
        effectiveAddress = r.effectiveAddress,
        callStack = r.callStack.orEmpty(),
    )
}

/** Recent watch sessions (all games), newest first, in one JSON file written atomically. */
class WatchResultStore(private val file: File, private val limit: Int = 30) {
    @Serializable
    private data class FileModel(val format: String = FORMAT, val version: Int = 1, val sessions: List<SavedWatchSession> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Synchronized
    fun load(): List<SavedWatchSession> = try {
        if (!file.exists()) emptyList() else json.decodeFromString(FileModel.serializer(), file.readText())
            .takeIf { it.format == FORMAT }?.sessions.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun add(session: SavedWatchSession): List<SavedWatchSession> =
        (listOf(session) + load().filterNot { it.id == session.id }).take(limit).also(::write)

    @Synchronized
    fun delete(id: String): List<SavedWatchSession> = load().filterNot { it.id == id }.also(::write)

    @Synchronized
    fun clear() { file.delete() }

    private fun write(list: List<SavedWatchSession>) {
        file.parentFile?.mkdirs()
        AtomicFiles.writeText(file, json.encodeToString(FileModel.serializer(), FileModel(sessions = list)))
    }

    companion object { const val FORMAT = "ps4-smartcheat-watch-results" }
}

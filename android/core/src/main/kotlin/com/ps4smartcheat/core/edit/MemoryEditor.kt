package com.ps4smartcheat.core.edit

import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.backend.WriteStatus
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.protocol.Ps4DebugProtocol
import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.scan.ValueType
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Safety settings. The default is read-only. */
data class EditPolicy(
    val editingEnabled: Boolean = false,
    /** Allow writes into mapped regions without the write protection bit (e.g. code patches). */
    val allowNonWritableRegions: Boolean = false,
    /** Allow writes into processes that look like system processes. */
    val allowSystemProcesses: Boolean = false,
)

object ProcessSafety {
    private val knownSystem = setOf(
        "mini-syscore.elf", "syscore.elf", "orbis_audiod.elf", "GnmCompositor.elf", "fs_cleaner.elf",
        "kernel", "SceSysCore.elf", "SceShellCore", "SceShellUI", "SceVdecProxy.elf", "SceVencProxy.elf",
    )

    /** Heuristic: PS4 system processes are named `Sce*` or are well-known daemons. Games are usually `eboot.bin`. */
    fun isSystemProcess(name: String): Boolean = name in knownSystem || name.startsWith("Sce")
}

class EditDeniedException(message: String) : Exception(message)

/** [length] is the number of bytes written: the value type's size, or the byte count of a raw patch. */
data class EditTarget(val pid: Int, val processName: String, val address: Long, val type: ValueType, val length: Int = type.size)

/** One verified write, kept for undo/redo within the session (never persisted, never re-applied automatically). */
data class HistoryItem(val target: EditTarget, val before: ByteArray, val after: ByteArray, val label: String, val timestampMillis: Long)

data class WritePlan(
    val target: EditTarget,
    val region: MemoryRegion,
    val original: ByteArray,
    val replacement: ByteArray,
    val replacementText: String,
    val warnings: List<String>,
) {
    /** A plan with warnings must be confirmed explicitly by the user. */
    val dangerous: Boolean get() = warnings.isNotEmpty()
}

sealed interface WriteOutcome {
    val message: String

    data class Verified(val readBack: ByteArray) : WriteOutcome {
        override val message = "Write verified by fresh read"
    }

    data class Mismatch(val expected: ByteArray, val actual: ByteArray) : WriteOutcome {
        override val message = "Verification failed: wrote ${Hex.encode(expected, " ")} but read ${Hex.encode(actual, " ")}. " +
            "The game may have overwritten the value or the write did not apply."
    }

    data class Rejected(val status: Long) : WriteOutcome {
        override val message = "PS4 rejected the write (${Ps4DebugProtocol.statusName(status)}). Memory may be partially modified; " +
            "use Restore."
    }

    data class Failed(override val message: String) : WriteOutcome
}

data class JournalEntry(
    val pid: Int,
    val processName: String,
    val address: Long,
    val type: ValueType,
    /** Bytes present before the first modification made by this app. */
    val original: ByteArray,
    val lastWritten: ByteArray,
    val timestampMillis: Long,
)

/**
 * Controlled memory editing: validate → read original → (user confirmation) → write → fresh read-back.
 * Original bytes are journaled per address before the first write so they can be restored.
 */
const val MAX_PATCH_BYTES = 64
const val MAX_HISTORY = 100

class MemoryEditor(
    private val backend: DebugBackend,
    private val policy: () -> EditPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _journal = MutableStateFlow<List<JournalEntry>>(emptyList())
    val journal: StateFlow<List<JournalEntry>> = _journal.asStateFlow()

    data class Snapshot(val bytes: ByteArray, val region: MemoryRegion?)

    suspend fun read(pid: Int, address: Long, type: ValueType): Snapshot {
        val regions = backend.getMemoryRegions(pid)
        val region = regions.regionFor(address, type.size)
        if (region == null || !region.isReadable) {
            throw EditDeniedException("${Address.format(address)} is not inside a readable memory region of pid $pid")
        }
        return Snapshot(backend.readMemory(pid, address, type.size), region)
    }

    /**
     * Validates and prepares a write. Throws [EditDeniedException] when policy forbids it.
     *
     * @param regions the process's memory map when the caller has just read it (one operation of several writes
     *   reads the map once instead of once per write — on a PS4 the map is the slowest request); null = read it now.
     */
    suspend fun plan(target: EditTarget, valueText: String, regions: List<MemoryRegion>? = null): WritePlan {
        checkPolicy(target)
        val replacement = ValueCodec.encode(ValueCodec.parse(target.type, valueText))
        return planChecked(target.copy(length = target.type.size), replacement, valueText.trim(), regions)
    }

    /**
     * Validates and prepares a raw byte patch (1..[MAX_PATCH_BYTES] bytes) at [address], e.g. from the memory
     * viewer. Same policy, region and confirmation rules as typed writes.
     */
    suspend fun planBytes(pid: Int, processName: String, address: Long, bytes: ByteArray, regions: List<MemoryRegion>? = null): WritePlan {
        if (bytes.isEmpty() || bytes.size > MAX_PATCH_BYTES) throw EditDeniedException("Patch 1..$MAX_PATCH_BYTES bytes at a time")
        val target = EditTarget(pid, processName, address, ValueType.INT8, bytes.size)
        checkPolicy(target)
        return planChecked(target, bytes, Hex.encode(bytes, " "), regions)
    }

    private fun checkPolicy(target: EditTarget) {
        val p = policy()
        if (!p.editingEnabled) throw EditDeniedException("Read-only mode: enable memory editing in Settings first.")
        if (ProcessSafety.isSystemProcess(target.processName) && !p.allowSystemProcesses) {
            throw EditDeniedException("\"${target.processName}\" looks like a system process. Writes to system processes are blocked.")
        }
    }

    private suspend fun planChecked(target: EditTarget, replacement: ByteArray, text: String, map: List<MemoryRegion>?): WritePlan {
        val p = policy()
        val regions = map ?: backend.getMemoryRegions(target.pid)
        val region = regions.regionFor(target.address, target.length)
            ?: throw EditDeniedException("${Address.format(target.address)} is not mapped in pid ${target.pid}.")
        val warnings = ArrayList<String>()
        if (!region.isWritable) {
            if (!p.allowNonWritableRegions) {
                throw EditDeniedException("Region ${region.name.ifBlank { "anon" }} [${region.protString()}] is not writable.")
            }
            warnings += "Target region is not writable (${region.protString()}); this patches read-only/code memory."
        }
        if (ProcessSafety.isSystemProcess(target.processName)) warnings += "Target is a system process."
        val original = backend.readMemory(target.pid, target.address, target.length)
        return WritePlan(target, region, original, replacement, text, warnings)
    }

    /** Performs a confirmed write and verifies it with a fresh read. */
    suspend fun execute(plan: WritePlan): WriteOutcome {
        val t = plan.target
        return try {
            // Journal the bytes present right before our first modification of this address.
            val before = backend.readMemory(t.pid, t.address, t.length)
            recordOriginal(t, before)
            when (val st = backend.writeMemory(t.pid, t.address, plan.replacement)) {
                is WriteStatus.Rejected -> WriteOutcome.Rejected(st.statusCode)
                WriteStatus.Accepted -> verify(t, plan.replacement).also {
                    updateLastWritten(t, plan.replacement)
                    if (it is WriteOutcome.Verified) pushHistory(HistoryItem(t, before, plan.replacement.copyOf(), plan.replacementText, clock()))
                }
            }
        } catch (e: BackendException) {
            WriteOutcome.Failed(e.message ?: "Write failed")
        }
    }

    /** Restores the journaled original bytes for an address, verifying with a fresh read. */
    suspend fun restore(pid: Int, address: Long): WriteOutcome {
        val entry = _journal.value.firstOrNull { it.pid == pid && it.address == address }
            ?: return WriteOutcome.Failed("No original value recorded for ${Address.format(address)}")
        if (!policy().editingEnabled) return WriteOutcome.Failed("Read-only mode: enable memory editing to restore values.")
        return try {
            val t = EditTarget(pid, entry.processName, address, entry.type, entry.original.size)
            val before = backend.readMemory(pid, address, entry.original.size)
            when (val st = backend.writeMemory(pid, address, entry.original)) {
                is WriteStatus.Rejected -> WriteOutcome.Rejected(st.statusCode)
                WriteStatus.Accepted -> {
                    val out = verify(t, entry.original)
                    if (out is WriteOutcome.Verified) {
                        _journal.update { list -> list.filterNot { it === entry } }
                        pushHistory(HistoryItem(t, before, entry.original.copyOf(), "restore original", clock()))
                    }
                    out
                }
            }
        } catch (e: BackendException) {
            WriteOutcome.Failed(e.message ?: "Restore failed")
        }
    }

    fun originalFor(pid: Int, address: Long): JournalEntry? =
        _journal.value.firstOrNull { it.pid == pid && it.address == address }

    /** Forget journal and history entries of a process (e.g. when it exits: its addresses are meaningless now). */
    fun forgetProcess(pid: Int) {
        _journal.update { list -> list.filterNot { it.pid == pid } }
        _undo.update { list -> list.filterNot { it.target.pid == pid } }
        _redo.update { list -> list.filterNot { it.target.pid == pid } }
    }

    // ------------------------------------------------------------------ undo / redo

    private val _undo = MutableStateFlow<List<HistoryItem>>(emptyList())
    /** Verified writes, oldest first; the last one is undone first. */
    val undoStack: StateFlow<List<HistoryItem>> = _undo.asStateFlow()
    private val _redo = MutableStateFlow<List<HistoryItem>>(emptyList())
    val redoStack: StateFlow<List<HistoryItem>> = _redo.asStateFlow()

    private fun pushHistory(item: HistoryItem) {
        _undo.update { (it + item).takeLast(MAX_HISTORY) }
        _redo.value = emptyList()
    }

    /** What undo would do: the item, and the bytes in memory now (differ from item.after if the game changed them). */
    suspend fun peekUndo(): Pair<HistoryItem, ByteArray>? = _undo.value.lastOrNull()?.let { it to current(it) }
    suspend fun peekRedo(): Pair<HistoryItem, ByteArray>? = _redo.value.lastOrNull()?.let { it to current(it) }

    private suspend fun current(i: HistoryItem) = backend.readMemory(i.target.pid, i.target.address, i.target.length)

    /** Writes back the bytes from before the last write (user-confirmed), verified by a fresh read. */
    suspend fun undo(): WriteOutcome = step(_undo, _redo) { it.before }

    /** Re-applies the last undone write (user-confirmed), verified by a fresh read. */
    suspend fun redo(): WriteOutcome = step(_redo, _undo) { it.after }

    private suspend fun step(
        from: MutableStateFlow<List<HistoryItem>>, to: MutableStateFlow<List<HistoryItem>>, bytes: (HistoryItem) -> ByteArray,
    ): WriteOutcome {
        val item = from.value.lastOrNull() ?: return WriteOutcome.Failed("Nothing to do")
        if (!policy().editingEnabled) return WriteOutcome.Failed("Read-only mode: enable memory editing first.")
        return try {
            val t = item.target
            when (val st = backend.writeMemory(t.pid, t.address, bytes(item))) {
                is WriteStatus.Rejected -> WriteOutcome.Rejected(st.statusCode)
                WriteStatus.Accepted -> verify(t, bytes(item)).also {
                    if (it is WriteOutcome.Verified) {
                        from.update { l -> l.dropLast(1) }
                        to.update { l -> (l + item).takeLast(MAX_HISTORY) }
                    }
                }
            }
        } catch (e: BackendException) {
            WriteOutcome.Failed(e.message ?: "Write failed")
        }
    }

    fun clearJournal() { _journal.value = emptyList() }

    private suspend fun verify(t: EditTarget, expected: ByteArray): WriteOutcome {
        val back = backend.readMemory(t.pid, t.address, expected.size)
        return if (back.contentEquals(expected)) WriteOutcome.Verified(back) else WriteOutcome.Mismatch(expected, back)
    }

    private fun recordOriginal(t: EditTarget, before: ByteArray) = _journal.update { list ->
        if (list.any { it.pid == t.pid && it.address == t.address && it.original.size == before.size }) list
        else list + JournalEntry(t.pid, t.processName, t.address, t.type, before, before, clock())
    }

    private fun updateLastWritten(t: EditTarget, bytes: ByteArray) = _journal.update { list ->
        list.map { if (it.pid == t.pid && it.address == t.address) it.copy(lastWritten = bytes) else it }
    }
}

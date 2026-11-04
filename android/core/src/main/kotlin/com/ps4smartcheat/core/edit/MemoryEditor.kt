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

data class EditTarget(val pid: Int, val processName: String, val address: Long, val type: ValueType)

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

    /** Validates and prepares a write. Throws [EditDeniedException] when policy forbids it. */
    suspend fun plan(target: EditTarget, valueText: String): WritePlan {
        val p = policy()
        if (!p.editingEnabled) throw EditDeniedException("Read-only mode: enable memory editing in Settings first.")
        if (ProcessSafety.isSystemProcess(target.processName) && !p.allowSystemProcesses) {
            throw EditDeniedException("\"${target.processName}\" looks like a system process. Writes to system processes are blocked.")
        }
        val replacement = ValueCodec.encode(ValueCodec.parse(target.type, valueText))
        val regions = backend.getMemoryRegions(target.pid)
        val region = regions.regionFor(target.address, target.type.size)
            ?: throw EditDeniedException("${Address.format(target.address)} is not mapped in pid ${target.pid}.")
        val warnings = ArrayList<String>()
        if (!region.isWritable) {
            if (!p.allowNonWritableRegions) {
                throw EditDeniedException("Region ${region.name.ifBlank { "anon" }} [${region.protString()}] is not writable.")
            }
            warnings += "Target region is not writable (${region.protString()}); this patches read-only/code memory."
        }
        if (ProcessSafety.isSystemProcess(target.processName)) warnings += "Target is a system process."
        val original = backend.readMemory(target.pid, target.address, target.type.size)
        return WritePlan(target, region, original, replacement, valueText.trim(), warnings)
    }

    /** Performs a confirmed write and verifies it with a fresh read. */
    suspend fun execute(plan: WritePlan): WriteOutcome {
        val t = plan.target
        return try {
            // Journal the bytes present right before our first modification of this address.
            val before = backend.readMemory(t.pid, t.address, t.type.size)
            recordOriginal(t, before)
            when (val st = backend.writeMemory(t.pid, t.address, plan.replacement)) {
                is WriteStatus.Rejected -> WriteOutcome.Rejected(st.statusCode)
                WriteStatus.Accepted -> verify(t, plan.replacement).also { updateLastWritten(t, plan.replacement) }
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
            when (val st = backend.writeMemory(pid, address, entry.original)) {
                is WriteStatus.Rejected -> WriteOutcome.Rejected(st.statusCode)
                WriteStatus.Accepted -> {
                    val out = verify(EditTarget(pid, entry.processName, address, entry.type), entry.original)
                    if (out is WriteOutcome.Verified) _journal.update { list -> list.filterNot { it === entry } }
                    out
                }
            }
        } catch (e: BackendException) {
            WriteOutcome.Failed(e.message ?: "Restore failed")
        }
    }

    fun originalFor(pid: Int, address: Long): JournalEntry? =
        _journal.value.firstOrNull { it.pid == pid && it.address == address }

    /** Forget journal entries of a process (e.g. when it exits: its addresses are meaningless now). */
    fun forgetProcess(pid: Int) = _journal.update { list -> list.filterNot { it.pid == pid } }

    fun clearJournal() { _journal.value = emptyList() }

    private suspend fun verify(t: EditTarget, expected: ByteArray): WriteOutcome {
        val back = backend.readMemory(t.pid, t.address, expected.size)
        return if (back.contentEquals(expected)) WriteOutcome.Verified(back) else WriteOutcome.Mismatch(expected, back)
    }

    private fun recordOriginal(t: EditTarget, before: ByteArray) = _journal.update { list ->
        if (list.any { it.pid == t.pid && it.address == t.address }) list
        else list + JournalEntry(t.pid, t.processName, t.address, t.type, before, before, clock())
    }

    private fun updateLastWritten(t: EditTarget, bytes: ByteArray) = _journal.update { list ->
        list.map { if (it.pid == t.pid && it.address == t.address) it.copy(lastWritten = bytes) else it }
    }
}

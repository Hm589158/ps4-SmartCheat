package com.ps4smartcheat.core.model

import com.ps4smartcheat.core.util.Address

/** One entry of CMD_PROC_LIST (`struct proc_list_entry { char p_comm[32]; int pid; }`). */
data class ProcessEntry(val pid: Int, val name: String)

/** CMD_PROC_INFO response (188 bytes). */
data class ProcessInfo(
    val pid: Int,
    val name: String,
    val path: String,
    val titleId: String,
    val contentId: String,
)

/**
 * One entry of CMD_PROC_MAPS
 * (`struct proc_vm_map_entry { char name[32]; u64 start; u64 end; u64 offset; u16 prot; }`, 58 bytes packed).
 * [end] is exclusive.
 */
data class MemoryRegion(
    val name: String,
    val start: Long,
    val end: Long,
    val offset: Long,
    val prot: Int,
) {
    val size: Long get() = if (Address.lt(start, end)) end - start else 0L
    val isReadable: Boolean get() = prot and PROT_READ != 0
    val isWritable: Boolean get() = prot and PROT_WRITE != 0
    val isExecutable: Boolean get() = prot and PROT_EXEC != 0
    val isValid: Boolean get() = Address.lt(start, end)

    /** True if `[address, address+length)` lies completely inside this region. */
    fun contains(address: Long, length: Int = 1): Boolean {
        if (length <= 0) return false
        val last = address + length.toLong()
        if (Address.lt(last, address)) return false // overflow
        return Address.le(start, address) && Address.le(last, end)
    }

    fun protString(): String = buildString {
        append(if (isReadable) 'r' else '-')
        append(if (isWritable) 'w' else '-')
        append(if (isExecutable) 'x' else '-')
    }

    companion object {
        // FreeBSD VM_PROT_* flags.
        const val PROT_READ = 0x1
        const val PROT_WRITE = 0x2
        const val PROT_EXEC = 0x4
    }
}

/** Extensions detected on the connected debugger. All fields are probed, never assumed. */
data class ServerCapabilities(
    /** CMD_BRANDING text up to the first NUL (e.g. "ps4debug-NG by OSR v1.3.3"); null if unsupported. */
    val brand: String? = null,
    /** Text after the NUL in the NG branding reply (e.g. "1.1"). */
    val capabilityLevel: String? = null,
    /** CMD_PROC_AUTH handshake succeeded (unlocks ps4debug-NG scan commands). */
    val scanAuthorized: Boolean = false,
    /** TSE_* engine bitmask from Turbo Scan CAPS; null when CAPS is unavailable. */
    val turboEngines: Int? = null,
) {
    val isNg: Boolean get() = brand?.startsWith("ps4debug-NG") == true
    val serverScan: Boolean get() = isNg && scanAuthorized
    val turboResident: Boolean get() = serverScan && turboEngines != null && (turboEngines and 0x4) != 0
    val turboRescanAliasing: Boolean get() = turboResident && (turboEngines!! and 0x200) != 0
    val turboAliasing: Boolean get() = turboResident && (turboEngines!! and 0x2) != 0

    companion object {
        val NONE = ServerCapabilities()
    }
}

data class ConsoleInfo(
    val host: String,
    val port: Int,
    /** String returned by CMD_VERSION (e.g. "1.3" for ps4debug-NG). */
    val debuggerVersion: String,
    /** Firmware as major*100+minor (e.g. 1100) from the CMD_FW_VERSION extension; null when unknown. */
    val firmware: Int?,
    val capabilities: ServerCapabilities = ServerCapabilities.NONE,
) {
    val firmwareText: String?
        get() = firmware?.let { "%d.%02d".format(it / 100, it % 100) }
}

/** Finds the region containing [address] (for [length] bytes), if any. */
fun List<MemoryRegion>.regionFor(address: Long, length: Int = 1): MemoryRegion? =
    firstOrNull { it.contains(address, length) }

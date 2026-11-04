package com.ps4smartcheat.core.protocol

/**
 * Wire constants of the ps4debug protocol.
 *
 * Every value here was verified against the upstream sources (see docs/PROTOCOL.md):
 *  - ps4debug-NG `debugger/include/protocol.h`, `debugger/include/proc.h`, `debugger/source/server.c`, `proc.c`
 *  - jogolden/GoldHEN-compatible client library `libdebug` as shipped in PS4CheaterNeo (`PS4DBG*.cs`)
 *
 * Only the commands this application needs are listed. Kernel read/write commands are
 * intentionally not implemented.
 */
object Ps4DebugProtocol {
    const val PACKET_MAGIC: Int = 0xFFAABBCC.toInt()
    const val CMD_PACKET_SIZE = 12

    const val CMD_VERSION: Int = 0xBD000001.toInt()
    /** Extension present in GoldHEN ps4debug (ctn) and ps4debug-NG; returns a raw u16 with no status word. */
    const val CMD_FW_VERSION: Int = 0xBD000500.toInt()

    const val CMD_PROC_LIST: Int = 0xBDAA0001.toInt()
    const val CMD_PROC_READ: Int = 0xBDAA0002.toInt()
    const val CMD_PROC_WRITE: Int = 0xBDAA0003.toInt()
    const val CMD_PROC_MAPS: Int = 0xBDAA0004.toInt()
    const val CMD_PROC_INFO: Int = 0xBDAA000A.toInt()

    const val CMD_CONSOLE_END: Int = 0xBDDD0002.toInt()

    const val CMD_SUCCESS: Long = 0x80000000L
    const val CMD_ERROR: Long = 0xF0000001L
    const val CMD_TOO_MUCH_DATA: Long = 0xF0000002L
    const val CMD_DATA_NULL: Long = 0xF0000003L
    const val CMD_ALREADY_DEBUG: Long = 0xF0000004L
    const val CMD_INVALID_INDEX: Long = 0xF0000005L

    const val CMD_PROC_READ_PACKET_SIZE = 16   // u32 pid, u64 address, u32 length
    const val CMD_PROC_WRITE_PACKET_SIZE = 16  // u32 pid, u64 address, u32 length
    const val CMD_PROC_MAPS_PACKET_SIZE = 4    // u32 pid
    const val CMD_PROC_INFO_PACKET_SIZE = 4    // u32 pid

    const val PROC_LIST_ENTRY_SIZE = 36        // char p_comm[32]; int pid
    const val PROC_MAP_ENTRY_SIZE = 58         // char name[32]; u64 start,end,offset; u16 prot
    const val PROC_INFO_RESPONSE_SIZE = 188    // u32 pid; char name[40], path[64], titleid[16], contentid[64]

    /** Firmware value the server sends when sceKernelGetSystemSwVersion fails. */
    const val FW_UNKNOWN = 0xFFFF

    fun statusName(status: Long): String = when (status) {
        CMD_SUCCESS -> "CMD_SUCCESS"
        CMD_ERROR -> "CMD_ERROR"
        CMD_TOO_MUCH_DATA -> "CMD_TOO_MUCH_DATA"
        CMD_DATA_NULL -> "CMD_DATA_NULL"
        CMD_ALREADY_DEBUG -> "CMD_ALREADY_DEBUG"
        CMD_INVALID_INDEX -> "CMD_INVALID_INDEX"
        else -> "0x" + status.toString(16).uppercase()
    }
}

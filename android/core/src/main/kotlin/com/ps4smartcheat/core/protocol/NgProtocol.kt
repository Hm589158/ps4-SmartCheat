package com.ps4smartcheat.core.protocol

/**
 * ps4debug-NG extensions used for server-side scanning. Verified against
 * Pharaoh2k/ps4debug-NG `debugger/include/protocol.h`, `debugger/source/proc.c`, `debugger/source/scan_turbo.c`
 * (see docs/PROTOCOL.md for the wire sequences).
 */
object NgProtocol {
    /** Returns `u32 len` + branding text, a NUL, then a capability level (NG). No status word. */
    const val CMD_BRANDING: Int = 0xBD000501.toInt()

    const val CMD_PROC_AUTH: Int = 0xBDAACCFF.toInt()
    const val AUTH_MAGIC: Int = 0xBB40E64D.toInt()
    /** Auth flag bit that unlocks the scan family. */
    const val AUTH_FLAG_SCAN = 0x2

    // Iterative ("standard") server-side scan.
    const val CMD_PROC_SCAN_START: Int = 0xBDAACC01.toInt()
    const val CMD_PROC_SCAN_COUNT: Int = 0xBDAACC02.toInt()
    const val SCAN_START_PACKET_SIZE = 23 // u32 pid, u64 addr, u32 len, u8 vt, u8 ct, u8 align, u32 lenData
    const val SCAN_COUNT_PACKET_SIZE = 18 // u32 pid, u64 base, u8 vt, u8 ct, u32 lenData
    /** Max bytes per client chunk accepted by the iterative COUNT stream. */
    const val SCAN_COUNT_MAX_CHUNK = 0x20000
    /** Max result batch payload the server emits (flush threshold < 0x10000). */
    const val SCAN_MAX_BATCH = 0x10000

    // Turbo Scan (ps4debug-NG >= 1.3.0); raw opcodes without CMD_* macros upstream.
    const val TURBO_CAPS: Int = 0xBDAACC10.toInt()
    const val TURBO_START: Int = 0xBDAACC11.toInt()
    const val TURBO_COUNT: Int = 0xBDAACC12.toInt()
    const val TURBO_GET: Int = 0xBDAACC13.toInt()
    const val TURBO_END: Int = 0xBDAACC14.toInt()
    const val TURBO_CANCEL: Int = 0xBDAACC17.toInt()
    const val TURBO_START_PACKET_SIZE = 27 // scan_start packet + u32 flags
    const val TURBO_COUNT_PACKET_SIZE = 22 // scan_count packet + u32 flags
    const val TURBO_GET_PACKET_SIZE = 12   // u32 start, u32 count, u32 flags

    // TSE_* engine bits from CAPS.
    const val TSE_SIMD_COMPARE = 0x1
    const val TSE_ALIASING = 0x2
    const val TSE_SERVER_RESIDENT = 0x4
    const val TSE_RESCAN_ALIASING = 0x200

    // TS_* request flags.
    const val TS_USE_ALIASING = 0x1
    const val TS_SERVER_RESIDENT = 0x2
    const val TS_SNAPSHOT_SEGMENTS = 0x10
    const val TS_RESCAN_ALIASING = 0x100

    /** Server-side cap of the resident survivor buffer (scan_turbo.c TS_RESIDENT_CAP). */
    const val TURBO_RESIDENT_CAP_BYTES = 256L shl 20
    const val TURBO_MAX_SEGMENTS = 1 shl 20

    const val SENTINEL: Long = -1L // 0xFFFFFFFFFFFFFFFF

    // cmd_proc_scan_valuetype (signed variants are used by this app).
    const val VT_INT8 = 1
    const val VT_INT16 = 3
    const val VT_INT32 = 5
    const val VT_INT64 = 7

    // cmd_proc_scan_comparetype
    const val CT_EXACT = 0
    const val CT_INCREASED = 5
    const val CT_DECREASED = 7
    const val CT_CHANGED = 9
    const val CT_UNCHANGED = 10

    /**
     * Client half of CMD_PROC_AUTH: the server expects `challenge XOR keystream`, where the keystream is
     * L'Ecuyer's combined Tausworthe generator (lfsr113) seeded with 200/300/400/500, low byte per step
     * (proc.c `auth_lfsr_next` / `proc_auth_xor_keystream`). Re-seeded for every handshake.
     */
    fun authResponse(challenge: ByteArray): ByteArray {
        var s1 = 200; var s2 = 300; var s3 = 400; var s4 = 500
        return ByteArray(challenge.size) { i ->
            s1 = ((s1 shl 18) and 0xFFF80000.toInt()) xor ((s1 xor (s1 shl 6)) ushr 13)
            s2 = ((s2 shl 2) and 0xFFFFFFE0.toInt()) xor ((s2 xor (s2 shl 2)) ushr 27)
            s3 = ((s3 shl 7) and 0xFFFFF800.toInt()) xor ((s3 xor (s3 shl 13)) ushr 21)
            s4 = ((s4 shl 13) and 0xFFF00000.toInt()) xor ((s4 xor (s4 shl 3)) ushr 12)
            (challenge[i].toInt() xor (s1 xor s2 xor s3 xor s4)).toByte()
        }
    }
}

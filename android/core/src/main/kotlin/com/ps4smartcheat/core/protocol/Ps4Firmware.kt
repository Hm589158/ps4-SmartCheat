package com.ps4smartcheat.core.protocol

/**
 * PS4 firmware versions as `major * 100 + minor` (the value of CMD_FW_VERSION, e.g. 1100 = 11.00).
 *
 * The app has no firmware-specific code: it uses only ps4debug-NG's commands, so it supports exactly the firmware
 * versions ps4debug-NG supports. That list is taken from the ps4debug-NG README, "Supported firmwares" (commit 8be828f,
 * 39 versions, 5.05–14.02); ps4debug-NG rejects other firmware before it installs (docs/PROTOCOL.md).
 */
object Ps4Firmware {
    val SUPPORTED_BY_PS4DEBUG_NG: List<Int> = listOf(
        505, 507,
        671, 672,
        700, 702, 750, 751, 755,
        800, 803, 850, 852,
        900, 903, 904, 950, 951, 960,
        1000, 1001, 1050, 1070, 1071,
        1100, 1102, 1150, 1152,
        1200, 1202, 1250, 1252,
        1300, 1302, 1304, 1350, 1352,
        1400, 1402,
    )

    /** "11.00". */
    fun format(fw: Int): String = "${fw / 100}.${(fw % 100).toString().padStart(2, '0')}"

    /** "5.05–14.02". */
    val range: String get() = format(SUPPORTED_BY_PS4DEBUG_NG.first()) + "–" + format(SUPPORTED_BY_PS4DEBUG_NG.last())

    fun isSupported(fw: Int): Boolean = fw in SUPPORTED_BY_PS4DEBUG_NG

    /** Short note for the connection screen, or null when the payload did not report the firmware. */
    fun note(fw: Int?): String? = when {
        fw == null -> null
        isSupported(fw) -> "supported by ps4debug-NG"
        else -> "not in ps4debug-NG's list (${SUPPORTED_BY_PS4DEBUG_NG.size} versions, $range)"
    }
}

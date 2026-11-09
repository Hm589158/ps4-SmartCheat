package com.ps4smartcheat.core.asm

import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex

/** Plain-text forms of a disassembly, for copying to the clipboard. */
object Listing {
    /** What to copy. */
    enum class Style(val label: String) {
        /** Address, module offset, bytes and instruction: the listing as shown. */
        FULL("listing"),
        /** Instructions only, one per line: can be pasted into the assembler fields (and other tools). */
        CODE("code only"),
    }

    /**
     * Formats [rows]. [relative] gives the module-relative form of an address (e.g. `eboot.bin+0x2000`, or "").
     * Undecodable bytes are kept as `db 0x..` so nothing is silently dropped.
     */
    fun format(rows: List<AsmInstruction>, style: Style, relative: (Long) -> String = { "" }): String {
        if (rows.isEmpty()) return ""
        return when (style) {
            Style.CODE -> rows.joinToString("\n") { it.text }
            Style.FULL -> {
                val rel = rows.map { relative(it.address) }
                val relWidth = rel.maxOf { it.length }
                val bytes = rows.map { Hex.encode(it.bytes, " ") }
                val byteWidth = bytes.maxOf { it.length }
                rows.indices.joinToString("\n") { i ->
                    buildString {
                        append(Address.format(rows[i].address)).append("  ")
                        if (relWidth > 0) append(rel[i].padEnd(relWidth)).append("  ")
                        append(bytes[i].padEnd(byteWidth)).append("  ")
                        append(rows[i].text)
                    }.trimEnd()
                }
            }
        }
    }
}

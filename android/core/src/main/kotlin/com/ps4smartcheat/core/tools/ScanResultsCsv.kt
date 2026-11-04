package com.ps4smartcheat.core.tools

import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.scan.ScanResults
import com.ps4smartcheat.core.scan.ValueCodec
import com.ps4smartcheat.core.scan.describe
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex

/** Streams scan results as CSV (values are those stored at the last scan step, not fresh reads). */
object ScanResultsCsv {
    const val HEADER = "index,address,type,value,bytes_hex,region"

    fun write(out: Appendable, results: ScanResults, regions: List<MemoryRegion>, processName: String? = null): Int {
        if (processName != null) out.append("# process=").append(escape(processName)).append('\n')
        if (results.serverTotal != null && results.serverTotal > results.count) {
            out.append("# note=").append("${results.count} of ${results.serverTotal} results exported (rest kept on PS4)").append('\n')
        } else if (results.truncated) {
            out.append("# note=result list was capped; export is incomplete\n")
        }
        out.append(HEADER).append('\n')
        val sorted = regions.filter { it.isValid }.sortedBy { it.start }
        for (i in 0 until results.count) {
            val b = results.valueBytes(i)
            val a = results.address(i)
            out.append(i.toString()).append(',')
                .append(Address.format(a)).append(',')
                .append(results.type.label).append(',')
                .append(escape(ValueCodec.format(results.type, b))).append(',')
                .append(Hex.encode(b)).append(',')
                .append(escape(sorted.describe(a))).append('\n')
        }
        return results.count
    }

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

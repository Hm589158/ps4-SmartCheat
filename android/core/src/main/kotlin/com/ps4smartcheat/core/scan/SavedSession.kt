package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.process.ProcessIdentity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A scan session written to disk so it can be resumed after the app restarts. Only valid for the exact same
 * process instance ([identity] including the pid): addresses of another instance are meaningless.
 * Unknown-initial-value snapshots are not saved (they can be hundreds of MiB).
 */
class SavedScanSession(
    val identity: ProcessIdentity,
    val type: ValueType,
    val step: Int,
    val lastMode: ScanMode?,
    val results: ScanResults,
    val savedAtMillis: Long,
) {
    companion object {
        private const val MAGIC = 0x53435353 // "SCSS"
        private const val VERSION = 1
        /** Sessions larger than this are not saved (12 MiB for Int32). */
        const val MAX_RESULTS = 1_000_000

        fun write(out: OutputStream, s: SavedScanSession) {
            val d = DataOutputStream(out.buffered())
            d.writeInt(MAGIC); d.writeInt(VERSION)
            d.writeInt(s.identity.pid); d.writeUTF(s.identity.name)
            d.writeUTF(s.identity.titleId ?: ""); d.writeUTF(s.identity.path ?: "")
            d.writeUTF(s.type.name); d.writeInt(s.step); d.writeUTF(s.lastMode?.name ?: "")
            d.writeLong(s.savedAtMillis)
            val r = s.results
            d.writeBoolean(r.truncated); d.writeInt(r.count)
            val a = r.addressArray()
            for (i in 0 until r.count) d.writeLong(a[i])
            d.write(r.valuesArray(), 0, r.count * s.type.size)
            d.flush()
        }

        /** Throws [IOException] for anything that is not a complete, valid session file. */
        fun read(input: InputStream): SavedScanSession {
            val d = DataInputStream(input.buffered())
            if (d.readInt() != MAGIC) throw IOException("not a saved scan session")
            val v = d.readInt()
            if (v != VERSION) throw IOException("unsupported session version $v")
            val pid = d.readInt()
            val name = d.readUTF()
            val title = d.readUTF().ifEmpty { null }
            val path = d.readUTF().ifEmpty { null }
            val typeName = d.readUTF()
            val type = ValueType.entries.firstOrNull { it.name == typeName } ?: throw IOException("unknown value type")
            val step = d.readInt()
            val modeName = d.readUTF()
            val mode = if (modeName.isEmpty()) null else ScanMode.entries.firstOrNull { it.name == modeName } ?: throw IOException("unknown mode")
            val saved = d.readLong()
            val truncated = d.readBoolean()
            val count = d.readInt()
            if (count < 0 || count > MAX_RESULTS) throw IOException("bad result count $count")
            val addresses = LongArray(count) { d.readLong() }
            for (i in 1 until count) if (java.lang.Long.compareUnsigned(addresses[i - 1], addresses[i]) >= 0) throw IOException("addresses not ascending")
            val values = ByteArray(count * type.size)
            d.readFully(values)
            return SavedScanSession(ProcessIdentity(pid, name, title, path), type, step, mode, ScanResults(type, addresses, values, count, truncated), saved)
        }
    }
}

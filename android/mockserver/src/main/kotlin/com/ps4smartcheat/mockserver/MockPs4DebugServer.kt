package com.ps4smartcheat.mockserver

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Deterministic, in-process simulation of a ps4debug server for automated tests and manual
 * app testing without a console.
 *
 * Written independently of the client code in :core so that client tests exercise the real wire
 * format. Behaviour follows ps4debug-NG `server.c` / `proc.c` for the supported commands:
 * CMD_VERSION, CMD_FW_VERSION, CMD_PROC_LIST, CMD_PROC_READ, CMD_PROC_WRITE, CMD_PROC_MAPS,
 * CMD_PROC_INFO, CMD_CONSOLE_END. Handler failures close the connection, like the real server.
 *
 * THIS IS A TEST BACKEND. It does not talk to a PlayStation 4.
 */
class MockPs4DebugServer(
    port: Int = 0,
    bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
) : AutoCloseable {

    class Region(
        val name: String,
        val start: Long,
        size: Int,
        val prot: Int,
        /** Value written to the `offset` field of the map entry. */
        val offset: Long = 0,
    ) {
        val data = ByteArray(size)
        val end: Long get() = start + data.size
        fun contains(addr: Long, len: Int) = addr >= start && addr + len <= end
    }

    class Process(
        val pid: Int,
        val name: String,
        val titleId: String = "",
        val contentId: String = "",
        val path: String = "",
    ) {
        val regions = CopyOnWriteArrayList<Region>()
        fun region(name: String, start: Long, size: Int, prot: Int, offset: Long = 0): Region =
            Region(name, start, size, prot, offset).also { regions += it }
    }

    /** Fault injection switches. All default to normal behaviour. */
    class Faults {
        @Volatile var supportFirmwareCommand = true
        @Volatile var firmware = 1100
        @Volatile var version = "1.3"
        /** Reply CMD_SUCCESS to writes without changing memory (simulates a write that did not stick). */
        @Volatile var ignoreWrites = false
        /** Classic ps4debug: never verify, always CMD_SUCCESS, even for unmapped addresses. */
        @Volatile var classicWriteStatus = false
        /** Close the connection when this command arrives. */
        @Volatile var dropOnCommand: Int? = null
        /** Never answer this command (client must time out). */
        @Volatile var stallOnCommand: Int? = null
        /** For CMD_PROC_MAPS: announce more entries than are sent, then close. */
        @Volatile var truncatedMaps = false
        /** For CMD_PROC_LIST: send a garbage status word. */
        @Volatile var garbageStatusForList = false
        /** For CMD_PROC_READ: close after sending this many payload bytes (null = disabled). */
        @Volatile var dropReadAfterBytes: Int? = null
        /** Artificial delay per CMD_PROC_READ in ms. */
        @Volatile var readDelayMs = 0L

        // ---- ps4debug-NG emulation (off by default: the mock then behaves like a classic server) ----
        /** CMD_BRANDING reply (text before the NUL); null = command unsupported (no reply). */
        @Volatile var brand: String? = null
        @Volatile var capabilityLevel: String = "1.1"
        /** Enables CMD_PROC_AUTH and the iterative scan commands (0xBDAACC01/02). */
        @Volatile var ngScan = false
        /** Enables Turbo Scan CAPS/START/COUNT/GET/END/CANCEL. */
        @Volatile var turbo = false
        @Volatile var turboEngines = 0x7FF
        /** Resident survivor buffer cap (bytes); exceeding it makes START report resident_stored = 0. */
        @Volatile var turboResidentCapBytes = 256L shl 20
        /** Artificial server-side scan cost in ms per MiB scanned (for cancellation tests). */
        @Volatile var scanDelayMsPerMiB = 0L
        /** Classic servers close the connection on unknown process commands. */
        @Volatile var closeOnUnknownCommand = false
        /** Make the CMD_PROC_AUTH handshake fail. */
        @Volatile var rejectAuth = false
        /** Simulated link speed for server→client data in bytes/s (0 = unlimited loopback). */
        @Volatile var linkBytesPerSecond = 0L
        /** Simulated request round-trip latency in ms, applied once per received command. */
        @Volatile var linkLatencyMs = 0L

        fun enableNg(turbo: Boolean = true) {
            brand = "ps4debug-NG by OSR v1.3.3 (MOCK)"
            ngScan = true
            this.turbo = turbo
        }
    }

    /** Per-connection state (ps4debug-NG keeps the Turbo session per client slot). */
    private class Conn {
        var turboType = 0
        var turboLen = 0
        var turboPid = 0
        var turboAddrs: LongArray? = null
        var turboVals: ByteArray? = null
        var turboCount = 0
    }

    @Volatile private var authBits = 0
    /** Flags word of the last CMD_PROC_AUTH request (for tests). */
    @Volatile var lastAuthFlags = -1
        private set
    val authAttempts = AtomicInteger()

    /** Simulates a payload restart: the server-global scan authorisation is cleared. */
    fun revokeAuth() { authBits = 0 }
    @Volatile private var turboCancelPid = 0
    val serverScanCommands = AtomicInteger()
    /** Bytes of process memory the mock "scanned" on the server side. */
    val serverScannedBytes = java.util.concurrent.atomic.AtomicLong()

    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(bindAddress, port))
    }
    val port: Int get() = server.localPort
    val faults = Faults()
    val processes = CopyOnWriteArrayList<Process>()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val lock = Any()
    @Volatile private var running = true

    val readRequests = AtomicInteger()
    val writeRequests = AtomicInteger()
    val connections = AtomicInteger()

    private val acceptThread = thread(name = "mock-ps4debug-accept", isDaemon = true) {
        while (running) {
            val s = try { server.accept() } catch (e: IOException) { break }
            connections.incrementAndGet()
            clients += s
            thread(name = "mock-ps4debug-client", isDaemon = true) { serve(s) }
        }
    }

    fun addProcess(p: Process): Process = p.also { processes += it }

    fun process(pid: Int): Process? = processes.firstOrNull { it.pid == pid }

    /** Simulates the game exiting. */
    fun killProcess(pid: Int) {
        processes.removeIf { it.pid == pid }
    }

    // ---- direct memory helpers for tests (simulate the game changing values) ----

    fun poke(pid: Int, address: Long, bytes: ByteArray) = synchronized(lock) {
        val r = requireNotNull(process(pid)).regions.first { it.contains(address, bytes.size) }
        System.arraycopy(bytes, 0, r.data, (address - r.start).toInt(), bytes.size)
    }

    fun peek(pid: Int, address: Long, len: Int): ByteArray = synchronized(lock) {
        val r = requireNotNull(process(pid)).regions.first { it.contains(address, len) }
        r.data.copyOfRange((address - r.start).toInt(), (address - r.start).toInt() + len)
    }

    fun pokeInt32(pid: Int, address: Long, v: Int) = poke(pid, address, le(4).putInt(v).array())
    fun peekInt32(pid: Int, address: Long): Int = ByteBuffer.wrap(peek(pid, address, 4)).order(ByteOrder.LITTLE_ENDIAN).int

    /** Disconnects every client (simulates Wi-Fi loss / payload crash). */
    fun dropAllClients() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        dropAllClients()
        acceptThread.join(2000)
    }

    // ---------------------------------------------------------------------------------------------

    private fun serve(s: Socket) {
        try {
            s.tcpNoDelay = true
            val inp = DataInputStream(s.getInputStream().buffered())
            val out = ThrottledOutput(s.getOutputStream()).buffered(64 * 1024)
            val conn = Conn()
            while (running && !s.isClosed) {
                val hdr = ByteArray(12)
                try { inp.readFully(hdr) } catch (e: EOFException) { break }
                val h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val magic = h.int
                val cmd = h.int
                val len = h.int
                if (magic != MAGIC) continue // real server silently ignores bad magic
                if (len < 0 || len > 0x100000) break
                val body = ByteArray(len).also { inp.readFully(it) }
                if (faults.linkLatencyMs > 0) Thread.sleep(faults.linkLatencyMs)
                if (faults.dropOnCommand == cmd) break
                if (faults.stallOnCommand == cmd) {
                    // Keep the socket open but never answer.
                    while (running && !s.isClosed) Thread.sleep(20)
                    break
                }
                val keepOpen = handle(cmd, ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN), inp, out, conn)
                out.flush()
                if (!keepOpen) break
            }
        } catch (e: SocketException) {
            // client went away
        } catch (e: IOException) {
            // ignore
        } catch (e: InterruptedException) {
            // shutting down
        } finally {
            runCatching { s.close() }
            clients -= s
        }
    }

    /** @return false when the server would close the connection (handler returned 1). */
    private fun handle(cmd: Int, body: ByteBuffer, inp: DataInputStream, out: OutputStream, conn: Conn): Boolean {
        when (cmd) {
            CMD_VERSION -> {
                val v = faults.version.toByteArray(Charsets.US_ASCII)
                out.write(le(4).putInt(v.size).array()); out.write(v)
                return true
            }
            CMD_FW_VERSION -> {
                if (faults.supportFirmwareCommand) out.write(le(2).putShort(faults.firmware.toShort()).array())
                return true // unsupported: classic server sends nothing
            }
            CMD_PROC_LIST -> {
                if (faults.garbageStatusForList) { out.write(le(4).putInt(0x12345678).array()); return true }
                val list = processes.toList()
                if (list.isEmpty()) { status(out, CMD_DATA_NULL); return false }
                status(out, CMD_SUCCESS)
                out.write(le(4).putInt(list.size).array())
                for (p in list) {
                    out.write(fixed(p.name, 32))
                    out.write(le(4).putInt(p.pid).array())
                }
                return true
            }
            CMD_PROC_INFO -> {
                if (body.capacity() < 4) { status(out, CMD_DATA_NULL); return true }
                val pid = body.int
                val p = process(pid)
                status(out, CMD_SUCCESS)
                // Real server answers even for unknown pids (with zeroed fields).
                out.write(le(4).putInt(p?.pid ?: 0).array())
                out.write(fixed(p?.name ?: "", 40))
                out.write(fixed(p?.path ?: "", 64))
                out.write(fixed(p?.titleId ?: "", 16))
                out.write(fixed(p?.contentId ?: "", 64))
                return true
            }
            CMD_PROC_MAPS -> {
                if (body.capacity() < 4) { status(out, CMD_ERROR); return false }
                val p = process(body.int) ?: run { status(out, CMD_ERROR); return false }
                val regions = p.regions.toList()
                status(out, CMD_SUCCESS)
                val announced = if (faults.truncatedMaps) regions.size + 5 else regions.size
                out.write(le(4).putInt(announced).array())
                for (r in regions) {
                    out.write(fixed(r.name, 32))
                    out.write(le(26).putLong(r.start).putLong(r.end).putLong(r.offset).putShort(r.prot.toShort()).array())
                }
                return !faults.truncatedMaps
            }
            CMD_PROC_READ -> {
                readRequests.incrementAndGet()
                if (body.capacity() < 16) { status(out, CMD_DATA_NULL); return false }
                val pid = body.int; val addr = body.long; val length = body.int.toLong() and 0xFFFFFFFFL
                if (faults.readDelayMs > 0) Thread.sleep(faults.readDelayMs)
                status(out, CMD_SUCCESS)
                val p = process(pid)
                val dropAfter = faults.dropReadAfterBytes
                var sent = 0L
                var a = addr
                while (sent < length) {
                    val n = minOf(0x10000L, length - sent).toInt()
                    val chunk = ByteArray(n)
                    if (p != null) synchronized(lock) { copyOut(p, a, chunk) }
                    if (dropAfter != null && sent + n > dropAfter) {
                        out.write(chunk, 0, (dropAfter - sent).toInt().coerceAtLeast(0))
                        out.flush()
                        return false
                    }
                    out.write(chunk)
                    sent += n; a += n
                }
                return true
            }
            CMD_PROC_WRITE -> {
                writeRequests.incrementAndGet()
                if (body.capacity() < 16) { status(out, CMD_DATA_NULL); return false }
                val pid = body.int; val addr = body.long; val length = body.int
                status(out, CMD_SUCCESS)
                out.flush()
                val data = ByteArray(length).also { inp.readFully(it) }
                val p = process(pid)
                var ok = p != null
                if (!faults.ignoreWrites && p != null) {
                    synchronized(lock) { ok = copyIn(p, addr, data) }
                }
                status(out, if (faults.classicWriteStatus || ok) CMD_SUCCESS else CMD_ERROR)
                return true
            }
            CMD_CONSOLE_END -> return false
            CMD_BRANDING -> {
                val b = faults.brand ?: return !faults.closeOnUnknownCommand
                val bytes = (b + "\u0000" + faults.capabilityLevel).toByteArray(Charsets.US_ASCII)
                out.write(le(4).putInt(bytes.size).array()); out.write(bytes)
                return true
            }
            CMD_PROC_AUTH -> {
                if (!faults.ngScan) return !faults.closeOnUnknownCommand
                return ng.auth(body, inp, out)
            }
            CMD_SCAN_START -> return if (faults.ngScan) ng.iterativeStart(body, inp, out) else !faults.closeOnUnknownCommand
            CMD_SCAN_COUNT -> return if (faults.ngScan) ng.iterativeCount(body, inp, out) else !faults.closeOnUnknownCommand
            TURBO_CAPS -> {
                if (!faults.turbo) return !faults.closeOnUnknownCommand
                status(out, CMD_SUCCESS)
                out.write(le(16).putInt(1).putInt(faults.turboEngines).putInt(4).putInt(0).array())
                return true
            }
            TURBO_START -> return if (faults.turbo) ng.turboStart(body, inp, out, conn) else !faults.closeOnUnknownCommand
            TURBO_COUNT -> return if (faults.turbo) ng.turboCount(body, inp, out, conn) else !faults.closeOnUnknownCommand
            TURBO_GET -> return if (faults.turbo) ng.turboGet(body, out, conn) else !faults.closeOnUnknownCommand
            TURBO_END -> {
                if (!faults.turbo) return !faults.closeOnUnknownCommand
                if (authBits and 2 == 0) { status(out, CMD_DATA_NULL); return false }
                conn.turboAddrs = null; conn.turboVals = null; conn.turboCount = 0
                status(out, CMD_SUCCESS); return true
            }
            TURBO_CANCEL -> {
                if (!faults.turbo) return !faults.closeOnUnknownCommand
                if (authBits and 2 == 0 || body.capacity() < 4) { status(out, CMD_DATA_NULL); return false }
                val pid = body.int
                if (pid == 0) { status(out, CMD_DATA_NULL); return false }
                turboCancelPid = pid
                status(out, CMD_SUCCESS); return true
            }
            else -> return !faults.closeOnUnknownCommand // unknown commands: ignored (NG) or connection closed (classic)
        }
    }

    // ------------------------------------------------------------------ ps4debug-NG scan emulation

    private val ng = NgScan()

    private inner class NgScan {
        fun auth(body: ByteBuffer, inp: DataInputStream, out: OutputStream): Boolean {
            if (body.capacity() < 8 || body.int != AUTH_MAGIC) { status(out, CMD_DATA_NULL); return false }
            val flags = body.int
            lastAuthFlags = flags
            authAttempts.incrementAndGet()
            status(out, CMD_SUCCESS)
            val challenge = ByteArray(64).also { java.util.Random().nextBytes(it) }
            out.write(le(2).putShort(64).array()); out.write(challenge); out.flush()
            val response = ByteArray(64).also { inp.readFully(it) }
            if (faults.rejectAuth || !response.contentEquals(xorKeystream(challenge))) { status(out, CMD_DATA_NULL); return false }
            authBits = authBits or (flags and 3)
            status(out, CMD_SUCCESS)
            return true
        }

        private fun authed(out: OutputStream): Boolean {
            if (authBits and 2 == 0) { status(out, CMD_DATA_NULL); return false }
            return true
        }

        private fun sizeOf(vt: Int) = when (vt) { 0, 1 -> 1; 2, 3 -> 2; 4, 5, 8 -> 4; 6, 7, 9 -> 8; else -> 0 }
        private fun needsValue(ct: Int) = ((1 shl ct) and 0x114F) != 0 || ct == 4
        private fun needsPrevious(ct: Int) = ct in 5..10

        /** Integer compare for the compare types the app uses (0, 5, 7, 9, 10), signed or unsigned by type. */
        fun compare(ct: Int, vt: Int, len: Int, mem: ByteArray, mo: Int, ref: ByteArray?, ro: Int): Boolean {
            fun raw(b: ByteArray, o: Int): Long { var r = 0L; for (i in len - 1 downTo 0) r = (r shl 8) or (b[o + i].toLong() and 0xFF); return r }
            fun signed(v: Long): Long = when (len) { 1 -> v.toByte().toLong(); 2 -> v.toShort().toLong(); 4 -> v.toInt().toLong(); else -> v }
            val isSigned = vt % 2 == 1
            val m = raw(mem, mo)
            val r = ref?.let { raw(it, ro) } ?: 0L
            val cmp = if (isSigned) signed(m).compareTo(signed(r)) else java.lang.Long.compareUnsigned(m, r)
            return when (ct) {
                0 -> m == r
                5 -> cmp > 0
                7 -> cmp < 0
                9 -> m != r
                10 -> m == r
                else -> false
            }
        }

        private fun readMem(pid: Int, addr: Long, len: Int): ByteArray {
            val b = ByteArray(len)
            val p = process(pid) ?: return b
            synchronized(lock) { copyOut(p, addr, b) }
            return b
        }

        private fun delayFor(bytes: Long) {
            serverScannedBytes.addAndGet(bytes)
            val d = faults.scanDelayMsPerMiB
            if (d > 0) Thread.sleep(maxOf(1L, d * bytes / (1 shl 20)))
        }

        /** CMD_PROC_SCAN_START (proc.c proc_scan_start_handle). */
        fun iterativeStart(body: ByteBuffer, inp: DataInputStream, out: OutputStream): Boolean {
            serverScanCommands.incrementAndGet()
            if (!authed(out)) return false
            if (body.capacity() < 23) { status(out, CMD_DATA_NULL); return false }
            val pid = body.int; val address = body.long; val length = body.int.toLong() and 0xFFFFFFFFL
            val vt = body.get().toInt(); val ct = body.get().toInt(); val align = body.get().toInt() and 0xFF; val lenData = body.int
            val vl = sizeOf(vt)
            if (vl == 0) { status(out, CMD_DATA_NULL); return false }
            status(out, CMD_SUCCESS); out.flush()
            val pattern = if (needsValue(ct) && lenData > 0) ByteArray(lenData).also { inp.readFully(it) } else null
            status(out, CMD_SUCCESS)
            val step = if (align == 0) vl else align
            val batch = java.io.ByteArrayOutputStream()
            fun flush() {
                if (batch.size() == 0) return
                out.write(le(8).putLong(batch.size().toLong()).array()); batch.writeTo(out); batch.reset()
            }
            var pos = 0L
            while (pos < length) {
                val n = minOf(0x8000L, length - pos).toInt()
                val buf = readMem(pid, address + pos, n + vl)
                delayFor(n.toLong())
                var j = 0
                while (j < n && pos + j + vl <= length) {
                    if (compare(ct, vt, vl, buf, j, pattern, 0)) {
                        if (batch.size() > 0xFFE8 - vl) flush()
                        batch.write(le(4).putInt((pos + j).toInt()).array()); batch.write(buf, j, vl)
                    }
                    j += step
                }
                pos += j.toLong().coerceAtLeast(n.toLong())
            }
            flush()
            out.write(le(8).putLong(-1L).array())
            status(out, CMD_SUCCESS)
            return true
        }

        /** CMD_PROC_SCAN_COUNT client-driven stream (proc.c proc_scan_count_handle). */
        fun iterativeCount(body: ByteBuffer, inp: DataInputStream, out: OutputStream): Boolean {
            serverScanCommands.incrementAndGet()
            if (!authed(out)) return false
            if (body.capacity() < 18) { status(out, CMD_DATA_NULL); return false }
            val pid = body.int; val base = body.long; val vt = body.get().toInt(); val ct = body.get().toInt(); val lenData = body.int
            val vl = sizeOf(vt)
            if (vl == 0) { status(out, CMD_DATA_NULL); return false }
            status(out, CMD_SUCCESS); out.flush()
            val pattern = if (needsValue(ct) && lenData > 0) ByteArray(lenData).also { inp.readFully(it) } else null
            val entry = if (needsPrevious(ct)) 4 + vl else 4
            while (true) {
                out.flush()
                val chunkLen = Integer.reverseBytes(inp.readInt())
                if (chunkLen == -1) break
                if (chunkLen == 0) { out.write(le(8).putLong(-1L).array()); continue }
                if (chunkLen < 0 || chunkLen > 0x20000) break
                val chunk = ByteArray(chunkLen).also { inp.readFully(it) }
                val cb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                val batch = java.io.ByteArrayOutputStream()
                var off = 0
                while (off + entry <= chunkLen) {
                    val o = cb.getInt(off)
                    val addr = base + (o.toLong() and 0xFFFFFFFFL)
                    val cur = readMem(pid, addr, vl)
                    delayFor(vl.toLong())
                    val ok = if (needsPrevious(ct)) compare(ct, vt, vl, cur, 0, chunk, off + 4) else compare(ct, vt, vl, cur, 0, pattern, 0)
                    if (ok) { batch.write(chunk, off, 4); batch.write(cur) }
                    off += entry
                }
                if (batch.size() > 0) { out.write(le(8).putLong(batch.size().toLong()).array()); batch.writeTo(out) }
                out.write(le(8).putLong(-1L).array())
            }
            status(out, CMD_SUCCESS)
            return true
        }

        /** Turbo START, server-resident + segments path (scan_turbo.c proc_turboscan_start_handle). */
        fun turboStart(body: ByteBuffer, inp: DataInputStream, out: OutputStream, conn: Conn): Boolean {
            serverScanCommands.incrementAndGet()
            if (!authed(out)) return false
            if (body.capacity() < 27) { status(out, CMD_DATA_NULL); return false }
            val pid = body.int; body.long; body.int
            val vt = body.get().toInt(); val ct = body.get().toInt(); val align = body.get().toInt() and 0xFF
            val lenData = body.int; val flags = body.int
            if (turboCancelPid == pid) turboCancelPid = 0
            if (ct > 12 || lenData == 0) { status(out, CMD_DATA_NULL); return false }
            val vl = sizeOf(vt)
            status(out, CMD_SUCCESS); out.flush()
            val pattern = if (needsValue(ct)) ByteArray(lenData).also { inp.readFully(it) } else null
            status(out, CMD_SUCCESS); out.flush()
            val resident = flags and TS_SERVER_RESIDENT != 0
            val segmented = flags and TS_SNAPSHOT_SEGMENTS != 0
            val segs = ArrayList<Pair<Long, Long>>()
            if (resident && segmented) {
                val n = Integer.reverseBytes(inp.readInt())
                repeat(n) {
                    val b = ByteArray(12).also { inp.readFully(it) }
                    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                    segs += bb.long to (bb.int.toLong() and 0xFFFFFFFFL)
                }
            }
            if (!resident || !segmented) {
                // Non-resident streaming is not emulated: empty result stream.
                out.write(le(8).putLong(-1L).array()); status(out, CMD_SUCCESS); return true
            }
            val step = if (align == 0) vl else align
            val cap = faults.turboResidentCapBytes
            val addrs = ArrayList<Long>()
            val vals = java.io.ByteArrayOutputStream()
            var overflow = false
            loop@ for ((start, len) in segs) {
                var pos = 0L
                while (pos < len) {
                    if (turboCancelPid == pid) break@loop
                    val n = minOf(0x100000L, len - pos).toInt()
                    val buf = readMem(pid, start + pos, n + vl)
                    delayFor(n.toLong())
                    var j = 0
                    while (j < n && pos + j + vl <= len) {
                        if (compare(ct, vt, vl, buf, j, pattern, 0)) {
                            if ((addrs.size + 1L) * (8 + vl) > cap) { overflow = true; break@loop }
                            addrs += start + pos + j; vals.write(buf, j, vl)
                        }
                        j += step
                    }
                    pos += n
                }
            }
            if (overflow) {
                conn.turboAddrs = null; conn.turboVals = null; conn.turboCount = 0
                out.write(le(12).putInt(0).putLong(0).array())
                out.write(le(8).putLong(-1L).array())
                status(out, CMD_SUCCESS)
                return true
            }
            conn.turboPid = pid; conn.turboType = vt; conn.turboLen = vl
            conn.turboAddrs = addrs.toLongArray(); conn.turboVals = vals.toByteArray(); conn.turboCount = addrs.size
            out.write(le(12).putInt(1).putLong(addrs.size.toLong()).array())
            status(out, CMD_SUCCESS)
            return true
        }

        /** Turbo COUNT on the resident set (scan_turbo.c proc_turboscan_count_handle, resident branch). */
        fun turboCount(body: ByteBuffer, inp: DataInputStream, out: OutputStream, conn: Conn): Boolean {
            serverScanCommands.incrementAndGet()
            if (!authed(out)) return false
            if (body.capacity() < 22) { status(out, CMD_DATA_NULL); return false }
            val pid = body.int; body.long; val vt = body.get().toInt(); val ct = body.get().toInt(); val lenData = body.int; val flags = body.int
            if (ct > 12) { status(out, CMD_DATA_NULL); return false }
            if (turboCancelPid == pid) turboCancelPid = 0
            val vl = sizeOf(vt)
            status(out, CMD_SUCCESS); out.flush()
            val needsVal = intArrayOf(1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 0, 0, 1)[ct] == 1
            val pattern = if (needsVal && lenData > 0) ByteArray(lenData).also { inp.readFully(it) } else null
            if (flags and TS_SERVER_RESIDENT == 0) { status(out, CMD_SUCCESS); return true }
            val addrs = conn.turboAddrs
            val vals = conn.turboVals
            var newCount = 0
            if (addrs != null && vals != null && conn.turboLen == vl) {
                val nv = ByteArray(vals.size)
                for (i in 0 until conn.turboCount) {
                    if (turboCancelPid == conn.turboPid) break
                    val cur = readMem(conn.turboPid, addrs[i], vl)
                    delayFor(vl.toLong())
                    val ok = if (needsPrevious(ct)) compare(ct, vt, vl, cur, 0, vals, i * vl) else compare(ct, vt, vl, cur, 0, pattern, 0)
                    if (ok) { addrs[newCount] = addrs[i]; System.arraycopy(cur, 0, nv, newCount * vl, vl); newCount++ }
                }
                conn.turboVals = nv
                conn.turboCount = newCount
            }
            out.write(le(8).putLong(-1L).array())
            out.write(le(8).putLong(newCount.toLong()).array())
            status(out, CMD_SUCCESS)
            return true
        }

        fun turboGet(body: ByteBuffer, out: OutputStream, conn: Conn): Boolean {
            if (!authed(out)) return false
            if (body.capacity() < 12) { status(out, CMD_DATA_NULL); return false }
            val start = body.int.toLong() and 0xFFFFFFFFL; val want = body.int.toLong() and 0xFFFFFFFFL
            val addrs = conn.turboAddrs; val vals = conn.turboVals
            status(out, CMD_SUCCESS)
            if (addrs == null || vals == null) { out.write(le(4).putInt(0).array()); status(out, CMD_SUCCESS); return true }
            val total = conn.turboCount.toLong()
            val s0 = minOf(start, total)
            val e = minOf(total, s0 + want)
            val vl = conn.turboLen
            out.write(le(4).putInt((e - s0).toInt()).array())
            for (i in s0.toInt() until e.toInt()) {
                out.write(le(8).putLong(addrs[i]).array())
                out.write(vals, i * vl, vl); out.write(vals, i * vl, vl) // current, previous (= current)
            }
            status(out, CMD_SUCCESS)
            return true
        }
    }

    /** Optional bandwidth limit to approximate a Wi-Fi link in benchmarks. */
    private inner class ThrottledOutput(private val target: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            val bps = faults.linkBytesPerSecond
            if (bps <= 0) { target.write(b, off, len); return }
            // Pace in 8 KiB pieces *before* sending so the receiver observes the link speed.
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(8192, left)
                java.util.concurrent.locks.LockSupport.parkNanos(n * 1_000_000_000L / bps)
                target.write(b, o, n); target.flush()
                o += n; left -= n
            }
        }
        override fun flush() = target.flush()
        override fun close() = target.close()
    }

    /** Copies mapped bytes; unmapped bytes stay zero (real ps4debug behaviour). */
    private fun copyOut(p: Process, addr: Long, dst: ByteArray) {
        for (r in p.regions) {
            val s = maxOf(addr, r.start); val e = minOf(addr + dst.size, r.end)
            if (s < e) System.arraycopy(r.data, (s - r.start).toInt(), dst, (s - addr).toInt(), (e - s).toInt())
        }
    }

    /** Writes only if the whole range is mapped (mirrors ps4debug-NG's full-transfer check). */
    private fun copyIn(p: Process, addr: Long, src: ByteArray): Boolean {
        var covered = 0L
        for (r in p.regions) {
            val s = maxOf(addr, r.start); val e = minOf(addr + src.size, r.end)
            if (s < e) covered += e - s
        }
        if (covered < src.size) return false
        for (r in p.regions) {
            val s = maxOf(addr, r.start); val e = minOf(addr + src.size, r.end)
            if (s < e) System.arraycopy(src, (s - addr).toInt(), r.data, (s - r.start).toInt(), (e - s).toInt())
        }
        return true
    }

    private fun status(out: OutputStream, s: Int) = out.write(le(4).putInt(s).array())

    companion object {
        const val MAGIC = 0xFFAABBCC.toInt()
        const val CMD_VERSION = 0xBD000001.toInt()
        const val CMD_FW_VERSION = 0xBD000500.toInt()
        const val CMD_PROC_LIST = 0xBDAA0001.toInt()
        const val CMD_PROC_READ = 0xBDAA0002.toInt()
        const val CMD_PROC_WRITE = 0xBDAA0003.toInt()
        const val CMD_PROC_MAPS = 0xBDAA0004.toInt()
        const val CMD_PROC_INFO = 0xBDAA000A.toInt()
        const val CMD_CONSOLE_END = 0xBDDD0002.toInt()
        const val CMD_BRANDING = 0xBD000501.toInt()
        const val CMD_PROC_AUTH = 0xBDAACCFF.toInt()
        const val CMD_SCAN_START = 0xBDAACC01.toInt()
        const val CMD_SCAN_COUNT = 0xBDAACC02.toInt()
        const val TURBO_CAPS = 0xBDAACC10.toInt()
        const val TURBO_START = 0xBDAACC11.toInt()
        const val TURBO_COUNT = 0xBDAACC12.toInt()
        const val TURBO_GET = 0xBDAACC13.toInt()
        const val TURBO_END = 0xBDAACC14.toInt()
        const val TURBO_CANCEL = 0xBDAACC17.toInt()
        const val AUTH_MAGIC = 0xBB40E64D.toInt()
        const val TS_SERVER_RESIDENT = 0x2
        const val TS_SNAPSHOT_SEGMENTS = 0x10

        /** lfsr113 keystream XOR as in ps4debug-NG proc.c (independent copy for the mock). */
        fun xorKeystream(input: ByteArray): ByteArray {
            var z1 = 200; var z2 = 300; var z3 = 400; var z4 = 500
            return ByteArray(input.size) { i ->
                z1 = ((z1 shl 18) and -0x80000) xor ((z1 xor (z1 shl 6)) ushr 13)
                z2 = ((z2 shl 2) and -0x20) xor ((z2 xor (z2 shl 2)) ushr 27)
                z3 = ((z3 shl 7) and -0x800) xor ((z3 xor (z3 shl 13)) ushr 21)
                z4 = ((z4 shl 13) and -0x100000) xor ((z4 xor (z4 shl 3)) ushr 12)
                (input[i].toInt() xor z1 xor z2 xor z3 xor z4).toByte()
            }
        }
        const val CMD_SUCCESS = 0x80000000.toInt()
        const val CMD_ERROR = 0xF0000001.toInt()
        const val CMD_DATA_NULL = 0xF0000003.toInt()

        const val PROT_R = 1
        const val PROT_W = 2
        const val PROT_X = 4

        private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

        private fun fixed(s: String, n: Int): ByteArray {
            val b = ByteArray(n)
            val src = s.toByteArray(Charsets.US_ASCII)
            System.arraycopy(src, 0, b, 0, minOf(src.size, n - 1))
            return b
        }
    }
}

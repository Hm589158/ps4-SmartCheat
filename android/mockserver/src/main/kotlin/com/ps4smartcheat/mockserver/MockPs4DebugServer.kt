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
    }

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
            val out = s.getOutputStream().buffered(64 * 1024)
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
                if (faults.dropOnCommand == cmd) break
                if (faults.stallOnCommand == cmd) {
                    // Keep the socket open but never answer.
                    while (running && !s.isClosed) Thread.sleep(20)
                    break
                }
                val keepOpen = handle(cmd, ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN), inp, out)
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
    private fun handle(cmd: Int, body: ByteBuffer, inp: DataInputStream, out: OutputStream): Boolean {
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
            else -> return true // unknown commands in the 0xBD namespace are ignored without a reply
        }
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

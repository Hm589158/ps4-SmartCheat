package com.ps4smartcheat.core.scan

import com.ps4smartcheat.core.backend.ConnectOptions
import com.ps4smartcheat.core.protocol.Ps4DebugClient
import com.ps4smartcheat.mockserver.MockPs4DebugServer
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_R
import com.ps4smartcheat.mockserver.MockPs4DebugServer.Companion.PROT_W
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Repeatable comparison of the three scan backends on an identical dataset, run with `./gradlew :core:benchmark`
 * (excluded from the normal test task).
 *
 * What it measures and what it does not:
 *  - Network bytes are exact (counted on the client socket) and transfer to a real PS4.
 *  - Durations are measured against the in-process JVM mock. The mock's "server-side" scan runs on the
 *    host CPU, not the PS4's, so absolute times do NOT predict console performance. The simulated-link
 *    run throttles server→client bytes to approximate Wi-Fi; it is a model, not a measurement of a PS4.
 *  - CPU time and peak heap are for the whole test JVM (client + mock), sampled every 5 ms.
 */
class ScanBenchmark {
    private val pid = 1000
    private val heapSize = 32 shl 20
    private val target = 13_371_337

    private data class Step(val name: String, val backend: ScanBackendKind, val ms: Long, val bytesIn: Long, val bytesOut: Long, val results: Int)
    private data class Run(
        val tier: BackendPreference, val link: String, val steps: List<Step>, val cpuMs: Long, val peakHeapMiB: Long,
        val serverScannedMiB: Long, val readRequests: Int, val final: List<Long>, val refineGap: Int?,
    )

    private fun dataset(server: MockPs4DebugServer): List<Long> {
        val p = server.addProcess(MockPs4DebugServer.Process(pid, "eboot.bin"))
        val heap = p.region("anon:heap", 0x1_0000_0000L, heapSize, PROT_R or PROT_W)
        Random(2024).nextBytes(heap.data)
        p.region("eboot.bin", 0x40_0000L, 1 shl 20, PROT_R or PROT_W).also { Random(7).nextBytes(it.data) }
        val rnd = Random(99)
        val planted = (0 until 2000).map { 0x1_0000_0000L + (rnd.nextInt(heapSize / 4 - 1) * 4L) }.distinct().sorted()
        planted.forEach { poke(server, it, target) }
        return planted
    }

    private fun poke(server: MockPs4DebugServer, a: Long, v: Int) =
        server.poke(pid, a, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())

    private fun runOnce(tier: BackendPreference, linkBps: Long, latencyMs: Long = 0): Run = runBlocking {
        val server = MockPs4DebugServer()
        server.faults.enableNg(turbo = true)
        server.faults.linkBytesPerSecond = linkBps
        server.faults.linkLatencyMs = latencyMs
        val planted = dataset(server)
        val client = Ps4DebugClient()
        client.connect(ConnectOptions("127.0.0.1", server.port, readTimeoutMs = 120_000, firmwareProbeTimeoutMs = 300))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = ScanController(client, scope)
        val mem = ManagementFactory.getMemoryMXBean()
        val peak = java.util.concurrent.atomic.AtomicLong()
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = thread(isDaemon = true) {
            while (sampling.get()) { peak.accumulateAndGet(mem.heapMemoryUsage.used, ::maxOf); Thread.sleep(5) }
        }
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val cpu0 = os?.processCpuTime ?: 0L
        val reads0 = server.readRequests.get()
        val steps = ArrayList<Step>()
        suspend fun step(name: String, start: () -> Unit) {
            start()
            val st = withTimeout(600_000) { controller.status.first { it !is ScanStatus.Running } }
            assertTrue("$name: $st", st is ScanStatus.Completed)
            val s = controller.lastStats.value!!
            steps += Step(name, s.backend, s.durationMs, s.traffic.bytesIn, s.traffic.bytesOut, s.results)
        }
        val opts = ScanOptions(backend = tier)
        step("first exact") { controller.firstScan(pid, "eboot.bin", ValueType.INT32, ScanMode.EXACT, target.toString(), options = opts) }
        planted.take(50).forEach { poke(server, it, target + 1) }
        step("next exact") { controller.nextScan(ScanMode.EXACT, (target + 1).toString()) }
        planted.take(5).forEach { poke(server, it, target - 300) }
        step("next decreased") { controller.nextScan(ScanMode.DECREASED, null) }
        val final = controller.results()!!.let { r -> (0 until r.count).map { r.address(it) } }
        sampling.set(false)
        sampler.join()
        val cpu = ((os?.processCpuTime ?: 0L) - cpu0) / 1_000_000
        scope.cancel()
        client.disconnect()
        server.close()
        Run(
            tier, if (linkBps == 0L) "loopback" else "simulated ${linkBps / (1 shl 20)} MiB/s + ${latencyMs} ms/request", steps, cpu,
            peak.get() / (1 shl 20), server.serverScannedBytes.get() / (1 shl 20), server.readRequests.get() - reads0, final,
            controller.linkEstimate?.breakEvenGap,
        )
    }

    @Test fun compareBackends() {
        // Warm-up (JIT) so the first measured tier is not penalised.
        runOnce(BackendPreference.CLIENT, 0)
        val runs = ArrayList<Run>()
        for (link in listOf(0L, 8L shl 20)) {
            for (tier in listOf(BackendPreference.CLIENT, BackendPreference.SERVER, BackendPreference.TURBO)) {
                runs += runOnce(tier, link, if (link == 0L) 0 else 2)
            }
        }
        // All tiers must find the same final candidates.
        val expected = runs.first().final
        assertEquals(5, expected.size)
        runs.forEach { assertEquals("${it.tier}/${it.link}", expected, it.final) }

        val sb = StringBuilder()
        sb.appendLine("# Scan backend benchmark (mock server)")
        sb.appendLine()
        sb.appendLine("Dataset: 33 MiB of rw memory (32 MiB random heap + 1 MiB), ${2000} planted Int32 values; steps: exact → change 50 → exact → lower 5 → decreased.")
        sb.appendLine("JVM ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} CPUs. Mock server runs in-process: times are NOT PS4 times; network bytes are exact. The simulated link (bandwidth + per-request latency) is a model, not a PS4 measurement.")
        sb.appendLine()
        sb.appendLine("| Link | Tier | Step | Backend used | Time ms | Bytes in | Bytes out | Results |")
        sb.appendLine("|---|---|---|---|---:|---:|---:|---:|")
        for (r in runs) for (s in r.steps) {
            sb.appendLine("| ${r.link} | ${r.tier.label} | ${s.name} | ${s.backend.label} | ${s.ms} | ${s.bytesIn} | ${s.bytesOut} | ${s.results} |")
        }
        sb.appendLine()
        sb.appendLine("| Link | Tier | Total ms | Total network bytes | CPU ms (JVM) | Peak heap MiB (JVM) | Mock server-scanned MiB | CMD_PROC_READ count | Adaptive refine gap |")
        sb.appendLine("|---|---|---:|---:|---:|---:|---:|---:|---:|")
        for (r in runs) {
            sb.appendLine(
                "| ${r.link} | ${r.tier.label} | ${r.steps.sumOf { it.ms }} | ${r.steps.sumOf { it.bytesIn + it.bytesOut }} | ${r.cpuMs} | ${r.peakHeapMiB} | ${r.serverScannedMiB} | ${r.readRequests} | ${r.refineGap ?: "-"} |",
            )
        }
        val text = sb.toString()
        println(text)
        val outDir = File(System.getProperty("benchmark.out") ?: "build/reports/benchmark")
        outDir.mkdirs()
        File(outDir, "scan-benchmark.md").writeText(text)
    }
}

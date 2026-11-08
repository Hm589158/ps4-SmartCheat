package com.ps4smartcheat.mockserver

import java.net.InetAddress
import kotlin.random.Random

/**
 * Standalone mock ps4debug server for testing the Android app without a console.
 *
 *   ./gradlew :mockserver:run --args="7440"
 *
 * Then connect the app to this computer's LAN IP on port 7440. Commands on stdin:
 *   coins <value>   set the simulated currency (int32 at DEMO_COINS_ADDRESS)
 *   spend <n>       subtract n coins
 *   buy             run the demo game's "spend one coin" routine (honours code patches; trips watchpoints)
 *   show            print the simulated values
 *   quit
 *
 * THIS IS A SIMULATOR. Values shown by the app while connected to it are not from a PS4.
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 7440
    val bind = args.getOrNull(1) ?: "0.0.0.0"
    val server = MockPs4DebugServer(port, InetAddress.getByName(bind))
    val game = DemoGame.install(server)
    println("Mock ps4debug server listening on $bind:${server.port} (SIMULATED, not a PS4)")
    println("Demo process pid=${game.pid} '${game.name}' coins=5000 at 0x%X".format(DemoGame.COINS_ADDRESS))
    while (true) {
        val line = readlnOrNull()?.trim() ?: break
        val parts = line.split(Regex("\\s+"))
        when (parts[0]) {
            "coins" -> parts.getOrNull(1)?.toIntOrNull()?.let { server.pokeInt32(game.pid, DemoGame.COINS_ADDRESS, it) }
            "spend" -> parts.getOrNull(1)?.toIntOrNull()?.let {
                server.pokeInt32(game.pid, DemoGame.COINS_ADDRESS, server.peekInt32(game.pid, DemoGame.COINS_ADDRESS) - it)
            }
            "buy" -> DemoGame.spendCoin(server)
            "show" -> println("coins=${server.peekInt32(game.pid, DemoGame.COINS_ADDRESS)}")
            "quit", "exit" -> break
            "" -> {}
            else -> println("commands: coins <v> | spend <n> | buy | show | quit")
        }
    }
    server.close()
}

/** A deterministic fake game process used by tests and the standalone server. */
object DemoGame {
    const val PID = 77
    const val TEXT_START = 0x0000_0040_0000L
    const val DATA_START = 0x0000_0050_0000L
    const val HEAP_START = 0x0000_0020_0000_0000L
    const val COINS_ADDRESS = DATA_START + 0x120
    const val HEAP_SIZE = 4 * 1024 * 1024

    fun install(server: MockPs4DebugServer, seed: Int = 1234): MockPs4DebugServer.Process {
        server.addProcess(MockPs4DebugServer.Process(1, "SceSysCore.elf"))
        server.addProcess(MockPs4DebugServer.Process(40, "SceShellUI", titleId = "NPXS20001", path = "/system/vsh/app/NPXS20001/eboot.bin"))
        val p = server.addProcess(
            MockPs4DebugServer.Process(PID, "eboot.bin", titleId = "MOCK00001", contentId = "MOCK-CONTENT", path = "/app0/eboot.bin"),
        )
        val text = p.region("eboot.bin", TEXT_START, 0x10000, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_X)
        p.region("eboot.bin", DATA_START, 0x10000, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_W)
        val heap = p.region("anon:heap", HEAP_START, HEAP_SIZE, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_W)
        Random(seed).nextBytes(text.data)
        Random(seed + 1).nextBytes(heap.data)
        server.pokeInt32(PID, COINS_ADDRESS, 5000)
        server.poke(PID, SPEND_ROUTINE, SPEND_CODE)
        return p
    }

    /**
     * Real x86-64 code of the demo game's "spend one coin" routine:
     *
     *     0x402000  8B 05 1A E1 0F 00   mov eax, dword ptr [rip+0xFE11A]   ; load coins (0x500120)
     *     0x402006  83 E8 01            sub eax, 1
     *     0x402009  89 05 11 E1 0F 00   mov dword ptr [rip+0xFE111], eax   ; store coins
     *     0x40200F  C3                  ret
     */
    const val SPEND_ROUTINE = TEXT_START + 0x2000
    const val LOAD_COINS = SPEND_ROUTINE
    const val SUB_ONE = SPEND_ROUTINE + 6
    const val STORE_COINS = SPEND_ROUTINE + 9
    val SPEND_CODE = byteArrayOf(
        0x8B.toByte(), 0x05, 0x1A, 0xE1.toByte(), 0x0F, 0x00,
        0x83.toByte(), 0xE8.toByte(), 0x01,
        0x89.toByte(), 0x05, 0x11, 0xE1.toByte(), 0x0F, 0x00,
        0xC3.toByte(),
    )

    /**
     * Runs the routine against the mock's memory: the load and the store are reported to the debugger emulation
     * (they trip watchpoints), and the `sub` only happens if its bytes are still `83 E8 01` — so a NOP patch made by
     * the app really stops the coins from decreasing.
     */
    fun spendCoin(server: MockPs4DebugServer, pid: Int = PID) {
        server.gameAccess(pid, LOAD_COINS, 6, COINS_ADDRESS, 4, write = false)
        var eax = server.peekInt32(pid, COINS_ADDRESS)
        val sub = server.peek(pid, SUB_ONE, 3)
        if (sub.contentEquals(byteArrayOf(0x83.toByte(), 0xE8.toByte(), 0x01))) eax -= 1
        server.pokeInt32(pid, COINS_ADDRESS, eax)
        server.gameAccess(pid, STORE_COINS, 6, COINS_ADDRESS, 4, write = true, registers = mapOf("rax" to (eax.toLong() and 0xFFFFFFFFL)))
    }
}

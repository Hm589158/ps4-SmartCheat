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
            "show" -> println("coins=${server.peekInt32(game.pid, DemoGame.COINS_ADDRESS)}")
            "quit", "exit" -> break
            "" -> {}
            else -> println("commands: coins <v> | spend <n> | show | quit")
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
        server.addProcess(MockPs4DebugServer.Process(40, "SceShellUI"))
        val p = server.addProcess(
            MockPs4DebugServer.Process(PID, "eboot.bin", titleId = "MOCK00001", contentId = "MOCK-CONTENT", path = "/app0/eboot.bin"),
        )
        val text = p.region("eboot.bin", TEXT_START, 0x10000, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_X)
        p.region("eboot.bin", DATA_START, 0x10000, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_W)
        val heap = p.region("anon:heap", HEAP_START, HEAP_SIZE, MockPs4DebugServer.PROT_R or MockPs4DebugServer.PROT_W)
        Random(seed).nextBytes(text.data)
        Random(seed + 1).nextBytes(heap.data)
        server.pokeInt32(PID, COINS_ADDRESS, 5000)
        return p
    }
}

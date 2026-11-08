# What writes / accesses — watchpoints and the port-755 relay

"What writes?" and "What accesses?" use ps4debug-NG's debugger: the app attaches to the game, puts a hardware
watchpoint (x86 debug register DR0) on the address and lists every instruction that touches it. Game memory is not
changed. Every hit pauses the game for one network round trip; the app resumes it immediately and detaches when you
press Stop, leave the screen, after 1000 hits or after 2 minutes. Closing the connection also detaches (the PS4 does
that itself).

## Why port 755 matters

When the app attaches, **the PS4 opens a connection back to the phone on TCP port 755** to report hits
(ps4debug-NG `debug.c`, `connect_debugger`). The port is fixed in the payload. Android only lets apps listen on ports
below 1024 with root, so:

| Phone | What to do |
|---|---|
| Rooted, app allowed to bind 755 | Nothing: keep Settings → Debugger → port **755**, relay empty |
| Not rooted (normal) | Run a small relay on a computer in the same network (below) |

If the PS4 cannot connect back, attaching fails with an error. ps4debug-NG then leaves the game attached without a
session — restart the game before trying again. The app always opens its listener *before* asking the PS4 to attach.

## Relay setup (not rooted)

The relay listens on 755 on a computer and forwards every connection to the phone on a high port, e.g. 7550.

1. Note the phone's IP (Settings → About phone → Status, or your router) and the computer's IP.
2. Start the relay on the computer:

   **Linux / macOS** (socat):
   ```sh
   sudo socat TCP-LISTEN:755,fork,reuseaddr TCP:<phone-ip>:7550
   ```
   **Windows** (administrator command prompt):
   ```bat
   netsh interface portproxy add v4tov4 listenport=755 listenaddress=0.0.0.0 connectport=7550 connectaddress=<phone-ip>
   netsh advfirewall firewall add rule name="ps4debug relay" dir=in action=allow protocol=TCP localport=755
   ```
   (Remove it later with `netsh interface portproxy delete v4tov4 listenport=755 listenaddress=0.0.0.0`.)
3. In the app: Settings → Debugger → **Interrupt listen port 7550**, **Relay IPv4 address = the computer's IP**.
4. Address list → open an entry → **What writes?** → Start → play until the value changes.

The relay must be on the local network (the same rule as the PS4 address). Nothing is sent to the internet.

## Reading the results

* Each row is one instruction with how often it hit, the thread name and — when the instruction's operands could be
  computed from the registers — the address it accessed. Tap it for all general-purpose registers.
* Data watchpoints trigger *after* the instruction ran; the app decodes the instruction that ends at the reported
  RIP. "Open in disassembler" jumps there, where you can build an ON/OFF patch.
* Sizes are 1, 2, 4 or 8 bytes and must be aligned; the app shrinks the size when the address is not aligned.
* Only one debugger session can exist on the PS4. Close other tools (PS4CheaterNeo …) first.

## Status

Implemented from the ps4debug-NG 1.3.4 source and verified against the simulated server in CI. Not yet reported on
real hardware — please file a hardware test report.

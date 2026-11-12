# Scanning performance

## Backends (automatic order)

| Tier | Requires | What crosses the network |
|---|---|---|
| **Turbo Scan (PS4)** | ps4debug-NG ≥ 1.3 (CAPS reports server-resident), auth OK, integer type, natural alignment | First scan: segment list up + count down + fetched survivors (≤ max results). Refines: one small request + survivors |
| **Server-side scan (PS4)** | ps4debug-NG, auth OK, integer type, natural alignment | First scan: matches only `(offset, value)`. Refines: candidate offsets up, matches down |
| **Chunked scan (Android)** | any ps4debug | First scan: every byte of every scanned region. Refines: spans around candidates |

Fallback is automatic and the reason is shown under the scan result (e.g. "Float is compared on Android",
"PS4 resident result buffer full", "PS4 session lost after reconnect"). Settings → Scan backend can force a tier;
unsupported choices still fall back.

Adaptive behaviour:
* **Region selection:** only readable+writable, non-executable regions by default (read-only/executable opt-in),
  invalid/unmapped regions skipped, unmapped candidates dropped before every refine.
* **Refine choice:** in Automatic mode the server COUNT refine is used only when its estimated cost (candidate upload
  + round trips) is lower than the Android span reads; Turbo refines are always used while the PS4 session is valid.
* **Refine batching:** Android refines split read spans at the break-even gap = measured RTT × bandwidth
  (measured once per connection with four tiny reads).
* **Bounded memory:** result cap (default 1,000,000, `8 + size` bytes each), 1 MiB read chunks, 64 MiB server slices,
  Turbo results fetched in 65,536-record pages, at most 100,000 per step (`turboFetchMax`); when the local list is capped the PS4 still holds the full Turbo set.

## Measured (automated benchmark, mock server)

`./gradlew :core:benchmark` runs the same dataset (33 MiB rw memory, 2000 planted Int32 values; exact → change 50 →
exact → lower 5 → decreased) through all three tiers. CI publishes the report on every run (job summary + artifact).

**What is real:** network byte counts (counted on the client socket) and request counts.
**What is not:** durations come from an in-process JVM mock; its "server-side" compare runs on the host CPU with a
naive byte loop, so server-tier times say nothing about the PS4. The "simulated" link (8 MiB/s + 2 ms per request)
is a model of Wi-Fi, not a measurement.

Run on the development container (JDK 21, 4 CPUs):

| Link | Tier | Total ms | Total network bytes |
|---|---|---:|---:|
| loopback | Android only | 187 | 54,638,156 |
| loopback | Server-side | 511 | 25,514 |
| loopback | Turbo | 367 | 33,623 |
| simulated 8 MiB/s + 2 ms/request | Android only | 8,073 | 44,701,856 |
| simulated 8 MiB/s + 2 ms/request | Server-side | 112 | 25,514 |
| simulated 8 MiB/s + 2 ms/request | Turbo | 185 | 33,623 |

Network traffic for the full 3-step workflow drops from ~44–55 MB (Android) to ~25–34 KB with either server tier
(≈1,700× less). On the simulated link that is the dominant cost. Real PS4 times depend on the console's memory
read speed and are **not measured yet**.

Android-fallback refine batching on the simulated link (same dataset, "next exact" step):

| Batching rule | Bytes in | Time ms |
|---|---:|---:|
| fixed 64 KiB spans (previous behaviour) | 20,011,480 | 3,407 |
| adaptive break-even gap (measured 18,292 B) | 9,811,424 | 3,125 |

## Responsiveness of the connection

There is one TCP connection to ps4debug-NG and requests take turns, so on a weak Wi-Fi every action costs a few round
trips (a confirmed, verified write: read the current bytes, write, read back). What the app does about it:

* **One packet per request.** Header, body and data are buffered and leave with one flush (they were two TCP packets;
  on a lossy link each packet is another chance for a retransmission delay). Both sides use `TCP_NODELAY`
  (ps4debug-NG `server.c` sets it on every client socket), so nothing waits for Nagle.
* **User actions first.** Keep-alive, the game-exit watcher, freezes and live values run as `BackgroundRequest`: while
  a user action is waiting they step aside, also after they already queued for the connection, for at most 2 s (so a
  freeze is never starved). A tap waits at most for the one request already running.
* **Large socket buffers** (1 MiB receive) set before connecting, so big reads keep flowing on a distant link.
* **Low-latency Wi-Fi lock** while connected (Android 10+: `WIFI_MODE_FULL_LOW_LATENCY`; older: high-performance),
  so the phone's Wi-Fi power saving does not add its sleep delay to each answer.
* **One memory map per operation** and joined reads of neighbouring parts (1.0.6/1.0.7).
* **Link time shown.** The Connection screen shows the smoothed round trip of small requests (`linkMillis`) and, above
  100 ms, what helps: phone near the router, 5 GHz, PS4 on a cable, fewer live values and freezes.

Measured on the simulated server only (tests `LinkTest`): one socket write per request, a user read finishing before
queued background reads, background work still served under constant load.

## Not yet measured
Real PS4 (FW 11.00, GoldHEN, ps4debug-NG) scan times, console CPU load during Turbo scans, aliasing on/off
comparison. See docs/TESTING.md for the manual procedure.

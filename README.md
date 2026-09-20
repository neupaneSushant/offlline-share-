# OfflineShare

LocalSend-style file transfer that doesn't need a Wi-Fi network to exist.
One phone creates the Wi-Fi link itself, the other scans a QR code to join it,
and files move at whatever the radio can actually do — no router, no mobile
data, no internet.

## The idea

LocalSend and its relatives all assume both devices are already on the same
Wi-Fi network. That's the one assumption that breaks exactly when you need it
most: in a car, on a trip, at a venue with captive-portal Wi-Fi, or anywhere
the network isolates clients from each other.

The fix is to stop waiting for a network and make one. Android can bring up a
Wi-Fi Direct group entirely on its own, and the group owner runs an ordinary
WPA2 access point. So the "hotspot" here isn't a fallback to something slow —
it *is* the fast path, and the transfer runs at full link speed over it.

## Getting full speed out of it

"Use a hotspot" and "go fast" fight each other unless you're deliberate about
it. Four things carry most of the difference, and each one is a real code
path rather than a config value:

**Ask for 5 GHz.** This is the single biggest factor, worth roughly 5–10x.
Wi-Fi Direct defaults to 2.4 GHz, which is crowded and slow. `HotspotHost`
requests `GROUP_OWNER_BAND_5GHZ` when it creates the group, and falls back
automatically when the device or the regulatory domain refuses — a slower
group beats no group. The UI reports the band the group actually landed on,
not the one that was requested, so a slow transfer never looks like a bug.

**Raise the socket buffers.** A 64 KiB receive window caps throughput at
`window / RTT` no matter how fast the radio is — which is why so many
"gigabit" transfers mysteriously top out near 100 Mbit/s. The listener asks
for 2 MiB *before* bind, because the window scale factor is negotiated during
the SYN handshake and can't be grown afterwards.

**Use several TCP connections.** One stream can't keep a Wi-Fi link full:
every retransmit drains that stream's congestion window while the radio idles.
Four connections pull from a shared work queue, so a stream that hits a bad
moment doesn't hold up the others. Work is *claimed*, not pre-assigned —
static partitioning leaves three streams idle while the unlucky one finishes.

**Don't copy the bytes.** The sender uses `FileChannel.transferTo`, which
becomes a `sendfile` syscall — payload goes from page cache to socket without
entering user space. That's what lets a mid-range phone saturate a 5 GHz link
without pinning a CPU core.

Two more that only show up in practice: the transfer holds a
`WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi lock, because Android parks the radio
between beacons when the screen is off and that alone can cost most of your
throughput; and the guest device calls `bindProcessToNetwork`, without which
sockets quietly route over cellular and fail to reach the host at all.

## How a transfer goes

```
Receiver                                  Sender
────────                                  ──────
creates Wi-Fi Direct group (5 GHz)
shows QR: SSID + passphrase + address
                                          scans QR
                                          joins as a normal Wi-Fi client
                                          binds its sockets to that network
        ◄──── control connection ─────────
        ◄──── HELLO / OFFER ──────────────
   asks the user to accept
        ───── ACCEPT + resume offsets ───►
        ◄──── 4 parallel data streams ────
        ───── RESULT ────────────────────►
group torn down, locks released
```

Hosting the network and receiving the files are separate roles. The common
case is that the receiver hosts, but `hostAndSend` covers the reverse for
devices that can't form a group — there the guest announces itself over UDP
broadcast and the host dials it, which is what `Discovery` is for.

## Layout

| Module | What it is | Buildable without an Android SDK |
| --- | --- | --- |
| `:protocol` | Wire format and transfer engine. Pure Kotlin/JVM, zero dependencies. | Yes |
| `:app` | Android app: hotspot lifecycle, QR pairing, Compose UI. | No |

The split is deliberate. Everything that can be tested without a phone lives
in `:protocol`, and `settings.gradle.kts` drops `:app` from the build when no
SDK is present — so `./gradlew :protocol:test` works on any machine with a
JDK, and a missing SDK doesn't fail a build that has nothing to do with
Android.

## Building

```bash
./gradlew :protocol:test          # transport + end-to-end transfers, no SDK needed
./gradlew :app:assembleDebug      # needs an Android SDK (see local.properties)
```

## State of the code

`:protocol` is built and tested — 28 tests covering the wire format, pairing
codes, and end-to-end transfers over real sockets: multi-file, nested paths,
resume after an interrupted run, declined offers, checksum verification,
single-stream mode, and rejection of unauthorized data streams. Loopback
throughput is around 117 MB/s, comfortably above any Wi-Fi link, which is the
point — it says the framing and threading aren't what limits a real transfer.

**`:app` has never been compiled.** It was written without an Android SDK
available, so treat it as a careful first draft rather than working code:
expect to fix compile errors on the first build. The CI workflow builds it on
every push, which is the fastest way to find out what needs fixing.

Beyond that, the parts that can only be judged on real hardware — whether a
given phone will form a 5 GHz group, how the `WifiNetworkSpecifier` dialog
behaves across OEM skins, actual throughput between two specific devices —
are exactly the parts no amount of local testing can settle. Two phones and
an afternoon will tell you more than anything else at this stage.

### Known gaps

- Received files land in `Android/data/dev.offshare.app/files/OfflineShare`,
  which needs no permissions and supports the positional writes the parallel
  receiver depends on, but is awkward to browse. A MediaStore export step
  would fix that.
- Thousands of tiny files will be slower than they should be: each one costs
  a work unit and a file open. Batching small files into a single stream
  would help.
- iOS can't create a hotspot programmatically at all, so a future iOS client
  could only ever be the guest.
- `minSdk` is 29. Both APIs the design rests on — `WifiP2pConfig.Builder` for
  band selection and `WifiNetworkSpecifier` for joining without a trip to
  Settings — arrived in Android 10.

## Security

The link is WPA2 with a fresh 12-character random passphrase per session that
only ever travels by QR code. Data connections must present a 16-byte session
token minted by the receiver, so another device on the same hotspot can't
inject into a transfer in progress. Incoming file paths are stripped of `..`
and absolute prefixes before anything is written.

Bulk payload rides on WPA2 rather than a second layer of app-level encryption.
That's a deliberate trade — on phone-class CPUs, encrypting again would cost
more throughput than it buys, given the link is already encrypted and lives
for the length of one transfer. `TransferConfig.verifyIntegrity` adds
per-chunk CRC32 for flaky *storage*; it is off by default because it forces
the sender off the `sendfile` path.

The app does hold the `INTERNET` permission, because Android gates every
socket on it — even a UDP broadcast to a hotspot the app created itself. It is
not evidence of anything reaching the internet: there is no HTTP client and no
remote endpoint anywhere in the code, and the only sockets opened are to the
address carried in the pairing QR code.

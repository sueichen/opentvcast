# Architecture

How opentvcast is put together, and — more importantly — **why**. This file is
written against the code, not against a design that was once intended. If you
change a dependency direction, move a class between modules, or add a new
platform call inside a protocol module, change this file in the same commit.

Nothing here lists permissions that are not in the manifest, and no layer is
described that does not exist in the tree.

---

## 1. What this is

opentvcast is a **receiver** for Android TV: it advertises itself on the local
network and plays what a sender (macOS, iOS, iPadOS) streams at it. It is not a
sender, has no sender UI, and never initiates a session.

Two constraints shape everything below:

1. **GPLv3** (see `README.md` → Licence). The AirPlay FairPlay path builds on
   reverse-engineered code that is GPL-only, so the whole work has to be GPLv3.
2. **Real TV firmware is hostile.** Some devices refuse multicast unless a
   `MulticastLock` is held, some hand out unexpected interface addresses, some
   keep a port alive after you close it. Section 3 exists for exactly that.

---

## 2. Module map

Six Gradle modules. Dependency direction is strictly downwards; nothing below
depends on anything above it.

| Module | Plugin | Role | May depend on |
|---|---|---|---|
| `:core` | Kotlin **JVM** (no Android) | Contracts and pure logic every protocol shares | *(nothing)* |
| `:platform` | Android library | Uses of `android.*` and Timber that the protocol modules need | `:core` |
| `:airplay` | Android library + NDK | The whole AirPlay / RAOP receiver | `:core` (api), `:platform` |
| `:dlna` | Android library | UPnP AV MediaRenderer: SSDP, description + SCPDs, SOAP control, GENA | `:core` (api), `:platform` |
| `:app` | Android application | UI, foreground service, settings, wiring | all of the above |
| `:test-runner` | Kotlin **JVM** (no AGP) | Aggregated test suite — see §6 | *sources only* |

Why `:core` is plain Kotlin/JVM: a contract that cannot even accidentally reach
`android.*` is one that can be unit-tested without Robolectric, and one that
cannot smuggle framework access into protocol code.

Why `:platform` exists separately: `Logger`, `NetworkUtils` and `Base64Util`
genuinely need Android. Keeping them in `:app` forced the protocol modules to
depend on the application layer; keeping them in `:core` broke `:core`'s purity.
`:platform` is that escape hatch and nothing more.

`:dlna` mirrors `:airplay`'s shape: protocol decisions live in pure, unit-tested
classes (SSDP matching, UPnP documents, the SOAP dispatcher, the HTTP router), and the
socket loops plus `MediaPlayer` are thin adapters on top. Its own port is allocated, not
assumed — `LOCATION` and every document URL derive from the granted port.

---

## 3. The compatibility choke point

Protocols do **not** touch `WifiManager`, `ConnectivityManager`, or construct
their own sockets and locks. Everything arrives through one interface:

```
ReceiverEnvironment  (core/src/main/kotlin/tv/opentvcast/core/net/ReceiverEnvironment.kt)
├── scope            SupervisorJob scope owned by the foreground service
├── localAddress     StateFlow<InetAddress?>            the address to advertise
├── allAddresses     StateFlow<List<InetAddress>>       dual-stack advertisement
├── displayName      already resolved against user settings
├── surfaceSink      SurfaceSink — ask for a target, receive a lease
├── multicastLock    MulticastLockHandle — reference counted
├── eventBus         CastEventBus — one-shot events the UI consumes once
├── allocatePort(preferred) -> Int
└── releasePort(port)
```

Four defaults back it (`DefaultReceiverEnvironment`, and `:platform`'s
`AndroidReceiverEnvironment` supplying the Wi-Fi-backed pieces):

| Facility | Class | Why it needs to exist |
|---|---|---|
| Multicast lock | `RefCountedMulticastLock` | One tag per *concern*, not per class, so DLNA's SSDP cannot release AirPlay's lock |
| Port reservation | `ServerSocketPortAllocator` | Rebinds and reports the port actually granted — never advertise the port you *asked* for |
| Interface choice | `DefaultInterfaceSelector` | Prefers a usable IPv4 address instead of whatever enumeration returns first |
| Network changes | `CoalescingNetworkMonitor` | Firmware fires several connectivity broadcasts per transition; coalesce, then re-register once |

This is not defensive architecture for its own sake: every one of those four
behaviours was missing upstream, and each produced a silent failure.

---

## 4. AirPlay receiver (`:airplay`)

Entry point `AirPlayReceiver`; control plane `RtspHandler` and its peers.

```
mDNS advertise → RTSP control on 7000 → pairing/verify → SETUP → streaming

streaming variants:
  mirroring video   MirrorStreamServer → decrypted H.264 → VideoDecoder → SurfaceSink
  mirroring audio   AudioStreamServer  → AES-128-CTR     → AudioPlayer (AudioTrack)
  legacy RAOP audio UDP RTP on 6001    → AlacDecoder     → AudioPlayer
  buffered audio    BufferedAudioServer (AirPlay 2 type 103)
  still photos      PhotoHandler       → photoFrame flow
  URL video         AirPlayVideoPlayer (MediaPlayer onto the same Surface)
  clock sync        TimingHandler / NTP on 6002
  reverse control   DacpClient (TV remote → sender's _dacp._tcp service)
```

`RtspHandler` is deliberately split rather than one large file:

| Class | Owns |
|---|---|
| `RtspHandler` | Connection loop and verb routing |
| `RtspResponseWriter` | Response serialisation (including the direct-socket 503 path) |
| `RtspPairing` | Pairing / PIN state machine (`pair-setup`, `pair-setup-pin`, `pair-pin-start`, `pair-verify`) |
| `RtspVideoControl` | URL video mode (`play`/`rate`/`scrub`/`stop`/`playback-info`) |
| `RtspNowPlayingControl` | `GET/SET_PARAMETER` — remembered volume, artwork, metadata |

Two cross-cutting peers:

- `NowPlayingState` — the audio-only "now playing" card. **Every transition
  returns the value to emit**, so a mutation cannot update state and forget to
  recompute the card.
- `CastServiceContract` — the wire strings that both the real `CastService` and
  the `:test-runner` stub re-export, so the two cannot drift apart.

---

## 5. Application layer (`:app`)

- **`MainActivity`** — the only Activity. It binds `CastService`, collects the
  service flows, and picks exactly one overlay. That priority is not inline any
  more: `OverlayDecision` owns it and the tests exercise it directly:
  **PIN > now-playing card > CONNECTED streaming screen > photo > hidden.**
- **`RebindableCollectorGroup`** — models "one bind round" of collectors. Without
  it, every reconnect stacked another set of collectors doing duplicate UI work.
- **`CastService`** — foreground service owning receiver lifecycle. Exposes
  `serviceState`, `airPlayState`, `dlnaState`, `activeConnection`, `photoFrame`,
  `nowPlaying`, `pairingPin`.
- **`ServiceController`** — the only supported way to start/stop/restart it; the
  action strings live in `CastServiceContract`.
- **`ConnectionMapping`** — the `ProtocolState` → `ActiveConnection` table,
  shared by the service and its tests.
- **`SettingsRepository`** — DataStore-backed settings; `AppSettings` holds the
  defaults plus `sanitizeDisplayName`, the single normalisation a display-name
  input goes through (trim + mDNS length cap).
- **`ReceiverRegistry`** — owns protocol receivers and arbitrates claims. It is
  `AutoCloseable`, so a discarded registry stops observing instead of leaking its
  coroutine scope for the life of the process.
- **`BootReceiver`** — emits nothing but a decision: only the real boot broadcast,
  and only when the user enabled autostart.
- **Flavours** — `googletv` and `firetv`, differing in launcher/dependency
  details only.

Permissions declared: INTERNET, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE,
CHANGE_WIFI_MULTICAST_STATE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_CONNECTED_DEVICE, RECEIVE_BOOT_COMPLETED,
POST_NOTIFICATIONS. If you add one, justify it in the manifest next to it.

---

## 6. Testing architecture

Three-plus-one tiers, each covering something the others cannot. The exact tasks
live in `docs/TESTING.md`; the reasons live here.

| Tier | Surface | Catches |
|---|---|---|
| `:core:test` | plain JUnit | Broken contracts everything else depends on |
| `:test-runner:test` | Kotlin/JVM, **no AGP**; production + test sources compiled as one unit | Anything reaching `internal` across modules; runs where Google Maven is unreachable |
| `:platform` / `:airplay` / `:app` unit tests | AGP + Robolectric | Broken **module boundaries** and UI compile errors — precisely what the aggregate runner cannot see |
| `:app:assemble{Flavor}DebugAndroidTest` | compile gate only | Rotting instrumented sources |

That last gate exists because a stale `MainActivityTest` sat in a dead package,
referenced view ids deleted months earlier, and no one noticed: instrumented
tests never *run* in CI (no emulator), but compiling them costs seconds.

The aggregate runner's blind spot is documented in its own build file and worth
restating: **it cannot detect a broken module boundary.** A reference from
`:airplay` to an `:app`-only class compiles happily there. The AGP build is the
structural check.

Several extractions exist specifically because tests once carried hand-copied
replicas that silently went stale — `ConnectionMapping`, `OverlayDecision`,
`NowPlayingState`, `CastServiceContract`, `SpsParser`,
`TimingHandler.millisToNtpTimestamp`. If you find yourself copying production
logic into a test, add a seam instead. It has already happened four times.

---

## 7. Known gaps

Traps that have already caused defects here are collected in
`docs/guides/PITFALLS.md` — read it before refactoring anything above.

Stated here rather than buried in a roadmap:

- **DLNA/UPnP is implemented but unproven against a real sender.** Its sockets
  and receiver lifecycle *are* unit-tested over loopback with an injected player
  (see `docs/TESTING.md` §7); what no test can reach is a real Windows/Android
  control point.
- **No device verification has happened.** Nothing here has been exercised
  against real macOS/iOS senders under the current architecture. Every protocol
  claim rests on code correctness and unit tests, not observed streams.
- **`MediaCodec` and vendor behaviour are out of reach.** Hardware codecs,
  a TV's multicast filtering, and interop with real Apple/Windows senders need a
  device. The adapters around them are injected ports, so the logic around them
  is tested; the hardware is not.
- Instrumented tests compile but do not run in CI.

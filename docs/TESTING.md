# Testing Guide

How to run the suite, what each tier covers, and — the part worth reading —
which seams exist so the interesting logic is testable without a device.

Every command below assumes you are at the repository root. Substitute
`firetv` for `googletv` anywhere a flavour appears.

---

## 1. Fast gates, slow gates

```bash
# Contracts every protocol depends on. Plain JVM — no SDK, no emulator.
./gradlew :core:test

# Everything except :app: all modules' unit tests + lint.
./gradlew :core:test :platform:testDebugUnitTest :airplay:testDebugUnitTest :app:lintGoogletvDebug

# Full: both flavours, the aggregate runner, lint, instrumented compile, R8 release.
./gradlew :core:test :platform:testDebugUnitTest :airplay:testDebugUnitTest \
          :app:testGoogletvDebugUnitTest :app:testFiretvDebugUnitTest \
          :test-runner:test :app:lintGoogletvDebug \
          :app:assembleGoogletvDebugAndroidTest :app:assembleGoogletvRelease
```

Note the module prefix: `:app:testGoogletvDebugUnitTest`, **not** a bare
`testGoogletvDebugUnitTest`. Root-level `./gradlew test` also works but builds
every module for every variant, which is the slow way to discover the same
failures.

**One class / one test:**

```bash
./gradlew :airplay:testDebugUnitTest --tests "tv.opentvcast.airplay.NowPlayingStateTest"
./gradlew :core:test --tests "tv.opentvcast.core.net.RefCountedMulticastLockTest"
```

Reports land per module: `core/build/reports/tests/test/`,
`airplay/build/reports/tests/testDebugUnitTest/`,
`app/build/reports/tests/testGoogletvDebugUnitTest/`, and CI uploads them as
artifacts.

---

## 2. The four tiers

| Tier | Task | Classes | Tests | Notes |
|---|---|---|---|---|
| `:core` | `:core:test` | 16 | 181 | Plain JUnit. Fastest meaningful gate. |
| `:platform` | `:platform:testDebugUnitTest` | 6 | 46 | Android helpers. Mostly plain JUnit — Robolectric only where a real `Context` is unavoidable. |
| `:airplay` | `:airplay:testDebugUnitTest` | 38 | 332 | **Must live here**: many tests drive `internal` members, which do not cross a module boundary. |
| `:dlna` | `:dlna:testDebugUnitTest` | 11 | 98 | UPnP protocol logic **plus** its sockets and receiver lifecycle over loopback (§7): SSDP, documents, SOAP, routing, GENA, HTTP server, receiver start/stop. |
| `:app` | `:app:testGoogletvDebugUnitTest` (and `…Firetv…`) | 9 | 78 | UI, service, settings, Android adapters. Both flavours run the same suite. |
| `:test-runner` | `:test-runner:test` | *aggregate* | *superset* | JVM-only aggregated build — see §3. |
| instrumented | `:app:assembleGoogletvDebugAndroidTest` | 1 | compile only | **Compile gate**, not execution — no emulator in CI. |

Module-native, deduplicated total: **736** (core 181, platform 46, airplay 332,
dlna 99, app 78). The rise from 703 is two features landing — portrait-panel rotation and
the video-URL-mode switch — including one view-level test (`StreamingScreenTest`, in `:app`
because it needs Robolectric) that covers wiring the pure `MirrorFit` tests cannot reach. The aggregate runner's number is a superset and deliberately not
part of that count.

Treat these counts as a map of coverage, not a target: they will drift with
every commit, and a number frozen in a document is how docs rot.

---

## 3. Why `:test-runner` exists — and what it cannot see

`:test-runner` compiles every module's production **and** test sources as one
Kotlin module, so tests can reach `internal` declarations in `:airplay`,
`:platform` and `:core`. It also resolves entirely from Maven Central, so it
runs where Google Maven is unreachable.

Two things follow, and both have bitten this project:

1. **It cannot detect a broken module boundary.** A reference from `:airplay` to
   an `:app`-only class compiles happily there and fails only under AGP. The
   structural check is the AGP build, never this task.
2. **It excludes the whole UI layer** plus anything needing a real Android
   runtime. Sources that need Robolectric (`SettingsRepositoryTest`,
   `BootReceiverTest`, `MainActivityTest`, `MdnsServiceTest`,
   `ServiceControllerTest`, `NetworkUtilsTest`) are listed in
   `test-runner/build.gradle.kts` explicitly, each with its reason. They run
   under `:app:test{Flavour}DebugUnitTest` instead.

If you add a test file that cannot compile inside the aggregate runner, add it
to that exclusion list **with a comment** and make sure the same test actually
runs somewhere else. Silent coverage loss is the failure mode here.

---

## 4. Seams worth knowing about

Several extractions exist only so that behaviour is testable — and several more
because a test previously carried a hand-copied replica that went stale.

| Seam | What it makes testable |
|---|---|
| `ConnectionMapping` | `ProtocolState` → `ActiveConnection` decisions |
| `OverlayDecision` | Full-screen overlay priority: PIN > now-playing > streaming > photo |
| `NowPlayingState` | The audio-only card; transitions return what to emit |
| `CastServiceContract` | The service action strings, shared by real class and test stub |
| `AppSettings.sanitizeDisplayName` | Display-name normalisation |
| `SpsParser` | H.264 resolution parsing without `MediaCodec` |
| `TimingHandler.millisToNtpTimestamp` | NTP epoch conversion shared by client and responder |
| `RebindableCollectorGroup` | "One bind round" of flow collectors |
| `RtpSequence` | 16-bit sequence arithmetic, wraparound included |
| `RtpDuplicateWindow` | Retransmission suppression |
| `RtpReorderBuffer` | Hold-until-contiguous, skip-a-stuck-hole policy |
| `RtspResponseWriter` / `RtspPairing` / `RtspVideoControl` / `RtspNowPlayingControl` | Each slice of the RTSP handler in isolation |

---

## 5. Techniques that keep these tests honest

- **Plain JUnit first; Robolectric only for the runtime edge.** Under
  Robolectric the sandbox classloader hides classes from JaCoCo *and* the class
  cannot run in the SDK-free aggregate runner — so putting logic that needs no
  Android behind `RobolectricTestRunner` silently costs you both. Split instead:
  `AndroidNetworkMonitorTest` (plain, virtual time, measured) and
  `AndroidNetworkMonitorSmokeTest` (Robolectric, real `ConnectivityManager`).
- **Extract the arithmetic, not the socket.** `AudioStreamServer` was 0% and
  looked untestable; its sequence arithmetic, duplicate window and reorder
  policy are now three plain classes at 97–100%, with the socket loop left as a
  thin caller.
- **Inject the mechanism, test the policy.** `ServerSocketPortAllocator` takes a
  `PortProbe`, so allocation policy runs against a deterministic fake while the
  shipped probe gets its own few tests binding real sockets.
- **Accumulate elapsed time instead of reading a clock.**
  `CoalescingNetworkMonitor` sums poll intervals rather than calling
  `System.nanoTime()`, so its timing is exact under virtual time. A monotonic
  clock reading stays real even there, which turns every timing assertion into a
  sleep with a tolerance.
- **Share `testScheduler`.** `UnconfinedTestDispatcher()` with no argument builds
  a *private* scheduler, so `runCurrent()` cannot pump work queued on it. Pass
  `testScheduler` or cancellation will never complete.
- **StateFlow replays its current value** to every new subscriber, so "the old
  collector stopped receiving" is not the right assertion after a rebind. Assert
  that each emission is delivered *exactly once*.

---

## 6. What is deliberately not covered

| Area | Why |
|---|---|
| `MdnsService` registration | Needs a real `NsdManager`; static initialisers reach JNI absent on a desktop JVM. |
| `VideoDecoder` buffer feeding | Needs `MediaCodec`; SPS/PPS parsing is split into `SpsParser` so the interesting part *is* tested. |
| `AirPlayVideoPlayer`, `AudioPlayer` playback | `MediaCodec` / `AudioTrack`; the encryption and framing halves are tested separately. |
| Layout rendering | No UI-test infrastructure in the JVM suite; extractable logic is extracted (see §4). |
| `MediaCodec` / `MediaPlayer` internals | Hardware and vendor codecs. The adapter is injected as a port, so the logic *around* it is tested; the codec itself is not. |
| Everything needing a TV | **No verification on real hardware has happened.** See `docs/spec/ACCEPTANCE_CRITERIA.md`; that milestone is explicitly not met. |

---

## 7. Testing network code without a device

Socket layers are the classic excuse for leaving a whole class untested. They do
not need a device, and they do not need Robolectric — TCP and UDP are plain
`java.net`:

- **Bind port 0, then ask the server what it got.** `UpnpHttpServer.boundPort`
  exists for this. It is also the production rule: advertise the granted port,
  never the requested one (FR-20/FR-33).
- **Drive the real thing over loopback.** `UpnpHttpServerTest` starts the server
  and speaks to it with `HttpURLConnection`; `DlnaReceiverTest` starts a whole
  receiver through its interface and fetches `/description.xml` from it.
- **Fake the socket when the network refuses you.** A multicast group join is
  routinely blocked in sandboxes, so `SsdpResponder` takes a socket factory and
  `SsdpResponderTest` drives the receive loop with a fake — the *policy* (answer
  the sender, not the group) is what needs pinning anyway.
- **Inject the framework edge.** `DlnaReceiver` takes a `playerFactory`, so its
  lifecycle runs with a fake player; `DlnaPlayerPort` keeps `MediaPlayer` out of
  the state machine entirely.

Two consequences worth remembering: a real socket test found a bug a pure parser
test could not (the server dropped every POST without answering), and tests must
pin `MX: 0` in an M-SEARCH or they inherit the responder's intentional reply
delay.

## 8. Measuring coverage

Coverage is opt-in — an init script, not part of the build:

```bash
./gradlew -I tools/coverage-init.gradle \
    :core:coverageReport :platform:coverageReport :airplay:coverageReport \
    :dlna:coverageReport :app:coverageReport
```

XML reports land in `<module>/build/reports/jacoco/coverageReport/`.

Last measured (2026-09-17): **51.9%** line coverage overall —
`:core` 95.1%, `:dlna` 81.6%, `:platform` 72.7%, `:airplay` 48.8%, `:app` 5.3%.

Two jumps are worth explaining, because both were measurement artefacts as much
as missing tests. `:dlna` went 62.2% → 81.6% by applying §7 to its sockets, and
`:platform` went 39.2% → 72.7% when the pure half of its monitor tests was moved
out from under `RobolectricTestRunner` — those tests were always running, JaCoCo
simply could not see them. `:airplay` rose by extracting
`AudioStreamServer`'s sequence handling (§5).

That number is not a target; it is a map. The uncovered half is concentrated in
`:app`'s UI layer and `:airplay`'s `MediaCodec`-facing classes, so the honest
reading is "the protocol logic and the DLNA sockets are covered; the UI and the
codec edges are not". Treat an *increase* in `:app` or `:airplay` as the signal
that a framework edge got a test, and a *decrease* in `:core` or `:dlna` as a
regression.

## 9. Instrumented tests

One class lives in `app/src/androidTest/kotlin/tv/opentvcast/`. It checks that
the Activity starts, that the app content is visible, and that the streaming
overlay stays hidden until a session begins.

```bash
# Compile gate used by CI (no device needed)
./gradlew :app:assembleGoogletvDebugAndroidTest

# Actual execution — needs a connected device or emulator
adb devices
./gradlew connectedGoogletvDebugAndroidTest
```

CI runs the compile gate on the `googletv` leg only; running every flavour's
instrumented APK build would double the CI time for the same information.

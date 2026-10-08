# Pitfalls

Traps that have already bitten this project, written down so they only bite
once. This is the durable part of the audit record; the day-by-day review log
it came from has been retired.

Everything here came from a real defect, a real red test, or a real wrong
assertion — not from style preference.

---

## 1. Never hand-copy production logic into a test

It has happened **five times**. Each copy passed when written and silently went
stale afterwards: the test kept passing while the production behaviour changed
underneath it, which is worse than having no test — it is a test that lies.

The first sign is a comment in the test admitting it: *"mirrors the exact
decision lambda in X"*, *"replicates the mapping"*. If you write a sentence like
that, stop and extract a seam instead.

The seams that exist because of this (all shared by production and tests):

| Seam | What it decides |
|---|---|
| `ConnectionMapping` | `ProtocolState` → `ActiveConnection`, and `isRunning` |
| `OverlayDecision` | Full-screen overlay priority |
| `NowPlayingState` | Audio-only "now playing" card; transitions return what to emit |
| `CastServiceContract` | Service action strings (real class **and** test stub re-export it) |
| `AppSettings.sanitizeDisplayName` | Display-name normalisation |
| `SpsParser` | H.264 resolution parsing, without `MediaCodec` |
| `TimingHandler.millisToNtpTimestamp` | NTP epoch conversion (client and responder) |
| `SsdpSearchMatcher` | What answers an `M-SEARCH`, and when |
| `DlnaControlDispatcher` | UPnP transport state machine |

A copy can also be a *constant*: the test-runner stub used to carry its own
literal copy of the service action strings. Both sides now read one object.

---

## 2. Testing traps, each found by a failing test

- **StateFlow replays its current value to every new subscriber.** After a
  rebind, "the old collector received nothing" is the wrong assertion — the new
  collector legitimately receives the replayed value. Assert that each emission
  is delivered **exactly once** (the real defect was duplicate delivery).
- **`UnconfinedTestDispatcher()` with no argument builds a *private* scheduler.**
  `runCurrent()` cannot pump work queued on it, so cancellation never completes.
  Pass `testScheduler`.
- **Inject time and IDs.** `CoalescingNetworkMonitor` sums poll intervals and
  `GenaSubscriptionManager` takes a `clock` and an `idSource`, so timing and
  expiry are exact. A wall-clock test with a 1970 timestamp expires nothing and
  proves nothing.
- **An assertion can be wrong about the *system*, not just the code.** One draft
  asserted `CONNECTED` outranks the now-playing card; the receiver actually emits
  the card while `CONNECTED` in the audio-takeover case. The red test corrected
  the author's mental model — that is what red is for.
- **Append tests *inside* the class.** Script-appending to the end of a file puts
  them after the closing brace; JUnit then reports
  `XxxKt.initializationError`, which says nothing about the cause.
- **Do not string-replace inside an XML fixture.** Turning `s:` into `soapenv:`
  also corrupts `xmlns:s=` into `xmlnsoapenv:s=`. Write the second fixture out.
- **A pure function's tests do not cover its use.** `MirrorFit` had eleven tests and the
  view could still have passed it the wrong angle, or ignored its answer: the wiring is a
  separate fact from the arithmetic. One view-level test closes that gap — Robolectric,
  measure and lay the view out by hand, and call the layout decision directly instead of
  waiting out its timer (`internal` rather than `private`, with the reason written next to
  it). Three mutations confirmed it has teeth: passing the wrong angle, not applying the
  rotation, and reverting `clipChildren` were each caught.
- **Geometry wants a property test, not only examples.** Truncating `1170 × (1920 / 2532)`
  gives `1919.999 → 1919`, a hairline seam on the edge that is supposed to touch. Hand-picked
  examples would have missed it; "the axis `min()` chose is filled exactly", asserted across
  the aspect ratios real phones produce, caught it. Assert the invariant, not just the cases.
- **Do not reach for Robolectric unless the runtime is genuinely needed.** Its
  sandbox classloader hides classes from JaCoCo *and* the class cannot run in the
  SDK-free aggregate runner. A whole test class measured 0% for this reason while
  passing: the tests were running, the coverage tool could not see them. Split the
  pure part out.
- **`assertEquals(message, expected, actual)` takes the message first.**
  Passing a string where the expected value goes produces an assertion failure
  that reads like a real defect. It cost a debugging round here; `assertNull` and
  friends take the message first too.
- **A test that pins a production constant is doing its job.** Extracting
  `RtpDuplicateWindow` with a default of 512 while production used 1024 looked
  harmless and would have shipped a behaviour change; the test now pins 1024 so
  the next such change has to be argued for.

---

## 3. Defect patterns to look for in review

| Pattern | Why it hides |
|---|---|
| Recursion while skipping untrusted input | A flood of blank lines overflowed the RTSP session thread's stack — a remote DoS with no exception you can catch. Iterate, don't recurse. |
| Classifying a failure by a nullable field | `audioSocket != null` was wrong in both directions: a bind failure looked like normal shutdown (receiver up, permanently deaf, no log), and every normal shutdown logged an ERROR. Classify on the **local** socket's `isClosed`. |
| Per-connection state that outlives the connection | The previous sender's remote address leaked into the next session. Clear it in one place (`resetConnectionState`). |
| Uncancelled coroutine scopes | `ReceiverRegistry`'s `SupervisorJob` scope lived for the process. Make long-lived owners `AutoCloseable`. |
| Empty `catch` blocks | Five of them vanished exceptions entirely. Catch the specific expected exception (e.g. `SocketTimeoutException`) and log the rest. |
| Rotating the display from an app | **An application cannot rotate an Android TV's display.** `requestedOrientation = PORTRAIT` is accepted by the framework and changes nothing (measured: `mRotation=0`, window `land television`); only the system-wide `user_rotation` setting moves it, and that needs `WRITE_SETTINGS` and outlives the app. Do orientation-shaped work inside your own window. `user_rotation` is the probe that tells "the panel can't rotate" apart from "an app may not ask". |
| Removing `clipChildren = false` while tidying | A rotated view's layout box must be the **transpose** of what it displays, so the box can be taller than its window; the overhang is empty space and clipping it cuts the picture. Nothing else in the build fails if that attribute disappears — pin it with a test. |
| Advertising the port you *asked* for | The allocator may substitute. Publish the granted port in mDNS, in SSDP `LOCATION` and in every document URL. |
| Collectors started per bind without cancelling the last round | Every reconnect stacked another set. Use `RebindableCollectorGroup`. |

---

## 4. Module boundaries

- **`:core` must stay plain Kotlin/JVM.** No `android.*`, no Timber (it ships as
  an AAR, so there is no JAR to depend on). That is what makes its contracts
  testable without Robolectric.
- **`internal` does not cross a module boundary.** A test for `:airplay`'s
  `internal` members must live in `airplay/src/test`, not `:app`.
- **`:test-runner` cannot see a broken boundary.** It merges every module into
  one compilation unit, so a reference from `:airplay` to an `:app`-only class
  compiles there and fails only under AGP. It is a fast gate, not a structural
  check; the structural check is the AGP build. It also excludes the whole UI
  layer, so UI compile errors never show up there either.
- **Adding a test that cannot compile in the aggregate runner** means adding it
  to that runner's exclusion list *with a reason*, and making sure it runs
  somewhere else.

---

## 5. Things nobody should delete

The GPLv3 obligation is real, not decorative. Do not remove:

- `LICENSE` (GPLv3 full text)
- `NOTICE` — all ten sections: playfair (GPL), PhairPlay (Apache-2.0), ALAC,
  dd-plist, Bouncy Castle, Timber, AndroidX, protocol references, trademarks,
  and the redistribution clause
- Copyright headers in `playfair/` and `alac/`
- The RPiPlay provenance line in `airplay/src/main/cpp/CMakeLists.txt`
- The acknowledgements section in `README.md`

Why GPLv3 rather than GPLv2/LGPL/AGPL is explained in the README's Licence
section. Short version: playfair is "GNU GPL" with no version specified, so
GPLv2 §9 forces a version choice, and only GPLv3 is compatible with the
Apache-2.0 code we inherit.

---

## 6. What is still unknown

**Nothing in this repository has been exercised against real hardware.** No
macOS/iOS sender, no Windows/Android DLNA control point.

That is narrower than it sounds, and worth stating precisely, because "needs a
device" is the excuse that keeps whole layers untested:

- **Sockets are covered.** HTTP is driven over real loopback; SSDP's policy is
  driven through a fake socket; the receiver lifecycle runs against a fake
  environment and an injected player (`docs/TESTING.md` §7).
- **What genuinely needs hardware**: hardware codecs (`MediaCodec`,
  `MediaPlayer` internals), a TV's multicast filtering, and interop with a real
  sender. No amount of JVM testing substitutes for those.

Say which of the two you mean when someone asks whether it works.

# Changelog

All notable changes to **opentvcast** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

opentvcast starts its own release history below. It is derived from
[PhairPlay](https://github.com/mazer666/PhairPlay) (Apache-2.0) and relicensed
under GPLv3 — `LICENSE` and `NOTICE` carry the full provenance and the reason
GPLv3 is required. PhairPlay's own releases belong to that project and are not
reproduced here.

## [Unreleased]

### Added

- **Portrait TV** (AirPlay mirroring): for a panel that has been rotated 90° by hand. The
  mirrored picture is rotated 90° or 270° and refitted so vertical video fills the panel's
  long edge with only narrow side bars, instead of sitting pillarboxed in the middle of a
  landscape frame. The picture is carried by a `TextureView` while the panel is rotated —
  a `SurfaceView` composites its own surface and silently ignores `rotation` (measured on
  real hardware), so the ordinary landscape case keeps the SurfaceView and only the rotated
  one pays for the extra GPU copy. Applied live, needs no permission, and is undone when the
  app exits — the TV's own settings are never touched. Android TV refuses an application's
  display-orientation request (measured), so the rotation happens inside the app's window.
  The framing arithmetic and the view/rotation wiring are unit-tested; the pixels on a
  rotated panel still need real hardware.

- **Video URL mode switch** (AirPlay): the receiver can be made mirroring-only, so a
  sender's URL is never fetched and played by the TV itself. `POST /play` is refused
  with 403, an in-flight URL playback stops at once, and the other transport verbs
  keep answering so the session survives. Applied live — no service restart. Default
  is on, so the capability is opt-out. Verified on an Android 14 TV emulator.

- **DLNA / UPnP AV MediaRenderer** (`:dlna`): SSDP discovery and announcements, device
  description and SCPDs served on an allocated port, `AVTransport` / `RenderingControl` /
  `ConnectionManager` over SOAP, and GENA eventing with `LastChange`. Enabled by the
  existing DLNA setting. **Not verified against a real control point.**
- **5+1 module layout**: `:core` (contracts, plain Kotlin/JVM), `:platform`
  (Android-only helpers), `:airplay`, `:dlna`, `:app`, and `:test-runner` — a
  JVM suite that compiles production and test sources as one unit so tests can
  reach `internal` declarations across modules.
- **Network-compatibility facilities in `:core`**: `RefCountedMulticastLock`,
  `ServerSocketPortAllocator`, `DefaultInterfaceSelector`,
  `CoalescingNetworkMonitor` — the four contracts protocol implementations need
  to behave on real TV firmware instead of assuming one device's behaviour.
- **Shared extractions** so production and tests cannot drift apart:
  `ConnectionMapping`, `OverlayDecision`, `AppSettings.sanitizeDisplayName`,
  `CastServiceContract`, `TimingHandler.millisToNtpTimestamp`, and
  `NowPlayingState` (whose transitions return what must be emitted, so the
  now-playing card cannot go stale when a mutation skips a recompute).
- CI: NDK + CMake setup, per-module unit tests, and an `androidTest` compile
  gate — instrumented sources used to rot silently because they were never
  compiled by any pipeline.

### Changed

- Relicensed to GPLv3 with a full `NOTICE` (README's Licence section explains
  why GPLv3 and not GPLv2, LGPL or AGPL).
- Identity unified on `opentvcast`: package `tv.opentvcast`, Gradle property
  prefix `opentvcast.*`, environment prefix `OPENTVCAST_*`, persisted stores
  `opentvcast_settings` / `opentvcast_prefs` / `opentvcast_pairings`, and the
  device-identity key `opentvcast_device_uuid`.
- `RtspHandler` split into four single-responsibility collaborators
  (`RtspResponseWriter`, `RtspPairing`, `RtspVideoControl`,
  `RtspNowPlayingControl`).
- MainActivity and HomeFragment now collect service state through one
  `RebindableCollectorGroup` per bind round, replacing a pattern that stacked
  competing collectors across every reconnect.

### Fixed

- **Media at a URL the sender supplies is now fetched the way the sender intended.** Two
  silent faults stopped AirPlay URL playback dead. The sender's `Referer`, which travels in
  the `/play` body and is required by a web page's CDN while a native app's in-app link
  usually does not care, was parsed and then thrown away. And **cleartext HTTP was blocked**:
  with no `networkSecurityConfig`, Android refuses every `http://` request the app makes —
  which is the shape of Apple's own `/play` example URL and of what a DLNA control point
  sends in `SetAVTransportURI`. Both failures surfaced as a black screen and one line in a
  log nobody reads. **The same defect would have stopped DLNA playback from working against
  any real control point.** URL playback now also stops a running mirror first (one Surface,
  one writer) and retries the first Surface for about two seconds, since a `MediaPlayer` that
  prepares without a surface plays sound and shows nothing.
- **Portrait TV rotates the picture only on a view that can carry the rotation.** The first
  implementation set `rotation` on a `SurfaceView`, which accepts the property and leaves the
  picture where it was — a real TV showed a portrait stream with its long edge along the
  panel's short edge. The picture is now carried by a `TextureView` whenever the panel is
  rotated, and by the (zero-copy) `SurfaceView` otherwise.
- **A URL video is framed by geometry, not by what `MediaPlayer` claims its size is.** While the
  panel is rotated and the size is unknown — which is every URL playback — the surface is laid out
  as the container *transposed*, so the rotation turns a complete picture into place instead of
  producing a zoomed, clipped one. `MediaPlayer`'s own size report was tried as an input and
  measured wrong (a 16:9 stream announced as 540×540); it already letterboxes correctly inside the
  surface it is given, so it is left to do that.
- Remote DoS: a flood of blank lines in the RTSP request stream recursed and
  overflowed the session thread's stack. The skip is now iterative.
- Audio crashes were misclassified: a UDP bind failure was logged as expected
  shutdown noise — the receiver stayed up, permanently deaf, with no error in
  the log — while every normal shutdown was reported as an unexpected error.
- `RtspHandler` leaked the previous sender's remote address into the next
  session, and sharing its stream-type set across threads was unsafe.
- `ReceiverRegistry` never cancelled its coroutine scope; it is `AutoCloseable`
  now.
- Five `catch` blocks swallowed exceptions with no logging at all.
- The port actually granted to the RTSP socket, rather than the default
  constant, now drives binding and logging.

### Removed

- **Google Cast sender-app-id plumbing** (gradle property, helper, and its
  guide) — nothing read it, and v1 does not ship Cast. The last build-level
  trace of the feature (`play-services-cast-tv`) went with it.
- The `CAST_APP_ID` setup guide, dead resources, and unused version-catalogue
  entries.

<!--
## [X.Y.Z] - YYYY-MM-DD

### Added
### Changed
### Fixed
### Removed
-->

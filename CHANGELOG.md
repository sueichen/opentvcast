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

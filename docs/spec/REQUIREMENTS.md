# opentvcast – Requirements

Version: 3.0
Status: Active
Date: 2026-09-16
Scope: v1

---

## 0. Scope

opentvcast v1 ships exactly **two** protocols:

| Protocol | Status |
|---|---|
| **AirPlay 2** — screen mirroring, audio, video URL, photos, DACP remote | Implemented |
| **DLNA / UPnP AV MediaRenderer** | Implemented, not verified on hardware (§1.3) |

Mirror display orientation is **designed, not implemented** (§1.8, FR-58/FR-59).
The video-URL-mode switch is **implemented** (§1.8, FR-60/FR-61).

**Miracast and Google Cast are not in v1, and this is a decision rather than a
backlog item:**

- **Google Cast** is the only feature that requires Google Play Services. Excluding it
  keeps the Google TV and Fire TV flavors dependency-identical, so the Fire TV build
  never has to degrade around a missing GMS.
- **Miracast** requires Wi-Fi Direct, whose availability and behaviour vary enough
  across TV hardware that supporting it well costs more than it returns. Most senders
  offer AirPlay or Cast when both are available.

**DLNA takes the second slot** because it needs neither: no GMS, no Wi-Fi Direct, no
vendor-specific permissions, and it covers the Windows and Android senders Miracast
was there to serve.

Requirements are numbered `FR-nn` (functional) and `NFR-nn` (non-functional). Anything
carried over from upstream PhairPlay is noted where the requirement changed meaning.

---

## 1. Functional Requirements

### 1.1 Service Discovery & Advertising

- FR-01: The app MUST advertise an AirPlay 2 receiver via mDNS (`_airplay._tcp`, `_raop._tcp`).
- FR-02: The app MUST advertise a DLNA MediaRenderer via SSDP (`ssdp:alive` on UDP 1900).
- FR-03: Each protocol MUST be independently enable/disable-able in Settings.
- FR-04: The device name shown in sender pickers MUST be configurable in Settings, defaulting to the Android device name.
- FR-05: Advertising MUST begin within 2 seconds of the service starting.
- FR-06: Advertising MUST stop within 5 seconds of the protocol being disabled or the service stopping.

#### 1.1.1 Multicast reception

- FR-07: While any protocol that relies on multicast discovery is running, the app MUST hold an Android **multicast lock**. Declaring `CHANGE_WIFI_MULTICAST_STATE` in the manifest is not sufficient: without the lock, inbound multicast is filtered on many devices and discovery fails with no error.
- FR-08: The lock MUST be **reference-counted by owner tag**, and released only when the last holder releases. Two discovery consumers do not share a lifetime — mDNS advertising runs for the whole receiver lifetime, DACP discovery only during a session — so a first-release-wins scheme would leave one of them deaf mid-session.
- FR-09: Acquiring twice under the same owner tag MUST count once, so a receiver that restarts without a matching release cannot leak the lock for the life of the process.
- FR-10: A failure to obtain the lock MUST be logged as a warning and MUST NOT prevent the protocol from starting. Wired-only devices may have no Wi-Fi service at all.

#### 1.1.2 Address selection

- FR-11: The address advertised to senders MUST be chosen from usable interfaces. Unusable means: down, loopback, virtual (containers, and the interfaces Android exposes for them), or point-to-point (VPN tunnels).
- FR-12: Among usable addresses the preference order MUST be: Ethernet over Wi-Fi; site-local over global; IPv4 over IPv6. Rationale, in the same order: a wired TV does not roam; a LAN sender reaches a private address directly while a public one invites a NAT hairpin; and a large installed base of senders still fails to route `.local` over IPv6.
- FR-13: `allAddresses` MUST expose every usable address, for dual-stack advertisement.

#### 1.1.3 Network change handling

- FR-14: The app MUST detect a change in network identity (new IP, Wi-Fi to Ethernet, interface up/down) and re-advertise. Without this, mDNS keeps advertising a stale address: the sender still sees the device in its cache and every connection attempt times out, which only an app restart clears.
- FR-15: Changes MUST be **coalesced**. A change is reported only once the new identity has been observed continuously for a settling period, because bringing an interface up produces a burst of intermediate states and re-advertising on each would tear the receiver down and up several times.
- FR-16: A change that returns to the last advertised identity within the settling period MUST NOT be reported at all (an interface that flapped and recovered).
- FR-17: Starting the monitor MUST NOT report the current identity: starting a receiver is not a change, and reporting it would re-advertise the address that had just been advertised.
- FR-18: A failure in the re-advertise callback MUST NOT stop future network monitoring.

#### 1.1.4 Port allocation

- FR-19: A preferred port MUST be treated as a **request**. When it is unavailable, the allocator MUST substitute a dynamic port rather than fail.
- FR-20: The value advertised in mDNS TXT / SSDP `LOCATION` MUST be the port that was **actually granted**, never the port that was requested.
- FR-21: Ports MUST be reserved per owner; releasing a port MUST be permitted only by the owner that reserved it, so one protocol shutting down cannot free a port a sibling is still using.
- FR-22: An in-process reservation MUST take precedence over probing, since the reserving owner may not have bound the port yet.
- FR-23: When neither the preferred nor any dynamic port can be bound, the app MUST raise a typed failure carrying the preferred port, rather than letting a raw socket exception escape.

### 1.2 AirPlay 2 Receiver

- FR-24: Accept screen mirroring from **macOS 12+** and **iOS/iPadOS 16+** via RTSP on TCP port 7000.
- FR-25: Complete the RTSP handshake (OPTIONS → SETUP → ANNOUNCE → RECORD) without errors.
- FR-26: Reject a second concurrent AirPlay connection, and surface it to the UI as a preemption prompt rather than a bare failure. (Upstream answered `503` with no body, leaving the sender to time out silently.)
- FR-27: Auto-reconnect and resume advertising after connection loss.
- FR-28: Accept **audio-only** AirPlay streams (music, podcasts) and play them through the TV speakers without opening the streaming screen.
- FR-29: Accept **photo** transfers via the `/photo` endpoint, display them full-screen, and return to the home screen when the sender clears or disconnects. Requires the `Photo` feature bit in the `features` TXT record.
- FR-30: Support **legacy SRP PIN pairing** and **HomeKit-style Ed25519/X25519 pairing**, with lockout after repeated failed attempts.
- FR-31: Support **DACP reverse remote**, so the TV remote drives the sender's playback.

#### AirPlay 2 codec requirements

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video | H.264 AVC, up to High Profile Level 5.2 | **Required** | Hardware `MediaCodec` decode |
| Video | H.265 HEVC | Deferred | Hardware-gated; v2 behind a capability query |
| Audio (mirroring) | AAC-ELD, AAC-LC | **Required** | Low-latency mirroring audio |
| Audio (mirroring) | ALAC | **Required** | macOS system audio |
| Audio (RAOP / music) | ALAC 16-bit | **Required** | Native `libalac` |
| Audio (buffered) | AirPlay 2 stream type 103 | Accepted, not played | Instrumented; see §3 |
| Audio (surround) | Dolby Atmos (E-AC3 + JOC), AC-3 | Deferred | v2, hardware-gated |
| Photos | JPEG, PNG | **Required** | `BitmapFactory`; no video decoder involved |
| Max resolution | 1080p @ 60fps | **Required** | Baseline |
| Max resolution | 4K UHD, HDR10, Dolby Vision | Deferred | v2 |
| Session-key exchange | FairPlay `/fp-setup` v2/v3 + legacy `rsaaeskey` | **Required** | Native `libplayfair`; see §3 |
| Content DRM | FairPlay Streaming (FPS) | **Not in v1, not planned** | See §3 |

### 1.3 DLNA / UPnP AV MediaRenderer

**Status: implemented, not verified on hardware.** The `:dlna` module implements the
requirements below; the settings toggle and the status card are wired to it. No part of
it has been exercised against a real control point, so treat FR-37 as unproven.

- FR-32: Respond to SSDP `M-SEARCH` and send `ssdp:alive` / `ssdp:byebye` notifications on UDP 1900.
- FR-33: Serve a device description document and per-service SCPD over a dynamically allocated TCP port, and publish the actual granted port in the SSDP `LOCATION` header (FR-20).
- FR-34: Implement the `AVTransport`, `RenderingControl`, and `ConnectionManager` control actions via SOAP.
- FR-35: Support GENA event subscription, including the `LastChange` state variable.
- FR-36: Implement a transport state machine (stop / play / pause / seek) driving a URL-based media player.
- FR-37: Support push from Windows and Android DLNA senders.
- FR-38: Show DLNA connection state on the home screen status card.

#### DLNA codec requirements

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video | H.264 AVC | **Required** | Hardware decode; sender-agnostic |
| Video | MPEG-4 Part 2, MPEG-2 | Optional | Depends on device decoders |
| Audio | AAC-LC, MP3, LPCM/WAV | **Required** | |
| Audio | FLAC, AC-3 | Optional | Hardware-gated |
| Container | MP4, MPEG-TS, AVI | **Required** | |
| Container (adaptive) | HLS | Deferred | v2 |
| Content DRM | — | **Not applicable** | DLNA has no mandatory content DRM layer |

### 1.4 Service Control

- FR-39: The user MUST be able to **start**, **stop**, and **restart** the receivers from the app UI and from the notification.
- FR-40: The receiver MUST run as an Android **ForegroundService**, so it survives screensaver and backgrounding.
- FR-41: A **persistent notification** MUST be shown while the service runs, with Stop and Restart actions.
- FR-42: Service state (running / stopped / restarting / error) MUST be visible on the home screen at all times.
- FR-43: Stopping MUST release every network port, close every connection, stop advertising, and release the multicast lock.
- FR-44: Restarting MUST be a clean stop followed by a clean start within 3 seconds.
- FR-45: Swiping the app from recents MUST stop the receivers, so the service does not survive as a zombie that advertises invisibly.

### 1.5 Settings

- FR-46: A dedicated **Settings screen** MUST be reachable from the home screen.
- FR-47: Settings MUST persist across restarts (Android DataStore).
- FR-48: The following MUST be configurable:

| Setting | Default | Description |
|---|---|---|
| Display name | Android device name | Name shown in sender pickers |
| AirPlay enabled | ON | Enable/disable the AirPlay receiver |
| DLNA enabled | ON | Enable/disable the DLNA receiver |
| AirPlay PIN auth | OFF | Require a PIN before accepting an AirPlay session |
| Start on boot | OFF | Auto-start the service on device boot |
| Show debug overlay | OFF | FPS / queue / loss HUD over the stream |
| Mirror resolution | Device default | Requested mirroring resolution |
| Mirror audio | ON | Receive mirroring audio |

| Video URL mode | ON | Allow the sender to hand the TV a URL; off = mirror only |

**Portrait TV** and **Rotate direction** are designed but not implemented
(§1.8, FR-58/FR-59) and are therefore not in the table above, which states what
the app configures today.

### 1.6 Media playback

- FR-49: Decode video with hardware `MediaCodec` only. There is no software fallback; when no hardware decoder is available the stream is rejected gracefully rather than played badly.
- FR-50: Display video full-screen, preserving aspect ratio. Portrait streams (phones) MUST be rendered portrait rather than stretched to 16:9.
- FR-51: Play audio in sync with video, with A/V drift ≤ 40 ms.
- FR-52: For audio-only streams, play through the default output without opening the streaming screen.
- FR-53: Show now-playing metadata (title, artist, album, artwork) when the sender provides it.

### 1.7 Internationalization

- FR-54: Every user-visible string MUST live in Android resource files, never hardcoded in Kotlin or layouts.
- FR-55: v1 ships **English**. German and French exist but are incomplete.
- FR-56: Adding a language MUST require no Kotlin or layout changes.
- FR-57: Date, time, and number formats MUST follow the device locale.

### 1.8 Display orientation and playback source

**Status: mirror orientation is designed, not implemented; the playback-source
switch is implemented.** Both have design documents carrying the measured numbers,
the rejected alternatives and their validation gates.

- [`FEATURE-mirror-orientation.md`](FEATURE-mirror-orientation.md) — portrait-mounted
  panels, rotation-aware framing
- [`FEATURE-video-url-mode.md`](FEATURE-video-url-mode.md) — mirroring-only mode

- FR-58: The user MUST be able to declare the panel's physical orientation —
  landscape (default) or rotated 90°. In the rotated case the mirroring picture
  MUST be rotated so it reads upright, and MUST NOT be cropped to remove the bars.
- FR-59: The rotation direction MUST be selectable (clockwise or
  counter-clockwise), since which is correct depends on how the panel was turned.
- FR-60: **Implemented.** The user can disable AirPlay **video URL playback**, so
  the receiver mirrors only and never fetches media itself. Default: **enabled**
  (today's behaviour). The switch is applied to a running receiver without a
  restart, and turning it off stops an in-flight URL playback.
- FR-61: **Implemented.** While URL playback is disabled, `POST /play` is refused
  with 403 and never reaches the player, while mirroring, mirroring audio, photos
  and DACP continue to work unchanged. The transport verbs keep answering, so a
  refusal reads as one feature switched off rather than a dead session. Verified on
  an Android 14 TV emulator: 200 with the switch on, 403 with it off.

---

## 2. Non-Functional Requirements

### 2.1 Performance

- NFR-01: Video latency ≤ 100 ms on local 5 GHz Wi-Fi.
- NFR-02: Sustained frame rate ≥ 25 fps.
- NFR-03: Peak RAM ≤ 150 MB with one active stream.
- NFR-04: Average CPU ≤ 30 % on a mid-range TV SoC.

### 2.2 UI / UX

- NFR-05: Dark, card-based layout with large focus indicators, following Android TV design guidance.
- NFR-06: Every interactive element MUST be reachable by D-pad.
- NFR-07: Minimum touch and focus target: 48 dp.
- NFR-08: Minimum text size: 18 sp body, 32 sp titles.
- NFR-09: Contrast sufficient for viewing from 3 m.
- NFR-10: The status card MUST reflect a protocol state change within 1 second. A `CONNECTING` state MUST be visually distinct from `CONNECTED` — showing a negotiating sender as connected claims a stream that does not exist.

### 2.3 Privacy & Security

- NFR-11: No ads, no analytics, no telemetry.
- NFR-12: All network input validated before use.
- NFR-13: No hardcoded secrets.
- NFR-14: Minimal permissions. The app declares no permission it does not exercise: `CHANGE_WIFI_MULTICAST_STATE` in particular is only justified because FR-07 acquires the lock.
- NFR-15: Protocol modules MUST NOT reach for `WifiManager`, `ConnectivityManager`, or socket plumbing directly; everything arrives through `ReceiverEnvironment`. This is enforced at compile time by keeping `:core` free of Android APIs, not by convention.

### 2.4 Compatibility

- NFR-16: Google TV / Android TV: Android 10+ (API 29+), ARMv8.
- NFR-17: Fire TV: Android 7.1+ (API 25+), ARMv7 and ARMv8.
- NFR-18: AirPlay senders: macOS 12+, iOS/iPadOS 16+.
- NFR-19: DLNA senders: Windows 10+ and Android, via any conformant UPnP control point.

### 2.5 Code quality

- NFR-20: No source file exceeds 550 lines (soft limit 400).
- NFR-21: Every public method has at least one unit test.
- NFR-22: Every class carries a KDoc header explaining why it exists, not just what it does.

---

## 3. Explicitly excluded, with reasons

| Feature | Reason | Status |
|---|---|---|
| Google Cast | Only feature needing Play Services; breaks flavor symmetry with Fire TV | Not planned for v1 |
| Miracast / WFD | Wi-Fi Direct support varies too much across TV hardware | Not planned for v1 |
| FairPlay **Streaming** content DRM | Needs an Apple-supplied Android KSM binary that does not exist | Not planned; see below |
| FairPlay **session-key** exchange | — | **Implemented**; see below |
| HDCP link protection | Only relevant to Miracast | Not applicable |
| Widevine / PlayReady | Only relevant to Cast | Not applicable |
| H.265 / HEVC | Hardware-gated | v2, behind a capability query |
| 4K, HDR10, Dolby Vision | Hardware-dependent | v2 |
| Dolby Atmos / AC-3 | Hardware-dependent | v2 |
| AirPlay 2 buffered audio (type 103) | Accepted and instrumented, not played back | v2 |
| AirPlay audio grouping (multi-room) | Needs the full AirPlay 2 multi-room stack | v3 |
| HomeKit / HAP pairing | Separate protocol with its own controller model | v2 research |
| SMB / file browsing | Not a casting feature | Not planned |
| Cloud / remote streaming | Local network only, by design | Not planned |
| Recording streams to file | Privacy | Not planned |

### FairPlay — the two mechanisms, kept apart

Earlier revisions of this document conflated these and concluded that FairPlay was
"not implemented". Only one of them is out of scope.

**(a) AirPlay session-key exchange (`/fp-setup`) — IMPLEMENTED.**
Every AirPlay 2 mirroring session is gated behind a per-session key exchange. Without
it the receiver cannot decrypt the video stream at all, so this is not optional. It is
implemented through the native `libplayfair` (see `NOTICE` §1).

This is also the sole reason opentvcast is licensed GPLv3 rather than a permissive
licence: `playfair` is GPL, every publicly available FairPlay implementation is, and
the project cannot relicense a component it does not own.

**(b) FairPlay Streaming (FPS) content DRM — NOT IMPLEMENTED, not planned.**
FPS is Apple's content DRM for HLS, used by Apple TV+, iTunes purchases, and other
Apple-licensed platforms. It is unrelated to (a).

The path to FPS, for the record:

1. Register as an Apple Developer (paid membership required).
2. Apply to Apple for an FPS deployment package — a Key Security Module (KSM). Apple
   approves or denies by use case.
3. Implement the FPS key exchange against a licence server using the supplied
   binary-only KSM library.
4. The KSM is platform-specific. Apple ships it for macOS, iOS, and tvOS. It has never
   shipped an Android-compatible KSM.

Conclusion:

- FPS cannot be implemented on Android without an Android KSM binary that Apple has
  never released.
- Even if one existed, its licence terms would be incompatible with GPLv3
  redistribution.
- **FPS is therefore out of scope and off the roadmap** unless Apple changes policy.
  FairPlay-protected *content* will not play. FairPlay-session-key *mirroring* does.

---

## 4. Target devices and senders

### Receivers

| Platform | Min OS | API | Arch |
|---|---|---|---|
| Google TV / Android TV | Android 10 | 29 | ARMv8 |
| Amazon Fire TV Stick 4K | Android 7.1 | 25 | ARMv8 |
| Amazon Fire TV Stick (3rd gen) | Android 7.1 | 25 | ARMv7 / ARMv8 |

### Senders

| Protocol | Platform | Version | Notes |
|---|---|---|---|
| AirPlay 2 | macOS | 12 (Monterey)+ | Screen mirroring, audio, photos |
| AirPlay 2 | iOS / iPadOS | 16+ | Screen mirroring, audio, photos |
| DLNA | Windows | 10+ | Push to the renderer |
| DLNA | Android | Any conformant control point | Push to the renderer |

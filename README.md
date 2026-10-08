# opentvcast

**English** | [简体中文](README.zh-CN.md)

opentvcast is a free, open-source, ad-free casting **receiver** for Android TV and
Fire TV. It lets your macOS or iOS/iPadOS device mirror its screen and audio
directly to your TV — no Apple TV, no dongle, no account.

```
 macOS (Monterey+)            Android TV / Fire TV
 iOS / iPadOS (16+)           ┌──────────────────────┐
 ┌────────────────┐  AirPlay  │                      │
 │  [Your Screen] │ ────────► │  [Your TV Screen]    │
 │                │           │                      │
 └────────────────┘           └──────────────────────┘
      Click AirPlay →              opentvcast
      Select your TV →             (this app)
      Done. ✓
```

---

## Scope — what v1 is

opentvcast deliberately ships **two** protocols:

| Protocol | Status |
|---|---|
| **AirPlay 2** (screen mirroring, audio, video, photos, DACP remote) | Implemented |
| **DLNA / UPnP AV MediaRenderer** | Implemented — see below |

**Miracast and Google Cast are not part of v1.** This is a scope decision, not an
oversight:

- **Google Cast** would be the only feature pulling in Google Play Services. Cutting
  it keeps both flavors dependency-identical and lets the View-based UI stay the same
  on Fire TV, where Play Services do not exist at all.
- **Miracast** needs Wi-Fi Direct (`WifiP2pManager`) plus WFD/MPEG-TS decoding. On TV
  hardware whose Wi-Fi Direct support varies by vendor, it is the single largest
  source of "works on my device" bug reports for comparatively little value, since
  most senders prefer AirPlay or Cast when both are offered.

Both remain candidates for v2. The protocol layer was built so that adding one is a
new module plus a single `register()` call, not an edit to every screen — see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

### DLNA status

The `:dlna` module implements a UPnP AV MediaRenderer: SSDP discovery, a device
description and SCPDs on an allocated port, `AVTransport` / `RenderingControl` /
`ConnectionManager` over SOAP, and GENA eventing. It has **not been exercised against
a real Windows or Android control point** — the protocol logic is unit-tested, the
socket loops and the player are not (see `docs/ARCHITECTURE.md` → Known gaps).

---

## Features

### AirPlay 2 (implemented)

- Screen mirroring from macOS 12+ and iOS/iPadOS 16+ — H.264 decode
- FairPlay session-key exchange (`fp-setup` v2/v3, and legacy `rsaaeskey`) via the
  native `libplayfair`
- HomeKit-style pairing (Ed25519/X25519) **and** legacy SRP PIN pairing, with
  access-control lockout after repeated failures
- Mirroring audio: AAC-ELD, AAC-LC, ALAC — independent audio/video start and stop
- System audio streaming (ALAC, unencrypted) — the reliable path for app audio
- Video URL mode (`/play`) with transport controls (play / pause / scrub / stop)
- Now-playing metadata (DMAP) with album artwork overlay
- DACP reverse remote — the TV remote drives the sender's playback
- NTP timing and UDP audio retransmit for packet-loss recovery
- Photo receiver — JPEG/PNG from the iOS Photos app, displayed full-screen

### App & platform

- Android TV / Fire TV app shell: foreground service, status UI, per-protocol cards
- Settings for display name, mirror resolution, mirror audio, PIN auth, video URL
  mode, portrait TV (rotated panel), start-on-boot, and a debug stats overlay
- Two flavors: Google TV (Android 10+) and Fire TV (Android 7.1+)
- No ads, no analytics, and no requests of its own: the traffic is what a session needs —
  discovery and media on the local network, plus media at a URL a sender explicitly hands over
- Cleartext HTTP is permitted for those media fetches, because the host is chosen by the sender
  (or by a DLNA control point) rather than by us — see
  `app/src/main/res/xml/network_security_config.xml`
- GPLv3, with complete source

---

## What opentvcast does not do

- **FairPlay-protected content** (Apple TV+, iTunes purchases, Netflix, Disney+) —
  this is Apple's content DRM, not the session-key exchange above. No open-source
  receiver can play it, and this one does not try.
- **Apple Music in-app audio** — protected on every AirPlay path. Route the Mac's
  system audio output instead; that works.
- **Buffered audio (AirPlay 2 stream type 103)** — accepted, not yet played back.
- **Cloud or remote streaming** — local network only.
- **Miracast / Google Cast** — out of v1 scope (see above).

---

## Requirements

**On your TV**

- Google TV / Android TV (Android 10+) or Amazon Fire TV (Android 7.1+)
- On the same network as the sender
- ADB enabled (Google TV) or sideloading enabled (Fire TV)

**On your Mac / iPhone / iPad**

- macOS 12 (Monterey) or later, or iOS/iPadOS 16 or later
- On the same network as the TV

**Network**

- Both devices on the same subnet — an ordinary home router is fine
- Multicast / mDNS must not be blocked
- 5 GHz Wi-Fi or Ethernet strongly recommended

---

## Build from source

There are no published binary releases yet; build from source.

### Prerequisites

| Tool | Version | Why |
|---|---|---|
| JDK | 17 | Kotlin and AGP both target 17 |
| Android SDK | `platforms;android-35`, `build-tools;35.0.0` | `compileSdk 35` |
| Android NDK | `28.2.13676358` | `libplayfair.so`, `libalac.so` |
| CMake | `3.22.1` | native build for `:airplay` |

Point Gradle at the SDK with `local.properties` (git-ignored):

```properties
sdk.dir=/path/to/Android/Sdk
```

### Build

```bash
git clone <this repository>
cd opentvcast

# Google TV / Android TV:
./gradlew :app:assembleGoogletvDebug

# Fire TV:
./gradlew :app:assembleFiretvDebug
```

APKs land in `app/build/outputs/apk/<flavor>/debug/`.

### Test

```bash
# Every module. Prefer this over a single aggregate task — see docs/TESTING.md.
./gradlew :core:test :platform:testDebugUnitTest :airplay:testDebugUnitTest \
  :test-runner:test \
  :app:testGoogletvDebugUnitTest :app:testFiretvDebugUnitTest

# Lint and both APKs (also the check that enforces the module graph)
./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug \
  :app:assembleGoogletvDebug :app:assembleFiretvDebug
```

No SDK on hand? The SDK-free subset still runs:

```bash
./gradlew --settings-file settings.verify.gradle.kts :core:test :test-runner:test
```

---

## Install on your TV

### Google TV / Android TV

1. **Settings → System → About → Android TV OS build**, click it 7 times to unlock
   Developer Options.
2. **Settings → System → Developer Options** → enable **USB debugging**.
3. Note the TV's IP under **Settings → Network & Internet**.
4. From your computer:
   ```bash
   adb connect <TV-IP>
   adb install app/build/outputs/apk/googletv/debug/app-googletv-debug.apk
   ```

### Amazon Fire TV

1. **Settings → My Fire TV → About**, click **Build** 7 times.
2. **Settings → My Fire TV → Developer Options** → enable **ADB debugging** and
   **Apps from Unknown Sources**.
3. Note the IP under **Settings → My Fire TV → About → Network**.
4. From your computer:
   ```bash
   adb connect <FIRETV-IP>
   adb install app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
   ```

---

## How to use

1. Launch opentvcast on the TV. The home screen shows the name your TV advertises.
2. On your Mac, click the **AirPlay** icon in the menu bar — or use
   **System Settings → Displays → AirPlay Display**.
3. Pick your TV from the list.
4. The screen appears on the TV.
5. To stop, select **Turn Off AirPlay Mirroring** on the sender, or stop the service
   from the app or its notification.

---

## Known limitations

- **Early software.** The AirPlay 2 stack is implemented and unit-tested, but
  real-device validation across macOS/iOS versions is ongoing. Bug reports are welcome.
- **Apple Music in-app audio is undecryptable.** macOS applies FairPlay on every
  AirPlay path. Route system audio output instead.
- **PIN auth is off by default.** With it disabled, any device on your network can
  mirror to the TV. Turn it on in Settings on a shared network, and see
  `docs/decisions/` for why the default is what it is.
- **Buffered audio (type 103)** is accepted but not played back.
- **Portrait TV rotates the picture, not your TV.** It is for a panel that has been
  rotated 90° by hand: the mirrored picture is turned to match, and the app's own UI stays
  landscape. Leaving the app ends the rotation. The first hardware test showed the rotation
  had been applied to a view type that ignores it; the picture is now carried by a
  `TextureView` while the panel is rotated, and an emulator check confirms it rotates and
  stays whole — the panel itself still needs a re-test.
- **Turning video URL mode off does not make a sender mirror.** It refuses the
  sender's URL instead; a video app will usually report that it cannot play on this
  TV, and you have to start Screen Mirroring yourself.
- **AP isolation or multicast filtering on your router** will stop the TV from
  appearing in the AirPlay menu. Disable those settings.
- On congested 2.4 GHz Wi-Fi you may see latency above 100 ms. Prefer 5 GHz or Ethernet.
- **DLNA has not been verified on a device** (see above).

When something fails on a real device, capture state before restarting the app:

```bash
tools/collect-device-logs.sh
```

It writes package state, memory, CPU and process-filtered logcat into
`device-test-logs/`. Set `OPENTVCAST_PACKAGE=<applicationId>` to target a flavor
explicitly.

---

## Contributing

Read [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) first. In short:

- Follow the coding rules there (file size limits, class-level rationale comments,
  test coverage)
- Every PR must pass build + tests + lint
- Open an issue before large changes

Useful entry points: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the module
layout and data flow, [`docs/TESTING.md`](docs/TESTING.md) for what is and is not
covered, and [`docs/decisions/`](docs/decisions/) for the ADRs.

---

## License

**GNU General Public License v3.0** — full text in [`LICENSE`](LICENSE),
third-party attributions in [`NOTICE`](NOTICE).

opentvcast is GPL-3.0 because it bundles `playfair`, the reverse-engineered FairPlay
session-key implementation that AirPlay 2 mirroring requires. Every public FairPlay
implementation is GPL; there is no permissive alternative. Upstream declares "GNU
GPL" without a version, so under GPLv2 section 9 we elect GPLv3 — the version
compatible with the Apache-2.0 material we also carry.

**opentvcast cannot be relicensed as MIT, Apache-2.0, LGPL, or closed source** while
it contains that component, and the maintainers do not own it well enough to sell a
commercial licence. If you need a permissive or commercial AirPlay receiver, you must
obtain an AirPlay/MFi licence from Apple and implement the stack independently.

FairPlay Streaming (FPS) *content* DRM is explicitly out of scope, so
FairPlay-protected media will not play.

If you received a binary build, you are entitled to the complete corresponding source
under the same licence.

---

## Acknowledgments

opentvcast is derived from [PhairPlay](https://github.com/mazer666/PhairPlay)
(Apache-2.0), which supplied the AirPlay 2 protocol stack, the native build, the
initial unit-test suite, and the architecture decision records. That provenance is
recorded in [`NOTICE`](NOTICE) §2.

Further thanks to:

- [openairplay/airplay-spec](https://github.com/openairplay/airplay-spec) —
  community-maintained AirPlay protocol documentation
- [UxPlay](https://github.com/FDH2/UxPlay) — open-source AirPlay mirror server,
  useful as a reference implementation
- [RPiPlay](https://github.com/FD-/RPiPlay) — source of the GPL `playfair` library
  (see [`NOTICE`](NOTICE) §1)
- [EstebanKubata/playfair](https://github.com/EstebanKubata/playfair) — the FairPlay
  implementation itself (GNU GPL)
- [macosforge/alac](https://github.com/macosforge/alac) — Apple's open-source ALAC
  decoder (Apache-2.0)

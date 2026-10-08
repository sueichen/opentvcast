# Feature design — Video URL mode switch (force mirroring)

**Status: designed, not implemented.** Requirements reserved: FR-60, FR-61.
Decision taken 2026-10-08. Implementation has not started.

Adds a setting that turns off AirPlay **video URL playback**, so that a sender's
video is never fetched and played by the TV itself. With the switch off, the
receiver is a mirroring-only device.

---

## 1. The problem

AirPlay has two unrelated ways to get video onto a TV:

- **Mirroring** (stream type 110): the sender encodes the screen and the receiver
  decodes it. This is what `AirPlayReceiver`'s mirror path does.
- **Video URL mode**: the sender sends `POST /play` with a `Content-Location`, and
  the receiver fetches that URL itself and plays it — in this codebase via
  `RtspVideoControl.play()` → `onVideoPlay` → `AirPlayReceiver.startUrlVideo()` →
  `AirPlayVideoPlayer` (a `MediaPlayer` reading the URL).

URL mode is what a video app (Safari, TV, YouTube) uses when the user taps the
AirPlay icon. Some users do not want it: the TV does the fetching, so the picture
may be a different encode, a different resolution, or the app the user expected to
see mirrored is replaced by a bare video player. They want the phone's screen on
the TV, exactly as mirroring shows it, and nothing else.

## 2. Decision

A setting turns URL mode off. **Default: URL mode enabled** — i.e. today's
behaviour. The change is opt-in, so an upgrade cannot take a capability away from
someone who relies on it.

## 3. Behaviour with the switch OFF

| Request | Behaviour |
|---|---|
| `POST /play` | **Refused.** No playback is started, no surface is shown, the player is never constructed. Returns `403 Forbidden`; logged at info level with the URL's host. |
| `POST /rate` | `200`, no-op (ignored). |
| `POST /scrub` | `200`, no-op. |
| `POST /stop` | `200`, no-op — and if URL playback somehow exists (see §5), it stops. |
| `GET /playback-info` / `GET /scrub` | `200` with the "nothing playing" shape, so senders polling them do not conclude the session died. |
| `GET /server-info` | Unchanged in v1 (see §8). |
| Mirroring (`SETUP`/`RECORD`, type 110), audio (96), photos, DACP | **Unaffected.** The switch is about URL playback only. |

## 4. The refusal contract

Three options were considered for `POST /play`:

| | Response | Verdict |
|---|---|---|
| a | `200 OK`, then ignore | **Rejected.** The sender believes it is playing; the user sees a black screen and blames us. A silent lie is worse than an error. |
| b | `404 Not Found` | Plausible — "no such resource" — but the handler exists and is simply disabled by policy. |
| c | `403 Forbidden` (chosen) | States exactly what happened: understood, refused. |

Whatever the code, the honest caveat belongs in the setting's own summary text:

> **Disabling video URL mode does not force the sender to mirror instead.** A video
> app that asks to play a URL will be refused and will usually report that it cannot
> play on this TV. To show that video, start **Screen Mirroring** from the sender's
> control centre.

That sentence is part of the feature, not documentation garnish: without it the
switch looks broken the first time someone taps AirPlay inside a video app.

## 5. Live toggling

The receiver must react to the switch changing while a session is open:

| Event | Action |
|---|---|
| Switch turned OFF while URL playback is running | Stop and release `AirPlayVideoPlayer`, clear the reference, end the streaming overlay (`stopUrlVideo()` already does the teardown), keep the RTSP session alive so mirroring can begin immediately. |
| Switch turned ON | Nothing to undo; the next `POST /play` is served normally. |

The gate must therefore read the setting **through a callback, not a snapshot** —
`urlModeEnabled: () -> Boolean` — so a session that started before the change sees
the new value, which is also what makes it trivially testable without a device.

## 6. Where it lives

| Layer | File | Change |
|---|---|---|
| Settings model | `app/.../settings/AppSettings.kt` | `videoUrlModeEnabled: Boolean = true` + KDoc explaining the caveat |
| Persistence | `app/.../settings/SettingsRepository.kt` | `video_url_mode_enabled` key |
| Settings UI | `app/.../ui/SettingsFragment.kt` | one row, AirPlay section, with the caveat as its summary |
| Strings | `app/src/main/res/values*/strings.xml` | title + summary × three locales |
| Protocol gate | `airplay/.../RtspVideoControl.kt` | `urlModeEnabled: () -> Boolean`; refuse `play()` when off |
| Wiring | `airplay/.../AirPlayReceiver.kt` | pass the setting through; stop in-flight playback when it turns off |
| Docs | `REQUIREMENTS.md` §1.8, `ARCHITECTURE.md` (AirPlay video), `README.md` + `README.zh-CN.md`, `CHANGELOG.md` | same change |

The gate sits in `RtspVideoControl` rather than in `RtspHandler` because that class
already owns `/play` parsing and the `onVideoPlay` callback, and it is the class
whose tests cover the video endpoints.

## 7. Tests

| Test | Module | What it pins |
|---|---|---|
| `RtspVideoControlTest` | `:airplay` | With the gate off, `play()` returns 4xx **and `onVideoPlay` is never invoked** (the assertion that matters — a 4xx alone could ship with playback still starting). With the gate on, the existing behaviour is unchanged: regression guard for the default path. Also: gate flipped mid-session is honoured on the next call. |
| `AppSettingsTest` | `:app` | default is `true`; `copy` keeps other fields |
| `SettingsRepositoryTest` | `:app` (Robolectric) | key round-trips; a pre-existing settings file loads with the default |
| `AirPlayReceiverTest` | `:airplay` | Toggling off while a fake URL player is live releases it and ends the overlay; mirroring state is untouched |

End-to-end check (emulator, cheap): `adb forward tcp:18200 tcp:8200` is DLNA; for
this feature the check is `curl`-style `POST /play` against RTSP port 7000 with the
gate off, expecting 403 and a log line, plus a screenshot showing no streaming
overlay appeared.

## 8. Risks and unknowns

- **No guaranteed fallback.** Refusing URL playback does not make a sender mirror;
  whether iOS retries in mirror mode is sender behaviour we do not control. The
  setting's text says so (§4). If it turns out that senders offer a worse
  experience than expected, the mitigation is documentation, not code.
- **Status-code behaviour against real senders is unverified.** 403 is the
  principled choice; a real Mac test may show that 404 produces a friendlier
  message. The code is a one-line change and is deliberately isolated in
  `RtspVideoControl` so it can be tuned after that test.
- **`InfoResponder`'s feature bits are untouched in v1.** Advertising "no video"
  in `/info` might stop senders offering URL mode at all, which would be better UX
  than refusing later — but the bits are not documented well enough to change
  safely, and a wrong bit can disable mirroring. Left as a follow-up with the
  evidence that would justify it.

## 9. Out of scope

- Blocking **DLNA** push of media (different protocol, different consent model: the
  user picks a file on the sender in that flow).
- Per-sender allow/deny lists.
- Signalling the choice to senders beyond the refusal itself (§8).

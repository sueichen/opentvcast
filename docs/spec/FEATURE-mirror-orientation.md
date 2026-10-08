# Feature design — Mirror display orientation

**Status: designed, not implemented.** Requirements reserved: FR-58, FR-59.
Decisions taken 2026-10-08. Implementation has not started.

Turns a portrait-mounted TV into a first-class configuration instead of an
accident: the mirroring picture is rotated 90°, so vertical short-form video fills
the panel's long edge with only narrow side bars, instead of sitting small in the
middle of a landscape frame.

---

## 1. The problem, measured

Mirroring is drawn by `app/ui/StreamingScreen.kt` → `applyAspectFit()`, which sizes
the `SurfaceView` to the decoded video's aspect ratio and centres it (contain, no
cropping) on a black background. The view is never rotated.

On a 1920×1080 panel a portrait phone stream (1080×1920) is therefore rendered at
**607×1080** — 657 px of black, split between the two sides, i.e. **34 % of the
panel width left empty**. That is the behaviour users see as "the picture is only
in the middle".

Nothing in the app knows whether the panel is standing upright or lying on its
side, and AirPlay sends the phone's screen as-is. So the only place this can be
fixed is inside the receiver, at display time.

## 2. Decisions

| # | Decision | Why |
|---|---|---|
| 1 | **The setting describes the physical panel, not the content.** `PORTRAIT` means "my TV is rotated 90°"; the receiver rotates the picture so it reads upright. | Content is never cropped, and the sender needs no cooperation. |
| 2 | **Two rotation directions, user-selectable** (`LEFT` = counter-clockwise, `RIGHT` = clockwise). | Which way is correct depends on how the panel was turned; guessing wrong yields an upside-down-looking picture, so it must be a choice. |
| 3 | **v1 affects the mirroring screen only.** | The photo screen (`ImageView.FIT_CENTER`) and the now-playing card have no orientation problem: they are already centred and upright. |
| 4 | Default is `LANDSCAPE` — today's behaviour. | An upgrade must not silently change what existing users see. |

## 3. Behaviour

### 3.1 Coordinate frames

Two frames must be kept apart, because the confusion between them is the whole
difficulty of this feature:

- **Panel frame** — the pixels, always 1920×1080 on a 1080p TV, regardless of how
  the TV is standing. This is also the Android window's coordinate space.
- **Viewer frame** — what a person in front of the TV sees. Rotating the panel 90°
  swaps the axes: the panel's 1920-pixel axis becomes the viewer's vertical axis.

Rotating the *picture* by ±90° maps content onto the viewer's vertical axis
correctly; the bars that remain are the ones the content's aspect ratio forces.

### 3.2 Framing rules

Let `src = (vw, vh)` be the decoded video size and `panel = (1920, 1080)`.

| Mode | Effective content size | Same as today? |
|---|---|---|
| `LANDSCAPE` | `(vw, vh)` | yes — `applyAspectFit()` unchanged |
| `PORTRAIT` | `(vh, vw)` — because the picture is rotated | no |

The effective size is then *contained* (no crop) into the panel and centred.

### 3.3 Worked examples (portrait-mounted panel)

| Mirrored content | After rotation | Occupied panel rect | What the viewer sees |
|---|---|---|---|
| 1080×1920 (9:16 phone) | 1920×1080 | 1920×1080 — full panel | full screen, **no bars** |
| 1080×2340 (9:19.5 tall phone) | 2340×1080 | 1920×886 | upright, fills the long edge, **~97 px bars left and right** |
| 1920×1080 (landscape video) | 1080×1920 | 607×1080 | small and centred, bars above and below — unavoidable for landscape content on a portrait panel |
| 0×0 (size unknown yet) | — | `MATCH_PARENT` | unchanged fallback while the stream warms up |

Row 2 is the case from the request: a vertical short-form video now fills the
panel's long edge with only narrow side bars, instead of 34 % of the width being
wasted.

## 4. The fit mathematics

Extracted as a pure function so the interesting part is testable without a device
(the `SpsParser` / `OverlayDecision` precedent):

```kotlin
// :core — video/MirrorFit.kt  (no android.*, plain Kotlin)
enum class FitRotation { NONE, LEFT, RIGHT }        // LEFT = CCW 90°, RIGHT = CW 90°

data class FitRect(val widthPx: Int, val heightPx: Int, val rotation: FitRotation)

object MirrorFit {
    fun fit(srcW: Int, srcH: Int, panelW: Int, panelH: Int, rotation: FitRotation): FitRect
}
```

Rules:

1. `rotation == NONE` → effective = `(srcW, srcH)`; else effective = `(srcH, srcW)`.
2. Contain into `(panelW, panelH)`: `scale = min(panelW / effW, panelH / effH)`.
3. Return the scaled size, floored, plus the rotation that must be applied.
4. Any non-positive input → caller falls back to `MATCH_PARENT` (today's behaviour).

The rect returned is in **panel coordinates**. When rotation is applied, the view
box must be the *inverse-rotated* rect — see §5, where that constraint bites.

## 5. Rendering: implementation strategies and the validation gate

Rotating a full-bleed video on a landscape window is the risky part, because a
rect that is *taller than the window* cannot be laid out as an ordinary child view.
Three strategies exist; the choice is not made until it is measured.

| | Strategy | How | Risk / cost |
|---|---|---|---|
| **S1** (preferred) | Oversized `SurfaceView` + `View.rotation` | Lay the view out as `(rectH × rectW)`, set `clipChildren = false` up the parent chain, apply `rotation = ±90°`. | The layout box is taller than the window (e.g. 1920 px in a 1080 px window). SurfaceView transforms and out-of-bounds boxes behave inconsistently across TV SoCs. |
| **S2** (fallback) | `TextureView` while in portrait mode | TextureView accepts arbitrary transforms; it can be laid out oversized and rotated reliably. | An extra GPU copy and roughly one frame of latency — against this project's "SurfaceView for lowest latency" choice, which is why it is a fallback, not the default. |
| **S3** (investigate) | Composer-side buffer transform | `SurfaceControl.Transaction.setBufferTransform` (or `Surface.setBuffersTransform`) rotates the buffer with no layout gymnastics. | Availability and behaviour on TV SoCs are unverified; must be checked against platform docs before being relied on. |

**Validation gate.** This feature is not "done" until a screenshot from the
Android TV emulator shows the framing in §3.3, and then a real panel confirms it.
The emulator run is cheap (`adb exec-out screencap`); the hardware run is not
optional, because the risk here is compositor behaviour, which the emulator does
not reproduce faithfully.

Landscape mode must remain pixel-identical to today: it keeps the existing code
path untouched.

## 6. Settings and UI

Two rows, both using the existing whole-row-clickable `SwitchCompat` template — no
new layouts, and consistent with the rest of the settings screen:

| Row | Values | Default | Key |
|---|---|---|---|
| **Portrait TV** | OFF = panel is landscape · ON = panel is rotated 90° | OFF | `mirror_orientation` (`LANDSCAPE` / `PORTRAIT`) |
| **Rotate direction** | OFF = left (CCW) · ON = right (CW) | OFF (left) | `portrait_rotation` (`LEFT` / `RIGHT`) |

`Rotate direction` only has an effect while `Portrait TV` is ON; its summary text
says so, rather than the row being hidden (hiding rows on a TV remote is more
confusing than explaining them).

New strings (`values/strings.xml`, with `values-de` / `values-fr` added in the same
change so untranslated rows fall back to English rather than to a key name — FR-54,
FR-56): two titles plus two summaries.

Changing either setting takes effect **without restarting the service**: the screen
re-reads the current value on its next layout pass (it already polls at 200 ms for
the debug HUD and the aspect fit).

## 7. Where it lives

| Layer | File | Change |
|---|---|---|
| Pure logic | `core/src/main/kotlin/tv/opentvcast/core/video/MirrorFit.kt` | **new** — the maths and enums above |
| Settings model | `app/.../settings/AppSettings.kt` | `mirrorOrientation`, `portraitRotation` + KDoc |
| Persistence | `app/.../settings/SettingsRepository.kt` | two new keys, written and read alongside the existing eight |
| Settings UI | `app/.../ui/SettingsFragment.kt` | two rows in the AirPlay section |
| Strings | `app/src/main/res/values*/strings.xml` | four strings × three locales |
| Rendering | `app/.../ui/StreamingScreen.kt` | rotation-aware fit; accept the current mode/direction; keep `LANDSCAPE` on the existing path |
| Wiring | `app/.../MainActivity.kt` | push the current setting into `StreamingScreen` from the existing settings collector |
| Docs | `REQUIREMENTS.md` §1.8, `ARCHITECTURE.md` (streaming screen), `README.md` + `README.zh-CN.md` (settings table), `CHANGELOG.md` | same change |

## 8. Tests

| Test | Module | What it pins |
|---|---|---|
| `MirrorFitTest` | `:core` (plain JUnit) | §3.3 rows exactly; `NONE` is byte-identical to today's `applyAspectFit` for the same inputs; unknown/zero sizes; extreme aspects (21:9, 9:21); `LEFT` and `RIGHT` produce the same rect but different rotation; floor behaviour (odd pixels) |
| `AppSettingsTest` | `:app` | defaults are `LANDSCAPE` / `LEFT`; `copy` keeps the other fields |
| `SettingsRepositoryTest` | `:app` (Robolectric) | both keys round-trip, and a settings file written before this feature still loads (defaults applied) |

Not unit-tested, by design: the transform itself (§5). It is the compositor's
behaviour that carries the risk, and a JVM test cannot speak for it — the gate is
the emulator screenshot plus hardware.

## 9. Risks and unknowns

- **Compositor behaviour on TV SoCs** (S1) — the only real unknown, and the reason
  for the validation gate and for S2 existing.
- **Upside-down picture** if the user picks the wrong direction — mitigated by
  offering both, not by trying to auto-detect (there is nothing to detect: the
  panel reports nothing about how it is physically mounted).
- **Landscape content on a portrait panel** looks small (§3.3 row 3). This is
  geometry, not a bug; the setting is meant to be switched with the TV's position.
- Adding a `:core` package for one function is deliberate: it keeps the maths in
  the module whose tests need no device.

## 10. Out of scope

- Cropping / "fill" modes that would cut content to remove bars.
- The photo screen and the now-playing card (decision 3).
- Photo/artwork orientation inside those screens.
- Sender-side rotation hints (`rotation` in InfoResponder's feature list) — iOS
  mirrors the device screen as it is; this feature is display-side only.
- Auto-detecting panel orientation.

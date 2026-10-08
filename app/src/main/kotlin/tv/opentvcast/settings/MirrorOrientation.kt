package tv.opentvcast.settings

/**
 * Physical orientation of the TV the app is running on.
 *
 * [LANDSCAPE] is the ordinary case and the default: the panel stands the way it
 * shipped. [PORTRAIT] means the user rotated the panel 90°, so the app must present a
 * portrait window — otherwise a vertical phone stream is pillarboxed in the middle of
 * a landscape frame, and the rest of the UI reads sideways to someone looking at the
 * rotated panel.
 *
 * Why the whole window and not just the video layer: rotating only the video would
 * leave the UI sideways on a rotated panel, and rotating a view inside a landscape
 * window cannot produce a rectangle taller than the window. Asking the platform for a
 * portrait window is smaller, has no rotation maths, and the existing aspect-fit
 * logic then frames vertical content correctly without a single change — see
 * `docs/spec/FEATURE-mirror-orientation.md`.
 */
enum class MirrorOrientation {
    LANDSCAPE,

    /** The panel is rotated 90°; the app window becomes portrait. */
    PORTRAIT,
}

/**
 * Which way the panel was turned, for [MirrorOrientation.PORTRAIT].
 *
 * There is nothing to detect: the panel reports nothing about how it is physically
 * mounted, and getting it wrong is visible (the picture is upside down relative to the
 * other choice), so the user picks. The two values map to the platform's two portrait
 * orientations.
 */
enum class PortraitRotation {
    /** Rotated counter-clockwise (the platform's "portrait"). */
    LEFT,

    /** Rotated clockwise (the platform's "reverse portrait"). */
    RIGHT,
}

/**
 * The one place that turns the two orientation settings into the angle the video view
 * rotates its content by.
 *
 * Extracted as a pure function so it is unit-testable without a device.
 *
 * Why an angle rather than `Activity.requestedOrientation`: **Android TV refuses an
 * application's display-orientation request.** Measured on an Android 14 TV image, a
 * `PORTRAIT` request is accepted by the framework and changes nothing — the display
 * stays `ROTATION_0`; only the system-wide `user_rotation` setting rotates it, and that
 * needs `WRITE_SETTINGS` and would outlive the app, which is the opposite of what a
 * "just for this app" switch should do. Rotating the picture inside our own window needs
 * no permission, works on every device, and is undone the moment the app exits.
 *
 * 0° means "leave the picture alone" (the ordinary landscape panel).
 */
object PanelOrientationMapping {

    /** Degrees clockwise to rotate the mirrored picture by. */
    fun rotationDegrees(orientation: MirrorOrientation, rotation: PortraitRotation): Int =
        when (orientation) {
            MirrorOrientation.LANDSCAPE -> 0
            MirrorOrientation.PORTRAIT -> when (rotation) {
                PortraitRotation.LEFT -> 90
                PortraitRotation.RIGHT -> 270
            }
        }

    /**
     * `true` when the panel is declared rotated. Used by the settings screen to explain
     * the direction row, and by tests as the readable form of the mapping.
     */
    fun isPortraitPanel(orientation: MirrorOrientation): Boolean =
        orientation == MirrorOrientation.PORTRAIT
}

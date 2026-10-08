package tv.opentvcast.ui

import tv.opentvcast.settings.MirrorOrientation
import tv.opentvcast.settings.PanelOrientationMapping
import tv.opentvcast.settings.PortraitRotation

/**
 * The live "Portrait TV" state, shared between the settings screen (which saves it) and
 * [StreamingScreen] (which rotates the picture).
 *
 * The same shape as `StreamStats.overlayEnabled`: one volatile field that the UI polls,
 * because the alternative — threading a settings flow into the view — buys nothing for a
 * single value the view re-reads every 200 ms anyway.
 *
 * Defaults to the ordinary case: no rotation.
 */
object PanelOrientationState {

    /** Degrees clockwise the mirrored picture should be rotated by; 0 = landscape panel. */
    @Volatile
    var rotationDegrees: Int = 0
        private set

    fun apply(orientation: MirrorOrientation, rotation: PortraitRotation) {
        rotationDegrees = PanelOrientationMapping.rotationDegrees(orientation, rotation)
    }
}

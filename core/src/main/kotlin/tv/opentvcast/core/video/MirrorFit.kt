/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.core.video

/** The layout size a video view should be given, before any rotation is applied. */
data class FitBox(val widthPx: Int, val heightPx: Int)

/**
 * Works out how large the mirrored picture should be, and what that means for the view
 * that holds it.
 *
 * WHY THIS IS A PURE FUNCTION: the interesting part of "Portrait TV" is arithmetic — how
 * big the picture ends up, and how big the view must be *before* it is rotated — and
 * arithmetic is the one thing here that can be tested without a device, a sender, or a
 * rotated panel.
 *
 * ### The two coordinate frames
 *
 * - **Panel frame** — the display's pixels. Always 1920×1080 on a 1080p TV, whatever the
 *   panel's physical position, and the frame the container is measured in.
 * - **Viewer frame** — what a person in front of the rotated panel sees: the panel's long
 *   pixel axis becomes their vertical axis.
 *
 * A panel declared "rotated by 90°" is fixed in landscape as far as Android is concerned
 * (see `PanelOrientationMapping`), so the picture itself is rotated to match the viewer.
 * In panel coordinates that swaps the picture's extents: a 1080×1920 stream covers
 * 1920 of the panel's width and 1080 of its height, i.e. the whole screen.
 *
 * ### Why the box is not the displayed rectangle
 *
 * Rotating a view by 90° swaps its extents on screen: a box of `(w, h)` displays as
 * `(h, w)`. So to *display* a rectangle that fits the panel, the view must be laid out as
 * its transpose — which, for vertical content on a landscape window, is a box **taller
 * than the window** (1080×1920 in a 1080-tall window). That is legal as long as the
 * parent does not clip it, and it is why `StreamingScreen` disables `clipChildren`: the
 * overhang is empty space that the rotation moves off-screen, while the picture lands
 * inside the window.
 */
object MirrorFit {

    /** Rotation that means "no rotation": the panel stands the way it shipped. */
    const val NO_ROTATION = 0

    /**
     * The size to give the video view.
     *
     * @param rotationDegrees clockwise rotation that will be applied to the view:
     *        `0`, `90` or `270`. Any other value is treated as no rotation, so a bad
     *        setting degrades to today's behaviour rather than to something arbitrary.
     * @return the view's layout size, or `null` when a dimension is not known yet — the
     *         caller then falls back to filling the container, exactly as before.
     */
    fun boxFor(
        videoWidth: Int,
        videoHeight: Int,
        containerWidth: Int,
        containerHeight: Int,
        rotationDegrees: Int,
    ): FitBox? {
        if (videoWidth <= 0 || videoHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) return null

        val rotated = rotationDegrees == 90 || rotationDegrees == 270

        // Extents as they will occupy the container, accounting for the rotation.
        val effectiveWidth = if (rotated) videoHeight else videoWidth
        val effectiveHeight = if (rotated) videoWidth else videoHeight

        // Contain (never crop): the same rule the unrotated path has always used.
        val widthScale = containerWidth.toDouble() / effectiveWidth
        val heightScale = containerHeight.toDouble() / effectiveHeight
        val scale = minOf(widthScale, heightScale)

        var displayedWidth = (effectiveWidth * scale).toInt()
        var displayedHeight = (effectiveHeight * scale).toInt()

        // Whichever ratio was the smaller one is, by definition of "contain", filled
        // exactly — but the multiplication can land on 1919.999… and truncate to 1919,
        // leaving a hairline bar on the edge that should touch. Snap that axis rather than
        // rounding both: rounding would also nudge the *other* axis, changing the framing
        // that landscape panels have always had. (A property test caught this.)
        if (widthScale <= heightScale) displayedWidth = containerWidth else displayedHeight = containerHeight

        displayedWidth = displayedWidth.coerceIn(1, containerWidth)
        displayedHeight = displayedHeight.coerceIn(1, containerHeight)

        return if (rotated) {
            // The view is laid out as the transpose of what it will display.
            FitBox(widthPx = displayedHeight, heightPx = displayedWidth)
        } else {
            FitBox(widthPx = displayedWidth, heightPx = displayedHeight)
        }
    }
}

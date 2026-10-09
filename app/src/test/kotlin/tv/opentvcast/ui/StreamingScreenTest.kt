/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.ui

import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.opentvcast.R
import tv.opentvcast.airplay.StreamStats
import tv.opentvcast.settings.MirrorOrientation
import tv.opentvcast.settings.PortraitRotation

/**
 * Tests for [StreamingScreen]'s rotation wiring — the part the pure `MirrorFit` tests cannot reach
 * (FR-58).
 *
 * WHY THIS FILE EXISTS: `MirrorFit` proves the arithmetic and `PanelOrientationMapping` proves the
 * angle, but neither proves that the *right view* uses them. That distinction is not academic: the
 * first implementation applied the transposed box to a `SurfaceView` and set `rotation` on it, and
 * every test here was green while a real TV showed the picture with its long edge along the panel's
 * short edge — because a SurfaceView's content cannot be transformed at all. So these tests now
 * assert which view carries the picture, not merely what number was computed.
 *
 * HOW: Robolectric, because this is a `View` that needs a real measure/layout pass. The activity is
 * not involved: the view is measured and laid out by hand at 1920x1080, which is also its own
 * container size, and [StreamingScreen.applyAspectFit] is called directly instead of waiting out the
 * 200 ms tick.
 *
 * `StreamStats` and `PanelOrientationState` are process-wide singletons, so every test puts them back
 * the way it found them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingScreenTest {

    private val panelWidth = 1920
    private val panelHeight = 1080

    @After
    fun resetSharedState() {
        StreamStats.videoWidth = 0
        StreamStats.videoHeight = 0
        PanelOrientationState.apply(MirrorOrientation.LANDSCAPE, PortraitRotation.LEFT)
    }

    private class Screen(
        val view: StreamingScreen,
        val surfaceView: SurfaceView,
        val textureView: TextureView,
    ) {
        /** The view that is supposed to be showing the picture right now. */
        val active: View get() = view.activeView()

        /** The box the active view was given. */
        val boxWidth: Int get() = active.layoutParams.width
        val boxHeight: Int get() = active.layoutParams.height
    }

    /** A streaming screen laid out as a 1080p landscape window would lay it out. */
    private fun screen(): Screen {
        val view = StreamingScreen(RuntimeEnvironment.getApplication())
        view.measure(
            MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, panelWidth, panelHeight)
        return Screen(view, view.getChildAt(0) as SurfaceView, view.getChildAt(1) as TextureView)
    }

    private fun video(width: Int, height: Int) {
        StreamStats.videoWidth = width
        StreamStats.videoHeight = height
    }

    // ─── the default path must not change ──────────────────────────────────────

    @Test
    fun `a landscape panel sizes the SurfaceView to the video and does not rotate it`() {
        val s = screen()
        video(1080, 1920)

        s.view.applyAspectFit()

        assertSame("a landscape panel must keep the zero-copy SurfaceView path", s.surfaceView, s.active)
        assertEquals(0f, s.surfaceView.rotation, 0.0f)
        assertEquals(607, s.boxWidth)
        assertEquals(1080, s.boxHeight)
        assertEquals(View.VISIBLE, s.surfaceView.visibility)
        assertEquals("the TextureView is not in use and must not be composited", View.INVISIBLE, s.textureView.visibility)
    }

    // ─── portrait panel ────────────────────────────────────────────────────────

    @Test
    fun `a portrait panel drives the TextureView, because a SurfaceView cannot be transformed`() {
        // The case the feature exists for, and the reason for the second view: the picture must be
        // rotated, so it must be carried by the one view type that honours rotation.
        val s = screen()
        video(1080, 1920)
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)

        s.view.applyAspectFit()

        assertSame("a rotated picture must not go to the SurfaceView", s.textureView, s.active)
        assertEquals(90f, s.textureView.rotation, 0.0f)
        assertEquals(View.VISIBLE, s.textureView.visibility)
        assertEquals(View.INVISIBLE, s.surfaceView.visibility)
    }

    @Test
    fun `the SurfaceView is never asked to rotate, since it silently ignores it`() {
        // Guard against the original defect coming back: setting `rotation` on a SurfaceView looks
        // right, reads right, and changes nothing on screen.
        val s = screen()
        video(1080, 1920)
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)

        s.view.applyAspectFit()

        assertEquals(0f, s.surfaceView.rotation, 0.0f)
    }

    @Test
    fun `a portrait panel lays the box out as the transpose`() {
        // Rotating the view swaps its extents, so the 1080x1920 display rectangle needs a 1080x1920
        // box — taller than the 1080-high window, which is why clipping is off.
        val s = screen()
        video(1080, 1920)
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)

        s.view.applyAspectFit()

        assertEquals(1080, s.boxWidth)
        assertEquals(1920, s.boxHeight)
    }

    @Test
    fun `the direction setting reaches the view`() {
        val s = screen()
        video(1080, 2340)
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.RIGHT)

        s.view.applyAspectFit()

        assertEquals(270f, s.textureView.rotation, 0.0f)
    }

    @Test
    fun `a modern phone stream fills the long edge with narrow side bars`() {
        // 1080x2340 rotated: displayed 1920 x 886, so the box is 886 x 1920. This is the outcome
        // from the request, asserted at the view that produces it.
        val s = screen()
        video(1080, 2340)
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)

        s.view.applyAspectFit()

        assertEquals(886, s.boxWidth)
        assertEquals(1920, s.boxHeight)
    }

    @Test
    fun `the picture follows a mid-session change of the setting`() {
        // The user can flip "Portrait TV" while a mirror is running; the next pass must switch both
        // the view and the rotation, and flipping it back must undo that. The decoder follows the
        // surface on its own (MirrorStreamServer rebuilds when the Surface changes).
        val s = screen()
        video(1080, 1920)

        s.view.applyAspectFit()
        assertSame(s.surfaceView, s.active)
        assertEquals(0f, s.surfaceView.rotation, 0.0f)

        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)
        s.view.applyAspectFit()
        assertSame(s.textureView, s.active)
        assertEquals(90f, s.textureView.rotation, 0.0f)
        assertTrue("the surface source must follow the view", s.view.usesTextureView())

        PanelOrientationState.apply(MirrorOrientation.LANDSCAPE, PortraitRotation.LEFT)
        s.view.applyAspectFit()
        assertSame(s.surfaceView, s.active)
        assertFalse(s.view.usesTextureView())
        assertEquals(607, s.boxWidth)
    }

    @Test
    fun `an unknown video size falls back to the container, transposed when rotated`() {
        // URL playback never reports a size worth trusting (MediaPlayer called a 16:9 stream
        // 540x540), so the box comes from geometry alone. Filling the container while rotated would
        // rotate into an area larger than the window, and the window would clip the picture.
        val landscape = screen()

        landscape.view.applyAspectFit()

        assertEquals(1920, landscape.boxWidth)
        assertEquals(1080, landscape.boxHeight)
        assertSame(landscape.surfaceView, landscape.active)

        val portrait = screen()
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)

        portrait.view.applyAspectFit()

        assertEquals(1080, portrait.boxWidth)
        assertEquals(1920, portrait.boxHeight)
        assertEquals(90f, portrait.textureView.rotation, 0.0f)
        assertSame(portrait.textureView, portrait.active)
    }

    @Test
    fun `an angle the settings cannot produce is treated as no rotation`() {
        // The view and the fit maths must agree on what "rotated" means. They were written with two
        // different rules once — `!= 0` here, `== 90 || == 270` there — and that is exactly how the
        // sideways-picture defect came about. An out-of-band angle is only askable because the
        // predicate takes one.
        val s = screen()

        assertFalse(s.view.usesTextureView(0))
        assertFalse("a half turn is not a quarter turn", s.view.usesTextureView(180))
        assertFalse(s.view.usesTextureView(45))
        assertTrue(s.view.usesTextureView(90))
        assertTrue(s.view.usesTextureView(270))
    }

    @Test
    fun `both views are given the same box, so a switch is instant`() {
        // Only one is visible at a time, but the hidden one is kept laid out and ready: a mid-session
        // flip then costs nothing but the visibility change.
        val s = screen()
        video(1080, 2340)
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)

        s.view.applyAspectFit()

        assertEquals(s.textureView.layoutParams.width, s.surfaceView.layoutParams.width)
        assertEquals(s.textureView.layoutParams.height, s.surfaceView.layoutParams.height)
        assertEquals(886, s.surfaceView.layoutParams.width)
    }

    // ─── the load-bearing layout constraint ────────────────────────────────────

    @Test
    fun `the streaming screen does not clip its children`() {
        // The rotated video box is taller than the window on a portrait panel. Clipping it would cut
        // the picture, not the empty overhang. Nothing else in the build fails if this is turned back
        // on, so it is pinned here.
        val s = screen()

        assertFalse("the rotated box overhangs the window; clipping would cut the picture", s.view.clipChildren)
    }

    @Test
    fun `the streaming container does not clip its children either`() {
        val root = LayoutInflater.from(RuntimeEnvironment.getApplication())
            .inflate(R.layout.activity_main, null)
        val container = root.findViewById<ViewGroup>(R.id.streaming_container)

        assertFalse("a clipped container would cut the rotated picture", container.clipChildren)
    }
}

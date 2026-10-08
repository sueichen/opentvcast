package tv.opentvcast.ui

import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.View.MeasureSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Tests for [StreamingScreen]'s rotation wiring — the part the pure `MirrorFit` tests cannot
 * reach (FR-58).
 *
 * WHY THIS FILE EXISTS: `MirrorFit` proves the arithmetic and `PanelOrientationMapping` proves
 * the angle, but neither proves that the view *uses* them. Wiring fails silently: pass the
 * wrong angle, forget to apply the box, or let `clipChildren` stay true, and every other test
 * stays green while the picture on a rotated TV is wrong — which is precisely the bug the
 * feature exists to fix.
 *
 * HOW: Robolectric, because this is a `View` that needs a real measure/layout pass. The
 * activity is not involved: the view is measured and laid out by hand at 1920x1080, which is
 * also its own container size, and [StreamingScreen.applyAspectFit] is called directly instead
 * of waiting out the 200 ms tick.
 *
 * `StreamStats` and `PanelOrientationState` are process-wide singletons, so every test puts
 * them back the way it found them.
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

    /** A streaming screen laid out as a 1080p landscape window would lay it out. */
    private fun screen(): Pair<StreamingScreen, SurfaceView> {
        val view = StreamingScreen(RuntimeEnvironment.getApplication())
        view.measure(
            MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, panelWidth, panelHeight)
        return view to (view.getChildAt(0) as SurfaceView)
    }

    private fun video(width: Int, height: Int) {
        StreamStats.videoWidth = width
        StreamStats.videoHeight = height
    }

    // ─── the default path must not change ──────────────────────────────────────

    @Test
    fun `a landscape panel sizes the surface to the video and does not rotate it`() {
        val (view, surface) = screen()
        video(1080, 1920)

        view.applyAspectFit()

        assertEquals(0f, surface.rotation, 0.0f)
        assertEquals(607, surface.layoutParams.width)
        assertEquals(1080, surface.layoutParams.height)
    }

    // ─── portrait panel ────────────────────────────────────────────────────────

    @Test
    fun `a portrait panel lays the view out as the transpose and rotates it`() {
        // The case the feature exists for: a 9:16 phone stream on a physically rotated panel.
        // The box is 1080x1920 — taller than the 1080-high window — and the rotation turns it
        // into a full-screen 1920x1080 picture.
        val (view, surface) = screen()
        video(1080, 1920)
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)

        view.applyAspectFit()

        assertEquals(90f, surface.rotation, 0.0f)
        assertEquals(1080, surface.layoutParams.width)
        assertEquals(1920, surface.layoutParams.height)
    }

    @Test
    fun `the direction setting reaches the view`() {
        val (view, surface) = screen()
        video(1080, 2340)
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.RIGHT)

        view.applyAspectFit()

        assertEquals(270f, surface.rotation, 0.0f)
    }

    @Test
    fun `a modern phone stream fills the long edge with narrow side bars`() {
        // 1080x2340 rotated: displayed 1920 x 886, so the box is 886 x 1920. This is the
        // outcome from the request, asserted at the view that produces it.
        val (view, surface) = screen()
        video(1080, 2340)
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)

        view.applyAspectFit()

        assertEquals(886, surface.layoutParams.width)
        assertEquals(1920, surface.layoutParams.height)
    }

    @Test
    fun `the picture follows a mid-session change of the setting`() {
        // The user can flip "Portrait TV" while a mirror is running; the next pass must
        // rotate, and flipping it back must undo that.
        val (view, surface) = screen()
        video(1080, 1920)

        view.applyAspectFit()
        assertEquals(0f, surface.rotation, 0.0f)

        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)
        view.applyAspectFit()
        assertEquals(90f, surface.rotation, 0.0f)

        PanelOrientationState.apply(MirrorOrientation.LANDSCAPE, PortraitRotation.LEFT)
        view.applyAspectFit()
        assertEquals(0f, surface.rotation, 0.0f)
        assertEquals(607, surface.layoutParams.width)
    }

    @Test
    fun `an unknown video size fills the container in whatever rotation is set`() {
        // Before the first frame, the size is unknown: filling the container is the same
        // fallback the unrotated path has always had.
        val (view, surface) = screen()
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)

        view.applyAspectFit()

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, surface.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, surface.layoutParams.height)
        assertEquals(90f, surface.rotation, 0.0f)
    }

    // ─── the load-bearing layout constraint ────────────────────────────────────

    @Test
    fun `the streaming screen does not clip its children`() {
        // The rotated video box is taller than the window on a portrait panel. Clipping it
        // would cut the picture, not the empty overhang. Nothing else in the build fails if
        // this is turned back on, so it is pinned here.
        val (view, _) = screen()

        assertFalse("the rotated box overhangs the window; clipping would cut the picture", view.clipChildren)
    }

    @Test
    fun `the streaming container does not clip its children either`() {
        val root = LayoutInflater.from(RuntimeEnvironment.getApplication())
            .inflate(R.layout.activity_main, null)
        val container = root.findViewById<ViewGroup>(R.id.streaming_container)

        assertFalse("a clipped container would cut the rotated picture", container.clipChildren)
    }
}

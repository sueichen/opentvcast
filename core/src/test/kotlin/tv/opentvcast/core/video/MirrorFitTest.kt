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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [MirrorFit] — the arithmetic behind "Portrait TV" (FR-58).
 *
 * WHY THESE NUMBERS MATTER: this function decides how large the picture is and how the
 * view must be laid out to get there. Both failure modes are silent and geometric — a
 * picture that is smaller than it should be, or a view whose transpose is forgotten so
 * the video appears stretched. There is no log line that would tell the user which one
 * happened, so the arithmetic is pinned here instead.
 *
 * The panel is 1920×1080 throughout, i.e. a 1080p TV held in landscape, with the picture
 * rotated in software to suit a physically rotated panel.
 */
class MirrorFitTest {

    private val panelW = 1920
    private val panelH = 1080

    // ─── landscape panel: unchanged from the behaviour that shipped ────────────

    @Test
    fun `without rotation the box is the contain fit of the video`() {
        // A 16:9 stream on a 16:9 panel fills it exactly.
        assertEquals(FitBox(1920, 1080), MirrorFit.boxFor(1920, 1080, panelW, panelH, 0))
    }

    @Test
    fun `without rotation a portrait stream is pillarboxed as before`() {
        // The behaviour the feature exists to change, pinned so the default path cannot
        // regress: 1080x1920 in a 1920x1080 panel leaves ~657 px of black in total.
        assertEquals(FitBox(607, 1080), MirrorFit.boxFor(1080, 1920, panelW, panelH, 0))
    }

    @Test
    fun `an unknown rotation falls back to no rotation rather than to something arbitrary`() {
        assertEquals(
            MirrorFit.boxFor(1080, 1920, panelW, panelH, 0),
            MirrorFit.boxFor(1080, 1920, panelW, panelH, 45),
        )
        assertEquals(
            MirrorFit.boxFor(1080, 1920, panelW, panelH, 0),
            MirrorFit.boxFor(1080, 1920, panelW, panelH, -90),
        )
    }

    // ─── rotated panel: the point of the feature ───────────────────────────────

    @Test
    fun `a 9 by 16 stream fills a rotated panel completely`() {
        // Rotated, the 1080x1920 picture covers the panel's 1920 axis with its height and
        // its 1080 axis with its width: a perfect fit, no bars.
        // The view is laid out as the transpose (1080 wide, 1920 tall) and rotated to
        // display as 1920x1080 — taller than the window, which is why clipChildren is off.
        val box = MirrorFit.boxFor(1080, 1920, panelW, panelH, 90)

        assertEquals(FitBox(1080, 1920), box)
    }

    @Test
    fun `a 9 by 19_5 stream fills the long edge and leaves narrow side bars`() {
        // The case from the request: a modern phone (1080x2340). Rotated it displays as
        // 1920 x 886 on the panel — full height in the viewer's frame, with ~97 px bars on
        // each side, instead of a small picture in the middle of a landscape frame.
        val box = MirrorFit.boxFor(1080, 2340, panelW, panelH, 90)!!

        // Transposed box: 886 wide, 1920 tall.
        assertEquals(FitBox(886, 1920), box)
        // And the displayed rectangle is the panel's width by 886 px.
        assertEquals(panelW, box.heightPx)
        assertEquals(886, box.widthPx)
    }

    @Test
    fun `the two rotation directions produce the same box`() {
        // 90 and 270 differ only in which way up the picture ends up; the geometry is the
        // same. Pinned because a wrong assumption here would show as a stretched picture.
        assertEquals(
            MirrorFit.boxFor(1080, 2340, panelW, panelH, 90),
            MirrorFit.boxFor(1080, 2340, panelW, panelH, 270),
        )
    }

    @Test
    fun `landscape content on a rotated panel is letterboxed, not stretched`() {
        // A 1920x1080 video rotated onto a physically portrait panel: the viewer sees it
        // small and centred. Unavoidable geometry, and it must stay undistorted.
        val box = MirrorFit.boxFor(1920, 1080, panelW, panelH, 90)!!

        // Panel-space displayed rect: 607 across the panel's width (the viewer's vertical
        // axis) by 1080 across its height (the viewer's horizontal axis) — i.e. the viewer
        // still sees a landscape picture, centred, with bars above and below.
        // The box is that rectangle transposed: 1080 wide by 607 tall.
        assertEquals(FitBox(1080, 607), box)
    }

    @Test
    fun `the rotated fit never exceeds the panel in the displayed orientation`() {
        // Property check across the aspect ratios phones actually produce.
        for ((vw, vh) in listOf(1080 to 1920, 1080 to 2340, 1170 to 2532, 828 to 1792, 1920 to 1080)) {
            val box = MirrorFit.boxFor(vw, vh, panelW, panelH, 90)!!

            val displayedWidth = box.heightPx
            val displayedHeight = box.widthPx
            assert(displayedWidth <= panelW) { "$vw x $vh: displayed width $displayedWidth > $panelW" }
            assert(displayedHeight <= panelH) { "$vw x $vh: displayed height $displayedHeight > $panelH" }
            // At least one axis is filled exactly — "contain" means as large as possible.
            assert(displayedWidth == panelW || displayedHeight == panelH) {
                "$vw x $vh: neither axis filled ($displayedWidth x $displayedHeight)"
            }
        }
    }

    // ─── degenerate inputs ─────────────────────────────────────────────────────

    @Test
    fun `an unknown video size asks the caller to fill the container`() {
        assertNull(MirrorFit.boxFor(0, 0, panelW, panelH, 90))
        assertNull(MirrorFit.boxFor(1080, 0, panelW, panelH, 90))
        assertNull(MirrorFit.boxFor(0, 1920, panelW, panelH, 90))
    }

    @Test
    fun `an unmeasured container asks the caller to fill the container`() {
        assertNull(MirrorFit.boxFor(1080, 1920, 0, panelH, 90))
        assertNull(MirrorFit.boxFor(1080, 1920, panelW, 0, 90))
    }

    @Test
    fun `extreme aspect ratios still produce a usable box`() {
        // 32:9 and a very tall phone: the maths must not produce a zero or negative size.
        for ((vw, vh) in listOf(3840 to 1080, 1080 to 3840)) {
            val box = MirrorFit.boxFor(vw, vh, panelW, panelH, 90)!!

            assert(box.widthPx >= 1) { "$vw x $vh produced width ${box.widthPx}" }
            assert(box.heightPx >= 1) { "$vw x $vh produced height ${box.heightPx}" }
        }
    }
}

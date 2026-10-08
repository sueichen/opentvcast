package tv.opentvcast.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [PanelOrientationMapping] — the only place that turns the two panel settings
 * into the angle the mirrored picture is rotated by (FR-58, FR-59).
 *
 * WHY THIS IS WORTH PINNING: the mapping is three branches, and getting one wrong is not a
 * crash but a silently wrong picture — rotated the wrong way, or not rotated at all, which
 * is exactly the bug the feature exists to fix. A pure function is the cheapest possible
 * place to catch that.
 *
 * The values are degrees clockwise. 90 and 270 both transpose the picture; they differ only
 * in which way up it ends up, which is why the user picks.
 */
class PanelOrientationMappingTest {

    @Test
    fun `a landscape panel rotates nothing`() {
        assertEquals(
            0,
            PanelOrientationMapping.rotationDegrees(MirrorOrientation.LANDSCAPE, PortraitRotation.LEFT),
        )
    }

    @Test
    fun `a portrait panel rotates a quarter turn`() {
        assertEquals(
            90,
            PanelOrientationMapping.rotationDegrees(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT),
        )
    }

    @Test
    fun `the other direction rotates the other way`() {
        // 270 clockwise is the same quarter turn mirrored: the difference between "reads
        // upright" and "reads upside down" for a user who turned their TV the other way.
        assertEquals(
            270,
            PanelOrientationMapping.rotationDegrees(MirrorOrientation.PORTRAIT, PortraitRotation.RIGHT),
        )
    }

    @Test
    fun `the direction is ignored while the panel is landscape`() {
        // The direction row stays visible and settable in landscape mode; it must not leak
        // into the rotation until the panel is declared rotated.
        assertEquals(
            PanelOrientationMapping.rotationDegrees(MirrorOrientation.LANDSCAPE, PortraitRotation.LEFT),
            PanelOrientationMapping.rotationDegrees(MirrorOrientation.LANDSCAPE, PortraitRotation.RIGHT),
        )
    }

    @Test
    fun `the three panel states produce three different angles`() {
        // Guards against a mapping that returns the same value for everything — the failure
        // mode that would make the feature look like it does nothing at all.
        val landscape = PanelOrientationMapping.rotationDegrees(MirrorOrientation.LANDSCAPE, PortraitRotation.LEFT)
        val left = PanelOrientationMapping.rotationDegrees(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)
        val right = PanelOrientationMapping.rotationDegrees(MirrorOrientation.PORTRAIT, PortraitRotation.RIGHT)

        assertEquals(3, setOf(landscape, left, right).size)
    }

    @Test
    fun `both portrait directions transpose the picture`() {
        // 90 and 270 are the two quarter turns; anything that is not a multiple of 90 would
        // leave the picture at an angle, and anything that is 180 would not fit a portrait
        // panel at all.
        for (rotation in PortraitRotation.entries) {
            val degrees = PanelOrientationMapping.rotationDegrees(MirrorOrientation.PORTRAIT, rotation)

            assertEquals("$rotation must be a quarter turn", 0, degrees % 90)
            assertEquals("$rotation must not be a half turn", 1, degrees % 180 / 90)
        }
    }
}

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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.opentvcast.settings.MirrorOrientation
import tv.opentvcast.settings.PortraitRotation

/**
 * Tests for [PanelOrientationState] — the value the view reads to decide whether to rotate.
 *
 * Deliberately **plain JUnit**, not Robolectric: the state is not a `View`, and under Robolectric's
 * sandbox classloader JaCoCo cannot see a class at all (measured 0% while its tests passed — the same
 * trap `AndroidNetworkMonitorTest` fell into). Keeping this half plain means the contract is measured
 * and it also runs in the SDK-free aggregate runner.
 *
 * The assertion that matters is the coupling: whatever `PanelOrientationMapping` produces is what the
 * view sees, because that mapping is the only place the two settings become an angle.
 */
class PanelOrientationStateTest {

    @After
    fun reset() {
        // Process-wide singleton: leave it the way the next test expects to find it.
        PanelOrientationState.apply(MirrorOrientation.LANDSCAPE, PortraitRotation.LEFT)
    }

    @Test
    fun `a landscape panel leaves the picture alone`() {
        PanelOrientationState.apply(MirrorOrientation.LANDSCAPE, PortraitRotation.LEFT)

        assertEquals(0, PanelOrientationState.rotationDegrees)
    }

    @Test
    fun `the state carries whichever direction the settings chose`() {
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.LEFT)
        assertEquals(90, PanelOrientationState.rotationDegrees)

        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.RIGHT)
        assertEquals(270, PanelOrientationState.rotationDegrees)
    }

    @Test
    fun `switching back to landscape clears the rotation`() {
        PanelOrientationState.apply(MirrorOrientation.PORTRAIT, PortraitRotation.RIGHT)
        PanelOrientationState.apply(MirrorOrientation.LANDSCAPE, PortraitRotation.RIGHT)

        assertEquals("the direction setting must not outlive the orientation one", 0, PanelOrientationState.rotationDegrees)
    }
}

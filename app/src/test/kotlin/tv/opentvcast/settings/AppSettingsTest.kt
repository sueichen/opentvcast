package tv.opentvcast.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppSettingsTest — Unit tests for [AppSettings] data class.
 *
 * WHY: [AppSettings] contains computed properties and constants that are
 * used throughout the app. Regression tests ensure that default values,
 * validation logic, and computed properties always behave correctly.
 *
 * WHAT WE TEST:
 * - Default values match the design spec
 * - [AppSettings.effectiveDisplayName] trims whitespace correctly
 * - [AppSettings.anyProtocolEnabled] returns correct results for all combinations
 * - [AppSettings.DISPLAY_NAME_MAX_LENGTH] constant value
 * - copy() semantics (standard data class behaviour)
 */
class AppSettingsTest {

    // ─── Default values ──────────────────────────────────────────────────────

    @Test
    fun `default settings have empty display name`() {
        assertEquals("", AppSettings.DEFAULT.displayName)
    }

    @Test
    fun `default settings have all protocols enabled`() {
        assertTrue(AppSettings.DEFAULT.airPlayEnabled)
        assertTrue(AppSettings.DEFAULT.dlnaEnabled)
    }

    @Test
    fun `default settings have pin auth disabled`() {
        assertFalse(AppSettings.DEFAULT.airPlayPinAuthEnabled)
    }

    @Test
    fun `default settings have start on boot disabled`() {
        assertFalse(AppSettings.DEFAULT.startOnBoot)
    }

    @Test
    fun `default settings describe a landscape panel`() {
        // The default must be the ordinary case: an upgrade cannot start rotating
        // someone's TV because they installed a new build.
        assertEquals(MirrorOrientation.LANDSCAPE, AppSettings.DEFAULT.mirrorOrientation)
        assertEquals(PortraitRotation.LEFT, AppSettings.DEFAULT.portraitRotation)
    }

    @Test
    fun `default settings allow video URL playback`() {
        // A capability the user already had must not disappear on upgrade; the switch is
        // opt-out, and this test is what says so.
        assertTrue(AppSettings.DEFAULT.videoUrlModeEnabled)
    }

    @Test
    fun `default settings have debug overlay disabled`() {
        assertFalse(AppSettings.DEFAULT.showDebugOverlay)
    }

    @Test
    fun `DISPLAY_NAME_MAX_LENGTH is 63`() {
        assertEquals(63, AppSettings.DISPLAY_NAME_MAX_LENGTH)
    }

    // ─── effectiveDisplayName ─────────────────────────────────────────────────

    @Test
    fun `effectiveDisplayName returns trimmed name`() {
        val settings = AppSettings(displayName = "  Living Room TV  ")
        assertEquals("Living Room TV", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName returns empty string for blank name`() {
        val settings = AppSettings(displayName = "   ")
        assertEquals("", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName returns name unchanged when no surrounding whitespace`() {
        val settings = AppSettings(displayName = "opentvcast")
        assertEquals("opentvcast", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName handles empty string`() {
        val settings = AppSettings(displayName = "")
        assertEquals("", settings.effectiveDisplayName)
    }

    // ─── anyProtocolEnabled ───────────────────────────────────────────────────

    @Test
    fun `anyProtocolEnabled is true when both protocols are enabled`() {
        val settings = AppSettings(airPlayEnabled = true, dlnaEnabled = true)
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only AirPlay is enabled`() {
        val settings = AppSettings(airPlayEnabled = true, dlnaEnabled = false)
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only DLNA is enabled`() {
        val settings = AppSettings(airPlayEnabled = false, dlnaEnabled = true)
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is false when both protocols are disabled`() {
        val settings = AppSettings(airPlayEnabled = false, dlnaEnabled = false)
        assertFalse(settings.anyProtocolEnabled)
    }

    // ─── copy() and equality ──────────────────────────────────────────────────

    @Test
    fun `sanitizeDisplayName trims surrounding whitespace`() {
        assertEquals("Living Room TV", AppSettings.sanitizeDisplayName("  Living Room TV\t"))
    }

    @Test
    fun `sanitizeDisplayName collapses a whitespace-only entry to the system default`() {
        assertEquals("", AppSettings.sanitizeDisplayName("   "))
    }

    @Test
    fun `sanitizeDisplayName clamps to the mDNS length limit`() {
        val tooLong = "x".repeat(AppSettings.DISPLAY_NAME_MAX_LENGTH + 10)
        assertEquals(
            AppSettings.DISPLAY_NAME_MAX_LENGTH,
            AppSettings.sanitizeDisplayName(tooLong).length,
        )
    }

    @Test
    fun `sanitizeDisplayName keeps an interior-valid name untouched`() {
        assertEquals("TV-1 (living room)", AppSettings.sanitizeDisplayName("TV-1 (living room)"))
    }
}

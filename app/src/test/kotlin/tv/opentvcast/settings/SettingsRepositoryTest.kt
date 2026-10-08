package tv.opentvcast.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for [SettingsRepository] — the DataStore read/write mapping.
 *
 * This is exactly the code the test-runner aggregation cannot see (it stubs
 * DataStore away), and a bug here means user settings silently revert or
 * default to the wrong protocol state.
 *
 * NOTE: the `preferencesDataStore` delegate is a process-wide singleton, so
 * every scenario lives in one test method executed in sequence, sharing one
 * store instance deliberately: write → read back → reset → read back proves
 * the full round trip on the same state the app would see.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val repo get() = SettingsRepository(context)

    @Test
    fun `defaults then update then read-back then reset round-trips`() = runBlocking {
        // 1. Fresh store → defaults, including the non-obvious ones
        //    (AirPlay and DLNA default ON, PIN auth and boot-start OFF).
        val defaults = repo.settingsFlow.first()
        assertEquals(AppSettings.DEFAULT, defaults)
        assertTrue(defaults.airPlayEnabled)
        assertTrue(defaults.dlnaEnabled)
        assertTrue(defaults.mirrorAudioEnabled)
        assertTrue("URL mode ships on — an upgrade must not take the capability away",
            defaults.videoUrlModeEnabled)
        assertFalse(defaults.airPlayPinAuthEnabled)
        assertFalse(defaults.startOnBoot)

        // 2. Update flips every field; the transform sees the CURRENT values.
        repo.update { current ->
            current.copy(
                displayName = "Living Room TV",
                airPlayEnabled = false,
                dlnaEnabled = false,
                airPlayPinAuthEnabled = true,
                startOnBoot = true,
                showDebugOverlay = true,
                forceHighResolution = true,
                mirrorAudioEnabled = false,
                videoUrlModeEnabled = false,
                mirrorOrientation = MirrorOrientation.PORTRAIT,
                portraitRotation = PortraitRotation.RIGHT,
            )
        }
        val updated = repo.settingsFlow.first()
        assertEquals("Living Room TV", updated.displayName)
        assertFalse(updated.airPlayEnabled)
        assertFalse(updated.dlnaEnabled)
        assertTrue(updated.airPlayPinAuthEnabled)
        assertTrue(updated.startOnBoot)
        assertTrue(updated.showDebugOverlay)
        assertTrue(updated.forceHighResolution)
        assertFalse(updated.mirrorAudioEnabled)
        assertFalse(updated.videoUrlModeEnabled)
        assertEquals(MirrorOrientation.PORTRAIT, updated.mirrorOrientation)
        assertEquals(PortraitRotation.RIGHT, updated.portraitRotation)

        // A partial update that says nothing about video URL mode must leave it alone:
        // a transform written for another setting cannot silently re-enable (or disable)
        // a feature the user chose.
        repo.update { it.copy(displayName = "Den TV") }
        assertFalse(repo.settingsFlow.first().videoUrlModeEnabled)

        // 3. The derived properties follow the stored flag, not a copy.
        assertEquals(2560, updated.mirrorWidth)
        assertEquals(1440, updated.mirrorHeight)

        // 4. resetToDefaults() clears everything back.
        repo.resetToDefaults()
        assertEquals(AppSettings.DEFAULT, repo.settingsFlow.first())
    }

    @Test
    fun `partial update preserves untouched fields`() = runBlocking {
        repo.update { it.copy(displayName = "Kitchen TV", airPlayEnabled = true) }
        repo.update { it.copy(displayName = "Den TV") }

        val settings = repo.settingsFlow.first()
        assertEquals("Den TV", settings.displayName)
        assertTrue("unmapped fields must survive a partial update", settings.airPlayEnabled)
    }
}

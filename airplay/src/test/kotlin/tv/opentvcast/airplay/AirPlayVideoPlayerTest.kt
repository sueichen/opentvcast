/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media-request header policy ([AirPlayVideoPlayer.mediaHeaders]).
 *
 * This is the part of URL playback that decides what identity the receiver presents to whoever
 * serves the media, and it is the one part that needs no `MediaPlayer` to test. The rest of
 * [AirPlayVideoPlayer] is verified on hardware: it owns a concrete `MediaPlayer`, so a JVM test
 * could only assert that a stub was called.
 */
class AirPlayVideoPlayerTest {

    @Test
    fun `a sender-supplied Referer is forwarded verbatim`() {
        assertEquals(
            mapOf("Referer" to "https://example.com/watch/123"),
            AirPlayVideoPlayer.mediaHeaders("https://example.com/watch/123"),
        )
    }

    @Test
    fun `no Referer sends no header at all`() {
        // Not an empty value: some CDNs read a blank Referer as "a page referred me" and refuse,
        // where an absent one is simply a direct request.
        assertTrue(AirPlayVideoPlayer.mediaHeaders(null).isEmpty())
        assertTrue(AirPlayVideoPlayer.mediaHeaders("").isEmpty())
        assertTrue(AirPlayVideoPlayer.mediaHeaders("   ").isEmpty())
    }

    @Test
    fun `nothing else is sent that the sender did not ask for`() {
        // Pins the policy as much as the value: the receiver presents exactly what it was told to.
        assertEquals(setOf("Referer"), AirPlayVideoPlayer.mediaHeaders("https://example.com/").keys)
    }

}

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

import tv.opentvcast.airplay.handshake.PlistCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RtspVideoControl] — the AirPlay URL-video transport endpoints
 * (/play, /rate, /scrub, /stop, /playback-info). Mirroring sessions never
 * reach this class; these verbs are what a sender app (Safari, a TV app)
 * drives when it hands the receiver a URL instead of a pixel stream.
 */
class RtspVideoControlTest {

    private class Harness(urlModeEnabled: Boolean = true, gate: (() -> Boolean)? = null) {
        val played = mutableListOf<Pair<String, Double>>()
        val rates = mutableListOf<Float>()
        val scrubs = mutableListOf<Double>()
        var stops = 0
        var info: PlaybackInfo? = PlaybackInfo(
            durationSec = 120.0, positionSec = 30.5, rate = 1.0, readyToPlay = true,
        )

        val control = RtspVideoControl(
            onVideoPlay = { url, start -> played.add(url to start) },
            onVideoRate = { rates.add(it) },
            onVideoScrub = { scrubs.add(it) },
            onVideoStop = { stops++ },
            onPlaybackInfo = { info },
            urlModeEnabled = gate ?: { urlModeEnabled },
        )
    }

    private fun request(method: String, uri: String, body: String = "") = RtspRequest(
        method = method,
        uri = uri,
        headers = emptyMap(),
        body = body,
    )

    // ─── /play ────────────────────────────────────────────────────────────────

    @Test
    fun `play with a plist body extracts the URL and start position`() {
        val h = Harness()
        val bodyBytes = PlistCodec.encode(
            mapOf("Content-Location" to "http://example.com/movie.m3u8", "Start-Position" to 0.25)
        )
        val response = h.control.play(
            RtspRequest("POST", "/play", emptyMap(), body = "", bodyBytes = bodyBytes)
        )

        assertEquals(200, response.statusCode)
        assertEquals(listOf("http://example.com/movie.m3u8" to 0.25), h.played)
    }

    @Test
    fun `play with a legacy text body still extracts the URL`() {
        val h = Harness()
        val response = h.control.play(
            request("POST", "/play", "Content-Location: http://example.com/v.mp4\r\nStart-Position: 0.5\r\n")
        )

        assertEquals(200, response.statusCode)
        assertEquals(listOf("http://example.com/v.mp4" to 0.5), h.played)
    }

    @Test
    fun `play without a location is rejected and never reaches the player`() {
        val h = Harness()
        val response = h.control.play(request("POST", "/play", ""))

        assertEquals(400, response.statusCode)
        assertTrue(h.played.isEmpty())
    }

    // ─── Video URL mode disabled by settings (FR-60, FR-61) ───────────────────

    @Test
    fun `play is refused and never reaches the player when URL mode is disabled`() {
        val h = Harness(urlModeEnabled = false)
        val bodyBytes = PlistCodec.encode(mapOf("Content-Location" to "http://example.com/movie.m3u8"))

        val response = h.control.play(RtspRequest("POST", "/play", emptyMap(), "", bodyBytes))

        assertEquals("understood but refused by policy", 403, response.statusCode)
        assertTrue("the player must never be started", h.played.isEmpty())
    }

    @Test
    fun `URL mode enabled still plays — regression guard for the default path`() {
        val h = Harness()
        val bodyBytes = PlistCodec.encode(mapOf("Content-Location" to "http://example.com/movie.m3u8"))

        val response = h.control.play(RtspRequest("POST", "/play", emptyMap(), "", bodyBytes))

        assertEquals(200, response.statusCode)
        assertEquals(listOf("http://example.com/movie.m3u8" to 0.0), h.played)
    }

    @Test
    fun `URL mode switched off mid-session is honoured on the next request`() {
        // The gate is a callback, not a snapshot: a session opened while URL mode
        // was on must see the change immediately, otherwise turning the switch off
        // would only take effect after the sender reconnects.
        var enabled = true
        val h = Harness(gate = { enabled })
        val bodyBytes = PlistCodec.encode(mapOf("Content-Location" to "http://example.com/one.mp4"))

        assertEquals(200, h.control.play(RtspRequest("POST", "/play", emptyMap(), "", bodyBytes)).statusCode)
        enabled = false
        assertEquals(403, h.control.play(RtspRequest("POST", "/play", emptyMap(), "", bodyBytes)).statusCode)

        assertEquals("only the first request may reach the player", 1, h.played.size)
    }

    @Test
    fun `transport verbs keep answering while URL mode is disabled`() {
        // A sender whose /play was refused still polls these; answering 4xx for
        // them too would look like the session died rather than one feature off.
        val h = Harness(urlModeEnabled = false)

        assertEquals(200, h.control.rate(request("POST", "/rate?value=1")).statusCode)
        assertEquals(200, h.control.scrubGet(request("GET", "/scrub")).statusCode)
        assertEquals(200, h.control.stop(request("POST", "/stop")).statusCode)
    }

    // ─── /rate, /scrub, /stop ─────────────────────────────────────────────────

    @Test
    fun `rate parses the query value, defaulting to resume`() {
        val h = Harness()
        assertEquals(200, h.control.rate(request("POST", "/rate?value=0")).statusCode)
        assertEquals(0f, h.rates.last())
        h.control.rate(request("POST", "/rate"))
        assertEquals(1f, h.rates.last())
    }

    @Test
    fun `scrub POST seeks when a position is given`() {
        val h = Harness()
        assertEquals(200, h.control.scrubPost(request("POST", "/scrub?position=12.5")).statusCode)
        assertEquals(listOf(12.5), h.scrubs)
        h.control.scrubPost(request("POST", "/scrub"))
        assertEquals("no position → no seek", listOf(12.5), h.scrubs)
    }

    @Test
    fun `scrub GET returns duration and position as text parameters`() {
        val h = Harness()
        val response = h.control.scrubGet(request("GET", "/scrub"))

        assertEquals(200, response.statusCode)
        assertEquals("text/parameters", response.contentType)
        assertTrue(response.body.contains("duration: 120.000000"))
        assertTrue(response.body.contains("position: 30.500000"))
    }

    @Test
    fun `stop always answers 200 and increments the stop counter exactly once`() {
        val h = Harness()
        assertEquals(200, h.control.stop(request("POST", "/stop")).statusCode)
        assertEquals(1, h.stops)
    }

    // ─── /playback-info ───────────────────────────────────────────────────────

    @Test
    fun `playbackInfo for a ready player reports position, duration and rate`() {
        val h = Harness()
        val response = h.control.playbackInfo(request("GET", "/playback-info"))

        assertEquals("text/x-apple-plist+xml", response.contentType)
        val body = response.bodyBytes!!.decodeToString()
        assertTrue(body.contains("duration"))
        assertTrue(body.contains("120.0"))
        assertTrue(body.contains("readyToPlay"))
        assertTrue(body.contains("1.0"))
        assertFalse(body.contains("false</string>"))
    }

    @Test
    fun `playbackInfo with no player reports not-ready`() {
        val h = Harness()
        h.info = null
        val response = h.control.playbackInfo(request("GET", "/playback-info"))

        val body = response.bodyBytes!!.decodeToString()
        assertTrue(body.contains("readyToPlay"))
        assertTrue(body.contains("false"))
    }
}

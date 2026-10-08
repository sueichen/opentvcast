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
import tv.opentvcast.util.Logger

/**
 * The AirPlay URL-video transport: /play, /rate, /scrub, /stop, /playback-info.
 *
 * Distinct from mirroring (H.264 over a data stream): here the sender app hands
 * the receiver a URL, the TV plays the media itself, and the sender drives
 * transport over these verbs. Extracted from [RtspHandler] so the body-parsing
 * rules — plist with `Content-Location`, the legacy text fallback — have direct
 * tests instead of living inside the routing class.
 *
 * Stateless: the player is reached only through the four callbacks.
 */
internal class RtspVideoControl(
    private val onVideoPlay: (url: String, startFraction: Double) -> Unit,
    private val onVideoRate: (rate: Float) -> Unit,
    private val onVideoScrub: (positionSec: Double) -> Unit,
    private val onVideoStop: () -> Unit,
    private val onPlaybackInfo: () -> PlaybackInfo?,
    /**
     * Whether URL playback is allowed at all — the "Video URL mode" setting (FR-60).
     *
     * A **callback rather than a Boolean on purpose**: the switch can be flipped while a
     * session is open, and a value captured at construction would keep serving the old
     * answer until the sender reconnected. Reading it per request also makes the
     * mid-session case testable without a device.
     */
    private val urlModeEnabled: () -> Boolean = { true },
) {

    /** POST /play — a media URL to play (binary/XML plist or legacy text body). */
    fun play(request: RtspRequest): RtspResponse {
        if (!urlModeEnabled()) {
            // Refused, not ignored: answering 200 and starting nothing would leave the
            // sender showing a "playing" state that the user cannot explain. The other
            // verbs keep answering, so the refusal reads as one feature switched off
            // rather than a dead session.
            Logger.i("POST /play refused: video URL mode is disabled in settings (mirroring only)")
            return RtspResponse(403, "Forbidden", protocol = request.responseProtocol())
        }
        val (url, start) = parsePlayBody(request)
        if (url.isNullOrBlank()) {
            Logger.w("POST /play with no Content-Location")
            return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        Logger.i("POST /play url=$url start=$start")
        onVideoPlay(url, start)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** POST /rate?value=X — X=0 pause, X≥1 resume. Defaults to resume. */
    fun rate(request: RtspRequest): RtspResponse {
        val rate = queryParam(request.uri, "value")?.toFloatOrNull() ?: 1f
        Logger.d("POST /rate value=$rate")
        onVideoRate(rate)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** POST /scrub?position=N — seek to N seconds. A missing position is a no-op. */
    fun scrubPost(request: RtspRequest): RtspResponse {
        queryParam(request.uri, "position")?.toDoubleOrNull()?.let {
            Logger.d("POST /scrub position=$it")
            onVideoScrub(it)
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /scrub — current position + duration as text/parameters. */
    fun scrubGet(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val body = "duration: %.6f\r\nposition: %.6f\r\n".format(
            info?.durationSec ?: 0.0,
            info?.positionSec ?: 0.0,
        )
        return RtspResponse(
            200, "OK",
            body = body,
            contentType = "text/parameters",
            protocol = request.responseProtocol(),
        )
    }

    /** POST /stop — stop URL playback. */
    fun stop(request: RtspRequest): RtspResponse {
        Logger.i("POST /stop (video URL)")
        onVideoStop()
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /playback-info — XML plist describing current position/duration/rate/ready state. */
    fun playbackInfo(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val plist: Map<String, Any?> = if (info == null || !info.readyToPlay) {
            mapOf("readyToPlay" to false)
        } else {
            val ranges = listOf(mapOf("start" to 0.0, "duration" to info.durationSec))
            mapOf(
                "duration" to info.durationSec,
                "position" to info.positionSec,
                "rate" to info.rate,
                "readyToPlay" to true,
                "playbackBufferEmpty" to false,
                "playbackBufferFull" to true,
                "playbackLikelyToKeepUp" to true,
                "loadedTimeRanges" to ranges,
                "seekableTimeRanges" to ranges,
            )
        }
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(plist),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol(),
        )
    }

    companion object {

        /** Extracts the media URL + start fraction from a /play body (plist or legacy text). */
        internal fun parsePlayBody(request: RtspRequest): Pair<String?, Double> {
            if (request.isPlistBody()) {
                val p = runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull()
                    ?: return null to 0.0
                val url = p["Content-Location"] as? String
                val start = (p["Start-Position"] as? Double) ?: 0.0
                return url to start
            }
            // Legacy text body: "Content-Location: <url>\r\nStart-Position: <float>\r\n"
            var url: String? = null
            var start = 0.0
            request.body.lineSequence().forEach { line ->
                when {
                    line.startsWith("Content-Location:", true) -> url = line.substringAfter(":").trim()
                    line.startsWith("Start-Position:", true) ->
                        start = line.substringAfter(":").trim().toDoubleOrNull() ?: 0.0
                }
            }
            return url to start
        }

        /** Extracts a query-string parameter (`?k=v&...`) from a request URI. */
        internal fun queryParam(uri: String, key: String): String? =
            uri.substringAfter('?', "").split('&')
                .firstOrNull { it.substringBefore('=') == key }
                ?.substringAfter('=', "")
    }
}

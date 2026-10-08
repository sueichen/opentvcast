package tv.opentvcast.airplay

import tv.opentvcast.airplay.handshake.AppleIdentity
import tv.opentvcast.airplay.handshake.FairPlay
import tv.opentvcast.airplay.handshake.InfoResponder
import tv.opentvcast.airplay.handshake.PairingKeys
import tv.opentvcast.airplay.handshake.PairingSession
import tv.opentvcast.airplay.handshake.PlistCodec
import tv.opentvcast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * RtspHandler — Manages the RTSP session with the AirPlay sender (macOS).
 *
 * AirPlay uses RTSP to negotiate codecs, ports, and encryption before media flows.
 * The handler accepts one sender at a time, parses ANNOUNCE SDP, acknowledges SETUP
 * and RECORD, then hands binary interleaved RTP frames to [RtpInterleaved].
 */
open class RtspHandler(
    private val context: android.content.Context,
    private val displayWidth: Int = 1920,
    private val displayHeight: Int = 1080,
    private val audioEnabled: Boolean = false,
    private val videoSurfaceProvider: () -> android.view.Surface?,
    private val onStreamingStarted: (session: SessionDescription) -> Unit,
    private val onStreamingStopped: () -> Unit,
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    private val onPhotoCleared: () -> Unit = {},
    /**
     * AirPlay 2 mirror SETUP msg 1: supply decrypted AES key + pairing secret + the sender's
     * address and timing port (so the receiver can start NTP). Returns (eventPort, timingPort).
     */
    private val onMirrorSetupKeys: (
        aesKey: ByteArray, ecdhSecret: ByteArray, aesIv: ByteArray,
        remoteAddress: java.net.InetAddress, senderTimingPort: Int
    ) -> Pair<Int, Int> = { _, _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror SETUP: start the video data server (type 110); returns its data port. */
    private val onMirrorStreamStart: (streamConnectionId: Long) -> Int = { 0 },
    /** AirPlay 2 SETUP: start the audio server (type 96; ct 8 AAC-ELD mirror / 4 AAC-LC / 2 ALAC). spf = samples/frame. */
    private val onMirrorAudioStart: (sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int) -> Pair<Int, Int> = { _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror TEARDOWN of just the audio stream (type 96) — stop audio, keep video. */
    private val onMirrorAudioStop: () -> Unit = {},
    /** AirPlay 2 mirror TEARDOWN of just the video stream (type 110) — stop video, keep audio. */
    private val onMirrorVideoStop: () -> Unit = {},
    /** AirPlay 2 buffered audio-only SETUP (type 103, Apple Music → TV); returns the TCP data port. */
    private val onBufferedAudioStart: () -> Int = { 0 },
    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private val onBufferedAudioStop: () -> Unit = {},
    /** Sender volume change (AirPlay dB: −30…0, or ≤ −144 = mute) via SET_PARAMETER. */
    private val onVolume: (Float) -> Unit = {},
    /** Now-playing track metadata (DMAP) from SET_PARAMETER — any field may be null. */
    private val onNowPlayingMetadata: (title: String?, artist: String?, album: String?) -> Unit = { _, _, _ -> },
    /** Album artwork (JPEG/PNG bytes) from SET_PARAMETER; empty bytes = artwork cleared. */
    private val onArtwork: (ByteArray) -> Unit = {},
    /** AirPlay video URL mode: POST /play with a media URL + start fraction (0..1). */
    private val onVideoPlay: (url: String, startFraction: Double) -> Unit = { _, _ -> },
    /** AirPlay video transport: POST /rate (≤0 pause, >0 resume). */
    private val onVideoRate: (rate: Float) -> Unit = {},
    /** AirPlay video transport: POST /scrub — seek to position (seconds). */
    private val onVideoScrub: (positionSec: Double) -> Unit = {},
    /** AirPlay video transport: POST /stop — stop URL playback. */
    private val onVideoStop: () -> Unit = {},
    /** Current URL-video playback snapshot for GET /playback-info and GET /scrub. */
    private val onPlaybackInfo: () -> tv.opentvcast.airplay.PlaybackInfo? = { null },

    /**
     * Whether video URL playback is permitted (FR-60). Read per request, so turning the
     * setting off takes effect on the next `POST /play` without reconnecting.
     */
    private val urlModeEnabled: () -> Boolean = { true },
    /** Sender's DACP reverse-control identity from RTSP headers (DACP-ID + Active-Remote token). */
    private val onRemoteControlInfo: (dacpId: String?, activeRemote: String?) -> Unit = { _, _ -> },
    /** When true, require HomeKit-style SRP PIN pairing before streaming (gated by AppSettings). */
    private val pinAuthEnabled: Boolean = false,
    /** Persistent store of paired controllers' Ed25519 keys (for pair-verify). */
    private val pairingStore: tv.opentvcast.airplay.handshake.PairingStore? = null,
    /** Shows ([pin]) or hides (null) the on-screen pairing PIN during SRP pair-setup. */
    private val onShowPin: (pin: String?) -> Unit = {},
    /**
     * Port the RTSP server binds. This is the **granted** port — the value the port
     * allocator actually handed out — not the requested one, because mDNS advertises
     * whatever the handler binds. Defaults to the canonical AirPlay port.
     */
    private val rtspPort: Int = AirPlayPorts.RTSP,
    /** UDP timing port answered by the NTP handler and advertised in SETUP/SDP. */
    private val timingPort: Int = AirPlayPorts.TIMING,
    /** UDP audio RTP port advertised in the SETUP transport response. */
    private val audioPort: Int = AirPlayPorts.AUDIO_RTP,
) {

    // ─── Extracted collaborators ─────────────────────────────────────────────
    // Pairing and PIN state lives for the RECEIVER's lifetime (macOS runs the PIN
    // handshake across separate TCP connections), so `pairing` is built once.
    private val pairing = RtspPairing(
        pinAuthEnabled = pinAuthEnabled,
        attempts = pairingStore?.let { store ->
            object : RtspPairing.PairAttemptLimiter {
                override fun failedAttempts() = store.failedAttempts()
                override fun recordFailedAttempt() = store.recordFailedAttempt()
                override fun resetFailedAttempts() = store.resetFailedAttempts()
            }
        },
        onShowPin = onShowPin,
        sessionProvider = { pairingSession!! },
        serverEdPublic = { tv.opentvcast.airplay.handshake.PairingKeys.get(context).edPublic },
    )

    // URL-video transport: stateless, reaches the player only via callbacks.
    private val videoControl = RtspVideoControl(
        onVideoPlay = onVideoPlay,
        onVideoRate = onVideoRate,
        onVideoScrub = onVideoScrub,
        onVideoStop = onVideoStop,
        onPlaybackInfo = onPlaybackInfo,
        urlModeEnabled = urlModeEnabled,
    )

    // Volume/artwork/metadata: the remembered volume state lives in there now.
    private val nowPlaying = RtspNowPlayingControl(
        onVolume = onVolume,
        onArtwork = onArtwork,
        onNowPlayingMetadata = onNowPlayingMetadata,
    )

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeClient: Socket? = null

    @Volatile
    private var running = false

    private var currentCSeq: Int = 0

    @Volatile
    private var currentSession: SessionDescription? = null

    /** Per-connection AirPlay pairing state (pair-setup / pair-verify). */
    @Volatile
    private var pairingSession: PairingSession? = null

    /** Per-connection FairPlay state (fp-setup handshake + stream-key decrypt). */
    @Volatile
    private var fairPlay: FairPlay? = null

    /** Remote (sender) address of the active control connection — needed for AirPlay 2 NTP. */
    @Volatile
    private var currentRemoteAddress: java.net.InetAddress? = null

    /** True once an AirPlay 2 mirroring SETUP has run on this connection (no ANNOUNCE/SDP). */
    @Volatile
    private var isMirrorSession = false

    /** Mirror stream types currently active (96 = audio, 110 = video). Drives TEARDOWN routing.
     *  Concurrent because a TEARDOWN callback can race the RTSP loop that mutates it;
     *  `protected` so tests can seed it without driving the full FairPlay SETUP handshake. */
    protected val activeStreamTypes: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private var setupCount = 0

    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = PhotoHandler.MAX_PHOTO_BYTES
    )

    /**
     * Callback for decoded H.264 NAL units from the RTP stream.
     * Set by [AirPlayReceiver] after RECORD — wires to [VideoDecoder.decodeNalUnit].
     * Null for audio-only streams.
     */
    @Volatile
    var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)? = null

    /** Starts the RTSP server. */
    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    /** Stops the RTSP server. */
    fun stop() {
        running = false
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing RTSP sockets (non-fatal)", e)
        }
        activeClient = null
        serverSocket = null
        Logger.i("RTSP handler stopped")
    }

    /**
     * Binds the RTSP port with SO_REUSEADDR, retrying briefly if a just-stopped instance hasn't
     * released it yet. A quick service stop→start (the activity being destroyed and relaunched)
     * could otherwise fail with EADDRINUSE, leaving opentvcast advertising over mDNS while port 7000
     * was dead — macOS would discover it and try to mirror but nothing could connect ("casting but
     * nothing shows"). SO_REUSEADDR handles TIME_WAIT; the retry covers the close/rebind race.
     */
    private fun bindRtspSocket(): ServerSocket {
        var lastError: java.io.IOException? = null
        repeat(BIND_MAX_ATTEMPTS) { attempt ->
            if (!running) throw java.io.IOException("RTSP server stopped before bind")
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(rtspPort))
                }
            } catch (e: java.io.IOException) {
                lastError = e
                Logger.w("RTSP port $rtspPort busy (attempt ${attempt + 1}/$BIND_MAX_ATTEMPTS) — retrying in ${BIND_RETRY_MS}ms")
                try { Thread.sleep(BIND_RETRY_MS) } catch (_: InterruptedException) { throw e }
            }
        }
        throw lastError ?: java.io.IOException("RTSP bind to $rtspPort failed")
    }

    private fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = bindRtspSocket()
            Logger.i("RTSP server listening on port $rtspPort")

            while (running && scope.isActive) {
                val clientSocket = serverSocket!!.accept()
                Logger.i("New client connected: ${clientSocket.inetAddress.hostAddress}")

                if (activeClient != null && !activeClient!!.isClosed) {
                    Logger.w("Rejecting second client — already streaming")
                    RtspResponseWriter.writeServiceUnavailable(clientSocket)
                    clientSocket.close()
                    continue
                }

                activeClient = clientSocket
                handleClient(clientSocket)
            }
        } catch (e: Exception) {
            if (running) {
                Logger.e("RTSP server error (unexpected)", e)
            } else {
                Logger.d("RTSP server socket closed (expected during shutdown)")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val inputStream = socket.getInputStream()
        val outputStream = socket.getOutputStream()

        // Fresh pairing + FairPlay state for each control connection.
        pairingSession = PairingSession(PairingKeys.get(context))
        fairPlay = FairPlay()
        // NOTE: the PIN state inside `pairing` (RtspPairing) is deliberately NOT reset here. macOS runs the PIN handshake
        // across SEPARATE TCP connections (/pair-pin-start on one, /pair-setup-pin on the next), so the
        // PIN/verifier and the "paired" flag must survive a reconnect. They live for the receiver's
        // lifetime — replaced by the next /pair-pin-start, set on a successful pairing.
        currentRemoteAddress = socket.inetAddress

        try {
            while (running && !socket.isClosed) {
                val request = requestReader.read(inputStream) ?: break
                currentCSeq = request.headers["CSeq"]?.toIntOrNull() ?: 0
                val response = routeRequest(request)
                RtspResponseWriter.write(outputStream, response, currentCSeq)

                // After RECORD on a legacy SDP session: a session WITH video switches to interleaved
                // RTP (video arrives $-framed over this TCP socket). An audio-only session (e.g. Apple
                // Music) keeps the RTSP control loop — audio arrives on the UDP port, and macOS sends
                // now-playing metadata / volume / FLUSH / TEARDOWN as RTSP requests here that we must
                // keep handling (switching to interleaved mode would skip them → no metadata).
                if (request.method == "RECORD" && response.statusCode == 200 && !isMirrorSession &&
                    currentSession?.hasVideo == true) {
                    Logger.d("RTSP handshake complete — switching to interleaved RTP (video)")
                    break
                }
            }

            val session = currentSession
            if (session != null && session.hasVideo && running) {
                RtpInterleaved.readLoop(
                    inputStream = inputStream,
                    onVideoNalUnit = { nalUnit, ptsUs ->
                        onVideoNalUnit?.invoke(nalUnit, ptsUs)
                    },
                    onStreamEnded = {
                        Logger.i("RTP stream ended")
                    }
                )
            }
        } catch (e: Exception) {
            if (running) Logger.e("Error handling RTSP client", e)
        } finally {
            Logger.i("Client disconnected")
            socket.close()
            resetConnectionState()
            onStreamingStopped()
        }
    }

    /**
     * Clears every piece of per-connection state. Runs when a control
     * connection ends so the NEXT sender cannot inherit this sender's
     * address, session, or stream set (the remote address in particular
     * would otherwise leak into the next session's AirPlay 2 NTP setup).
     * Internal for tests.
     */
    internal fun resetConnectionState() {
        activeClient = null
        currentRemoteAddress = null
        currentSession = null
        pairingSession = null
        fairPlay = null
        isMirrorSession = false
        activeStreamTypes.clear()
        setupCount = 0
    }

    private fun routeRequest(request: RtspRequest): RtspResponse {
        Logger.d("RTSP ${request.method} ${request.uri}")
        // Senders attach their DACP reverse-control identity to most requests — capture it so the TV
        // remote can drive playback (DacpClient dedups, so this is cheap to call repeatedly).
        request.headers["Active-Remote"]?.let { onRemoteControlInfo(request.headers["DACP-ID"], it) }
        return when (request.method) {
            "OPTIONS"       -> handleOptionsInternal(request)
            "ANNOUNCE"      -> handleAnnounceInternal(request)
            // AirPlay 2 mirroring SETUP carries a binary plist; legacy audio SETUP carries SDP-ish text.
            "SETUP"         -> if (request.isPlistBody()) handleMirrorSetup(request) else handleSetupInternal(request)
            "RECORD"        -> handleRecordInternal(request)
            "TEARDOWN"      -> handleTeardownInternal(request)
            "GET_PARAMETER" -> nowPlaying.getParameter(request)
            "SET_PARAMETER" -> nowPlaying.setParameter(request)
            "FLUSH"         -> handleFlush(request)
            "PAUSE"         -> handlePauseInternal(request)
            // AirPlay 2 buffered-audio control verbs. Acknowledge them (a 501 would abort audio-only
            // playback) and log their bodies so the anchor/rate/peer formats can be implemented.
            "SETRATEANCHORTIME", "SETRATEANCHORTIM" -> handleBufferedControl(request, "SETRATEANCHORTIME")
            "SETPEERS", "SETPEERSX"                 -> handleBufferedControl(request, "SETPEERS")
            "FLUSHBUFFERED"                         -> handleBufferedControl(request, "FLUSHBUFFERED")
            "PUT"           -> handlePhotoPutInternal(request)
            "DELETE"        -> handlePhotoDeleteInternal(request)
            // AirPlay 2 handshake is HTTP-style (GET/POST with bodies) over the RTSP socket.
            "GET"           -> routeGet(request)
            "POST"          -> routePost(request)
            else            -> handleUnknownInternal(request)
        }
    }

    /** Routes AirPlay 2 GET requests by URI path. */
    private fun routeGet(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/info"          -> handleInfo(request)
        "/playback-info" -> videoControl.playbackInfo(request)
        "/scrub"         -> videoControl.scrubGet(request)
        "/server-info"   -> handleServerInfo(request)
        else             -> handleUnknownInternal(request)
    }

    /** Routes AirPlay 2 POST requests by URI path. */
    private fun routePost(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/pair-setup"  -> pairing.pairSetup(request)
        "/pair-setup-pin" -> pairing.pairSetupPin(request)       // legacy AirPlay PIN SRP (plist)
        "/pair-pin-start" -> pairing.pairPinStart(request)
        "/pair-verify" -> pairing.pairVerify(request)
        "/fp-setup"    -> handleFpSetup(request)
        "/feedback"    -> handleFeedback(request)
        "/audioMode"   -> RtspResponse(200, "OK", protocol = request.responseProtocol())
        // AirPlay video URL mode (non-mirroring): play a URL + drive transport.
        "/play"        -> videoControl.play(request)
        "/rate"        -> videoControl.rate(request)
        "/scrub"       -> videoControl.scrubPost(request)
        "/stop"        -> videoControl.stop(request)
        else           -> handleUnknownInternal(request)
    }

    /** GET /server-info — legacy XML plist of receiver identity for AirPlay video senders. */
    private fun handleServerInfo(request: RtspRequest): RtspResponse {
        val info = mapOf(
            "deviceid" to tv.opentvcast.util.NetworkUtils.getMacAddress(),
            "features" to AppleIdentity.FEATURES_MASK,
            "model" to AppleIdentity.MODEL,
            "protovers" to AppleIdentity.PROTOCOL_VERSION,
            "srcvers" to AppleIdentity.SOURCE_VERSION,
        )
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(info),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** POST /feedback — macOS health-checks the session every ~2 s; acknowledge with 200 OK. */
    private fun handleFeedback(request: RtspRequest): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("/feedback body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("/feedback body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * Acknowledges an AirPlay 2 buffered-audio control verb (SETRATEANCHORTIME / SETPEERS /
     * FLUSHBUFFERED). Returning 200 keeps an audio-only session alive (a 501 would make macOS abort).
     */
    private fun handleBufferedControl(request: RtspRequest, label: String): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("$label body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("$label body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /info — advertises receiver identity + capabilities (binary plist). */
    private fun handleInfo(request: RtspRequest): RtspResponse = RtspResponse(
        statusCode = 200,
        statusMessage = "OK",
        bodyBytes = InfoResponder.build(context, displayWidth, displayHeight, pinRequired = pinAuthEnabled),
        contentType = "application/x-apple-binary-plist",
        protocol = request.responseProtocol()
    )

    /** POST /fp-setup — FairPlay: 16-byte phase 1 → 142-byte reply; 164-byte phase 2 → 32-byte reply. */
    private fun handleFpSetup(request: RtspRequest): RtspResponse = try {
        val fp = fairPlay!!
        val b = request.bodyBytes
        // Diagnostics: byte 4 is the FairPlay version (0x03 mirroring/Safari, 0x02 Apple Music audio);
        // for phase 1, byte 14 is the mode (0..3). Confirms which path a given sender uses.
        val verMode = if (b.size >= 16) " v=0x%02x mode=%d".format(b[4].toInt() and 0xFF, b[14].toInt() and 0xFF)
                      else if (b.size >= 5) " v=0x%02x".format(b[4].toInt() and 0xFF) else ""
        val body = when (b.size) {
            16 -> fp.setup(b)
            164 -> fp.handshake(b)
            else -> throw IllegalArgumentException("unexpected fp-setup size ${b.size}")
        }
        Logger.i("fp-setup phase (${b.size}B in → ${body.size}B out)$verMode OK")
        RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
    } catch (e: Exception) {
        Logger.e("fp-setup failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /**
     * AirPlay 2 mirroring SETUP (binary plist). Two messages arrive on one connection:
     *  - msg 1 carries `ekey`+`eiv`+`timingPort` → FairPlay-decrypt the AES key, hand it
     *    (with the pairing secret) to the receiver, reply with event/timing ports.
     *  - msg 2 carries `streams`[type 110] → start the mirror data server, reply with its port.
     */
    private fun handleMirrorSetup(request: RtspRequest): RtspResponse = try {
        val req = PlistCodec.decode(request.bodyBytes)
        Logger.i("mirror SETUP plist: " + req.entries.joinToString { (k, v) ->
            "$k=" + when (v) {
                is ByteArray -> "${v.size}B"
                is List<*> -> "list[${v.size}]"
                else -> v.toString()
            }
        })
        val response = mutableMapOf<String, Any?>()

        isMirrorSession = true
        val ekey = req["ekey"] as? ByteArray
        if (ekey != null) {
            val aesKey = fairPlay!!.decrypt(ekey)
            val ecdhSecret = pairingSession?.sharedSecret ?: error("mirror SETUP before pair-verify")
            val aesIv = (req["eiv"] as? ByteArray) ?: ByteArray(16)
            val senderTimingPort = (req["timingPort"] as? Long)?.toInt() ?: 0
            val remoteAddr = currentRemoteAddress ?: error("mirror SETUP without remote address")
            val (eventPort, timingPort) = onMirrorSetupKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            response["eventPort"] = eventPort.toLong()
            response["timingPort"] = timingPort.toLong()
            Logger.i("mirror SETUP keys OK — eventPort=$eventPort timingPort=$timingPort (sender timing $senderTimingPort)")
        }

        val streams = req["streams"] as? List<*>
        if (streams != null) {
            val resStreams = streams.mapNotNull { s ->
                val stream = s as? Map<*, *> ?: return@mapNotNull null
                when ((stream["type"] as? Long)?.toInt()) {
                    110 -> {
                        val scid = (stream["streamConnectionID"] as? Long) ?: 0L
                        val dataPort = onMirrorStreamStart(scid)
                        activeStreamTypes.add(110)
                        Logger.i("mirror stream type=110 streamConnectionID=$scid dataPort=$dataPort")
                        mapOf("type" to 110L, "dataPort" to dataPort.toLong())
                    }
                    96 -> {
                        // Realtime-audio stream fields (codec type ct, samples-per-frame spf, latencies, …).
                        Logger.d("mirror stream type=96 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("mirror stream type=96 ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val sr = (stream["sr"] as? Long)?.toInt() ?: 44100
                        val ch = (stream["channels"] as? Long)?.toInt() ?: 2
                        val ct = (stream["ct"] as? Long)?.toInt() ?: 8   // 8 = AAC-ELD (mirror), 4 = AAC-LC, 2 = ALAC
                        val spf = (stream["spf"] as? Long)?.toInt() ?: 352   // ALAC frameLength (samples/frame)
                        val (dataPort, controlPort) = onMirrorAudioStart(sr, ch, ct, spf)
                        activeStreamTypes.add(96)
                        Logger.i("audio stream type=96 (ct=$ct ${sr}Hz x$ch spf=$spf) dataPort=$dataPort controlPort=$controlPort")
                        mapOf("type" to 96L, "dataPort" to dataPort.toLong(), "controlPort" to controlPort.toLong())
                    }
                    103 -> {
                        // Buffered (audio-only) AirPlay 2 — accepted + instrumented, but the macOS
                        // Music stream stays FairPlay-encrypted (undecryptable), so playback is not
                        // wired. Stream fields (codec ct, audioFormat, shk/shiv, latencies) logged for ref.
                        Logger.d("buffered audio stream type=103 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("buffered audio (type=103) ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val dataPort = onBufferedAudioStart()
                        activeStreamTypes.add(103)
                        Logger.i("buffered audio stream type=103 dataPort=$dataPort")
                        mapOf("type" to 103L, "dataPort" to dataPort.toLong())
                    }
                    else -> {
                        Logger.i("mirror SETUP stream dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; else -> v.toString() }
                        })
                        null
                    }
                }
            }
            response["streams"] = resStreams
        }

        RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encode(response),
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    } catch (e: Exception) {
        Logger.e("mirror SETUP failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /** Handles OPTIONS — macOS asks what RTSP methods are supported. */
    open fun handleOptionsInternal(request: RtspRequest): RtspResponse {
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf(
                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            )
        )
    }

    /** Handles ANNOUNCE — macOS/iOS sends SDP describing codecs, ports, and encryption. */
    open fun handleAnnounceInternal(request: RtspRequest): RtspResponse {
        Logger.d("ANNOUNCE body (${request.body.length} bytes)")
        val parsed = SdpParser.parse(request.body)

        if (parsed == null) {
            Logger.e("ANNOUNCE: SDP parsing returned no usable session — rejecting")
            return RtspResponse(statusCode = 400, statusMessage = "Bad Request")
        }

        currentSession = parsed.copy(senderName = extractSenderName(request.headers["User-Agent"]))
        val s = currentSession!!
        Logger.i("Session: hasVideo=${s.hasVideo} hasAudio=${s.hasAudio} " +
                 "codec=${s.audioCodec} encrypted=${s.isAudioEncrypted} sender='${s.senderName}'")

        setupCount = 0
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    private fun extractSenderName(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return DEFAULT_SENDER_NAME
        val name = userAgent.substringBefore("/").trim()
        return name.ifEmpty { DEFAULT_SENDER_NAME }
    }

    /** Handles SETUP — allocates a media channel. */
    open fun handleSetupInternal(request: RtspRequest): RtspResponse {
        setupCount++
        val session = currentSession

        val isVideoSetup = setupCount == 1 && session?.hasVideo == true

        val transport = if (isVideoSetup) {
            "RTP/AVP/TCP;unicast;interleaved=0-1"
        } else {
            "RTP/AVP/UDP;unicast;" +
            "client_port=$audioPort-${audioPort + 1};" +
            "server_port=$audioPort-${audioPort + 1};" +
            "timing-port=$timingPort"
        }

        Logger.d("SETUP #$setupCount — transport: $transport")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Session" to SESSION_ID, "Transport" to transport)
        )
    }

    /** Handles RECORD — macOS/iOS says start sending media now. */
    open fun handleRecordInternal(request: RtspRequest): RtspResponse {
        // AirPlay 2 mirroring has no ANNOUNCE/SDP — RECORD just acknowledges the session.
        if (isMirrorSession) {
            Logger.i("RECORD (mirror session) — OK")
            return RtspResponse(
                statusCode = 200, statusMessage = "OK",
                headers = mapOf("Audio-Latency" to "0"),
                protocol = request.responseProtocol()
            )
        }
        var session = currentSession
        if (session == null) {
            Logger.e("RECORD received but no session from ANNOUNCE — rejecting")
            return RtspResponse(statusCode = 455, statusMessage = "Method Not Valid in This State")
        }
        // RAOP audio (Apple Music) wraps the AES key with FairPlay (SDP `fpaeskey`). Unwrap it via the
        // fp-setup session into the real 16-byte key so the AudioPlayer can AES-CBC-decrypt the stream.
        val fpKey = session.fpAesKey
        if (fpKey != null && session.aesKey == null) {
            val realKey = runCatching { fairPlay?.decrypt(fpKey) }
                .onFailure { Logger.w("RAOP FairPlay audio-key decrypt failed (${fpKey.size}B): ${it.message}") }
                .getOrNull()
            if (realKey != null) {
                Logger.i("RAOP FairPlay (v0x%02x) audio key decrypted → ${realKey.size}B AES key, iv=${session.aesIv?.size ?: 0}B"
                    .format(fairPlay?.negotiatedVersion ?: 0))
                session = session.copy(aesKey = realKey)
                currentSession = session
            }
        }
        Logger.i("RECORD — streaming starting (audioOnly=${session.isAudioOnly}, encrypted=${session.isAudioEncrypted})")
        onStreamingStarted(session)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles TEARDOWN. A TEARDOWN may target SPECIFIC streams (AirPlay 2 dynamic stream removal —
     * e.g. macOS drops the audio stream when playback stops) or the whole session. If the body lists
     * streams and they're audio-only, we stop just the audio and KEEP the mirror running; otherwise
     * we tear the whole session down. (Previously any TEARDOWN killed the mirror, so stopping audio
     * on the Mac ended screen mirroring entirely.)
     */
    open fun handleTeardownInternal(request: RtspRequest): RtspResponse {
        val streamTypes = parseTeardownStreamTypes(request.bodyBytes)
        if (streamTypes != null && streamTypes.isNotEmpty()) {
            // Stream-level teardown: stop ONLY the listed streams. Keep the session (keys, NTP,
            // event channel) alive so the remaining stream keeps running and a stopped one can be
            // re-added later — e.g. audio keeps playing with video gone, or video keeps mirroring
            // with audio stopped. But if this removes the LAST active stream (e.g. macOS names both
            // 96 and 110 to end the session), fall through to a full teardown so cleanup isn't left
            // to the eventual socket close.
            if (streamTypes.contains(96)) { onMirrorAudioStop(); activeStreamTypes.remove(96) }
            if (streamTypes.contains(110)) { onMirrorVideoStop(); activeStreamTypes.remove(110) }
            if (streamTypes.contains(103)) { onBufferedAudioStop(); activeStreamTypes.remove(103) }
            if (activeStreamTypes.isNotEmpty()) {
                Logger.i("TEARDOWN streams=$streamTypes — stopped those, session continues (active=$activeStreamTypes)")
                return RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
            }
            Logger.i("TEARDOWN streams=$streamTypes — last stream removed, ending session")
        } else {
            Logger.i("TEARDOWN (session, body=${request.bodyBytes.size}B) — streaming stopping")
        }
        activeStreamTypes.clear()
        onStreamingStopped()
        return RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
    }

    /** Parses the `streams` list from a TEARDOWN body, returning the stream `type`s, or null. */
    private fun parseTeardownStreamTypes(body: ByteArray): List<Int>? = runCatching {
        if (body.isEmpty()) return null
        val streams = PlistCodec.decode(body)["streams"] as? List<*> ?: return null
        streams.mapNotNull { ((it as? Map<*, *>)?.get("type") as? Long)?.toInt() }
    }.getOrNull()

    /** Handles any unrecognized RTSP method. */
    open fun handleUnknownInternal(request: RtspRequest): RtspResponse {
        Logger.w("Unknown/unhandled RTSP: ${request.method} ${request.uri} (${request.bodyBytes.size}B body)")
        return RtspResponse(statusCode = 501, statusMessage = "Not Implemented", protocol = request.responseProtocol())
    }

    /** Handles FLUSH — macOS requests we discard buffered media data (seek/pause). */
    private fun handleFlush(@Suppress("UNUSED_PARAMETER") request: RtspRequest): RtspResponse {
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles PAUSE — suspends media delivery. Responds 200 OK; resume arrives as RECORD. */
    open fun handlePauseInternal(request: RtspRequest): RtspResponse {
        Logger.d("PAUSE received")
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles AirPlay photo sharing: HTTP `PUT /photo` with a JPEG/PNG body. */
    open fun handlePhotoPutInternal(request: RtspRequest): RtspResponse {
        if (!request.isPhotoRequest()) {
            return handleUnknownInternal(request)
        }

        return when (val validation = PhotoHandler.validatePhoto(
            request.bodyBytes,
            request.headers["Content-Type"]
        )) {
            is PhotoValidation.Valid -> {
                onPhotoReceived(request.bodyBytes, validation.imageType)
                Logger.i("Photo received (${validation.imageType.mimeType}, ${request.bodyBytes.size} bytes)")
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    protocol = request.responseProtocol()
                )
            }
            is PhotoValidation.Invalid -> {
                Logger.w("Photo rejected: ${validation.reason}")
                RtspResponse(
                    statusCode = 400,
                    statusMessage = "Bad Request",
                    protocol = request.responseProtocol()
                )
            }
        }
    }

    /** Handles AirPlay photo clearing: HTTP `DELETE /photo`. */
    open fun handlePhotoDeleteInternal(request: RtspRequest): RtspResponse {
        if (!request.isPhotoRequest()) {
            return handleUnknownInternal(request)
        }

        onPhotoCleared()
        Logger.i("Photo cleared")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            protocol = request.responseProtocol()
        )
    }

    companion object {
        private const val BIND_MAX_ATTEMPTS = 12      // ~3s total — covers a quick stop→start restart
        private const val BIND_RETRY_MS = 250L
        private const val MAX_MESSAGE_BYTES = 65536
        private const val OCTET_STREAM = "application/octet-stream"
        private const val SESSION_ID = "OpenTvCastSession"

        /**
         * Generic display name when the sender never identifies itself. Public because
         * [tv.opentvcast.service.CastService] needs the same fallback before CONNECTED fires —
         * a second literal there would risk the two disagreeing.
         */
        const val DEFAULT_SENDER_NAME = "AirPlay Sender"
    }
}

private fun RtspRequest.isPhotoRequest(): Boolean =
    uri.substringBefore("?") == PhotoHandler.PHOTO_PATH


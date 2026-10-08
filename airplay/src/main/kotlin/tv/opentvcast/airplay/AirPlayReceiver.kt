package tv.opentvcast.airplay

import android.content.Context
import android.view.Surface
import tv.opentvcast.airplay.handshake.AirPlayNtpClient
import tv.opentvcast.airplay.handshake.AudioStreamServer
import tv.opentvcast.airplay.handshake.BufferedAudioServer
import tv.opentvcast.airplay.handshake.MirrorStreamServer
import tv.opentvcast.core.protocol.ProtocolState
import tv.opentvcast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket

/**
 * AirPlayReceiver — Top-level orchestrator for the AirPlay 2 receiver pipeline.
 *
 * WHY: Coordinates all AirPlay components into a single lifecycle:
 * - [MdnsService]: mDNS advertising (makes device visible in sender pickers)
 * - [RtspHandler]: RTSP handshake (OPTIONS → ANNOUNCE → SETUP → RECORD)
 * - [VideoDecoder]: H.264 hardware decode via MediaCodec → SurfaceView
 * - [AudioPlayer]: AES-128-CTR decrypt + AAC/ALAC decode → AudioTrack
 *
 * HOW: [CastService] creates this receiver and calls [start]/[stop].
 * The pipeline activates lazily — VideoDecoder and AudioPlayer are created
 * only after RECORD is received, when [SessionDescription] is available.
 *
 * For audio-only streams (music, podcasts), only [AudioPlayer] is started —
 * no [VideoDecoder] and no fullscreen streaming surface is needed.
 *
 * State changes are reported via [onStateChanged] to [CastService].
 *
 * Example:
 *   val receiver = AirPlayReceiver(
 *       context = context,
 *       displayName = settings.effectiveDisplayName,
 *       videoSurfaceProvider = { streamingScreen.getSurface() },
 *       onStateChanged = { state -> /* update UI */ }
 *   )
 *   receiver.start()
 *   receiver.stop()
 */
class AirPlayReceiver(
    private val context: Context,
    /** User-configured display name from Settings (blank = use system device name). */
    private val displayName: String = "",
    /** Advertised mirroring resolution (from the "high resolution" setting). */
    private val mirrorWidth: Int = 1920,
    private val mirrorHeight: Int = 1080,
    /** Whether to accept the mirroring audio stream (experimental — see AppSettings.mirrorAudioEnabled). */
    private val audioEnabled: Boolean = false,
    /** Require HomeKit-style SRP PIN pairing before streaming (AppSettings.airPlayPinAuthEnabled). */
    private val pinAuthEnabled: Boolean = false,

    /**
     * Whether AirPlay video URL playback is permitted (FR-60). Mirrors the "Video URL
     * mode" setting; update it live through [setVideoUrlModeEnabled].
     */
    videoUrlModeEnabled: Boolean = true,
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    /**
     * Called with the sender name when a streaming session starts (RECORD received).
     *
     * The name is extracted from the RTSP `User-Agent` header. The caller
     * ([CastService]) uses this to update the [ActiveConnection] and notification
     * text with the real sender identifier instead of the generic "AirPlay Sender".
     *
     * Guaranteed to be called BEFORE [onStateChanged] is called with [ProtocolState.CONNECTED].
     */
    private val onSenderNameChanged: (String) -> Unit = {},
    /** Called when iOS/macOS sends a JPEG/PNG to the `/photo` endpoint. */
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    /** Called when iOS/macOS clears the currently displayed `/photo`. */
    private val onPhotoCleared: () -> Unit = {},
    /**
     * Called with the actual mDNS-registered name after [start].
     *
     * The name may differ from [displayName] if another device on the network already uses
     * the same name — NsdManager resolves the collision by appending " (2)", " (3)", etc.
     * The UI can use this callback to show the user the real registered name.
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    /**
     * Audio-only "now playing" state. Emits a [NowPlayingInfo] when audio is streaming WITHOUT video
     * (system audio, Apple Music, podcasts) so the UI can show a now-playing card instead of a black
     * surface; emits null when video is mirroring (the video screen takes over) or audio stops.
     */
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    /** Pairing PIN to show ([pin]) or hide (null) on the TV during SRP pair-setup. */
    private val onPinChanged: (pin: String?) -> Unit = {},
    /**
     * Granted ports, not requested ones. The service layer asks the port allocator
     * and passes what it actually got; these values are both bound and advertised,
     * so the two can never drift. Defaults keep the canonical AirPlay ports.
     */
    private val rtspPort: Int = AirPlayPorts.RTSP,
    private val timingPort: Int = AirPlayPorts.TIMING,
    private val audioPort: Int = AirPlayPorts.AUDIO_RTP,
) {

    // Persistent store of paired controllers (for PIN access control / pair-verify).
    private val pairingStore = tv.opentvcast.airplay.handshake.PairingStore(context)

    // SupervisorJob: child coroutine failures don't propagate to siblings.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Child components
    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var timingHandler: TimingHandler? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer: AudioPlayer? = null

    // UDP socket for receiving audio RTP packets — opened after RECORD, closed on TEARDOWN
    @Volatile private var audioSocket: DatagramSocket? = null

    // AirPlay 2 mirroring: data stream server + event channel + keys (set during SETUP).
    @Volatile private var mirrorServer: MirrorStreamServer? = null
    @Volatile private var audioServer: AudioStreamServer? = null
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null

    /** Live view of the "Video URL mode" setting; read per request by [RtspHandler]. */
    @Volatile private var videoUrlModeEnabled: Boolean = videoUrlModeEnabled

    // Reverse remote control (TV → sender). Created lazily once a sender advertises DACP-ID.
    private val dacpClient = DacpClient(context)
    @Volatile private var ntpClient: AirPlayNtpClient? = null
    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: java.net.Socket? = null
    @Volatile private var mirrorAesKey: ByteArray? = null
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorAesIv: ByteArray? = null

    // ─── Now-playing (audio-only) state ──────────────────────────────────────
    // The now-playing card shows only when audio plays WITHOUT video. The whole
    // decision — stream kinds, DMAP metadata/artwork lifecycle, and the emit
    // value — lives in [NowPlayingState]; every transition hands back exactly
    // what must be forwarded to [onNowPlayingChanged], so a mutation can no
    // longer forget to recompute the card.
    private val nowPlaying = NowPlayingState()

    /**
     * Starts the AirPlay receiver.
     *
     * 1. Starts mDNS advertising with the configured display name.
     * 2. Opens the RTSP server socket (port 7000).
     * 3. Emits [ProtocolState.ADVERTISING] once both mDNS services are registered.
     *
     * Non-blocking — all network work runs in background coroutines.
     */
    fun start() {
        Logger.i("AirPlayReceiver starting (displayName='$displayName')")
        scope.launch {
            try {
                startTimingHandler()
                startMdnsService()
                startRtspHandler()
            } catch (e: Exception) {
                Logger.e("Failed to start AirPlayReceiver", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Stops the AirPlay receiver and releases all resources.
     *
     * Stops RTSP handler, mDNS advertising, video decoder, and audio player.
     * Cancels all background coroutines.
     *
     * MUST be called when [CastService] stops or is destroyed.
     */
    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        try {
            rtspHandler?.stop()
            timingHandler?.stop()
            mdnsService?.stop()
            dacpClient.stop()
            releaseMediaComponents()
            // Clear the debug-overlay HUD so the next session does not show this
            // session's stale counters while it warms up.
            StreamStats.resetStreams()
        } catch (e: Exception) {
            Logger.e("Error during AirPlayReceiver stop", e)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Sends a DACP transport command (see [DacpClient] constants) from the TV remote back to the
     * AirPlay sender — e.g. play/pause or skip what the Mac/iPhone is streaming. No-op if no sender
     * has advertised a DACP identity yet.
     */
    fun sendRemoteCommand(command: String) = dacpClient.sendCommand(command)

    // ─── Private: startup ────────────────────────────────────────────────────

    private fun startTimingHandler() {
        timingHandler = TimingHandler().also { it.start(scope, port = timingPort) }
        Logger.d("Timing handler started on UDP port $timingPort")
    }

    private fun startMdnsService() {
        mdnsService = MdnsService(
            context = context,
            onStateChange = { state -> emitState(state) },
            onActualNameRegistered = { actualName -> onActualNameRegistered(actualName) },
            advertisedPort = rtspPort
        ).also { it.start(displayName.ifBlank { null }) }
        Logger.d("mDNS service started (advertised port $rtspPort)")
    }

    private fun startRtspHandler() {
        rtspHandler = RtspHandler(
            context = context,
            displayWidth = mirrorWidth,
            displayHeight = mirrorHeight,
            audioEnabled = audioEnabled,
            videoSurfaceProvider = videoSurfaceProvider,
            onStreamingStarted = { session -> onStreamingStarted(session) },
            onStreamingStopped = { onStreamingStopped() },
            onPhotoReceived = { bytes, imageType -> onPhotoReceived(bytes, imageType) },
            onPhotoCleared = { onPhotoCleared() },
            onMirrorSetupKeys = { aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort ->
                startMirrorKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            },
            onMirrorStreamStart = { streamConnectionId -> startMirrorStream(streamConnectionId) },
            onMirrorAudioStart = { sampleRate, channels, ct, spf -> startMirrorAudio(sampleRate, channels, ct, spf) },
            onMirrorAudioStop = { stopMirrorAudio() },
            onMirrorVideoStop = { stopMirrorVideo() },
            onBufferedAudioStart = { startBufferedAudio() },
            onBufferedAudioStop = { stopBufferedAudio() },
            onVolume = { v -> audioServer?.setVolume(v) },
            onNowPlayingMetadata = { title, artist, album ->
                onNowPlayingChanged(nowPlaying.onMetadata(title, artist, album))
            },
            onArtwork = { bytes ->
                onNowPlayingChanged(nowPlaying.onArtwork(bytes))
            },
            onVideoPlay = { url, start, referer -> startUrlVideo(url, start, referer) },
            onVideoRate = { rate -> urlVideoPlayer?.setRate(rate) },
            onVideoScrub = { pos -> urlVideoPlayer?.scrub(pos) },
            onVideoStop = { stopUrlVideo() },
            onPlaybackInfo = { urlVideoPlayer?.info() },
            urlModeEnabled = { videoUrlModeEnabled },
            onRemoteControlInfo = { dacpId, activeRemote -> dacpClient.configure(dacpId, activeRemote) },
            pinAuthEnabled = pinAuthEnabled,
            pairingStore = pairingStore,
            onShowPin = { pin -> onPinChanged(pin) },
            rtspPort = rtspPort,
            timingPort = timingPort,
            audioPort = audioPort
        ).also { it.start(scope) }
        Logger.i("RTSP handler started on port $rtspPort (audioEnabled=$audioEnabled pinAuth=$pinAuthEnabled)")
    }

    // ─── Private: streaming lifecycle ────────────────────────────────────────

    /**
     * Called by [RtspHandler] when RECORD is received and [SessionDescription] is ready.
     *
     * Wires the media pipeline:
     * - video stream: creates [VideoDecoder] + wires [RtspHandler.onVideoNalUnit]
     * - audio stream: creates [AudioPlayer]
     * - audio-only:   only [AudioPlayer], app stays on HomeScreen
     */
    private fun onStreamingStarted(session: SessionDescription) {
        Logger.i("Streaming started — video=${session.hasVideo} audio=${session.hasAudio} " +
                 "audioOnly=${session.isAudioOnly}")

        scope.launch {
            try {
                if (session.hasVideo) startVideoDecoder(session)
                if (session.hasAudio) startAudioPlayer(session)
                // Legacy (SDP) session: reflect its stream kinds into now-playing state so an
                // audio-only RAOP session shows the now-playing card.
                onNowPlayingChanged(
                    nowPlaying.onAnnounce(session.senderName, session.hasVideo, session.hasAudio)
                )
                // Notify CastService of the sender name BEFORE emitting CONNECTED,
                // so the name is ready when the ActiveConnection is created.
                onSenderNameChanged(session.senderName)
                emitState(ProtocolState.CONNECTED)
            } catch (e: Exception) {
                Logger.e("Failed to start media pipeline", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Called when streaming ends (TEARDOWN received or socket closed).
     *
     * Releases media components and re-advertises so the device reappears
     * in sender pickers immediately.
     */
    private fun onStreamingStopped() {
        Logger.i("Streaming stopped — releasing media components")
        releaseMediaComponents()
        emitState(ProtocolState.ADVERTISING)

        scope.launch {
            try {
                mdnsService?.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                Logger.e("Failed to restart mDNS after streaming", e)
            }
        }
    }

    // ─── Private: media pipeline ──────────────────────────────────────────────

    /**
     * Initializes [VideoDecoder] with SPS/PPS from the [SessionDescription].
     *
     * Resolution hint: AirPlay SDP does not include width/height — the actual
     * resolution is embedded in the SPS NAL unit. We pass [DEFAULT_VIDEO_WIDTH] ×
     * [DEFAULT_VIDEO_HEIGHT] as a hint; MediaCodec reads the real size from SPS.
     *
     * [RtspHandler.onVideoNalUnit] is wired here so RTP interleaved NAL units
     * flow directly into [VideoDecoder.decodeNalUnit].
     */
    private fun startVideoDecoder(session: SessionDescription) {
        val surface = videoSurfaceProvider() ?: run {
            Logger.w("VideoDecoder: no surface available — skipping video pipeline")
            return
        }
        val sps = session.spsBytes ?: run {
            Logger.w("VideoDecoder: no SPS in SDP — skipping")
            return
        }
        val pps = session.ppsBytes ?: run {
            Logger.w("VideoDecoder: no PPS in SDP — skipping")
            return
        }

        videoDecoder = VideoDecoder(surface).also { decoder ->
            decoder.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
            rtspHandler?.onVideoNalUnit = { nalUnit, ptsUs ->
                decoder.decodeNalUnit(nalUnit, ptsUs)
            }
        }
        Logger.i("VideoDecoder started (${DEFAULT_VIDEO_WIDTH}x${DEFAULT_VIDEO_HEIGHT} hint)")
    }

    /**
     * Initializes [AudioPlayer] with codec and encryption params from [SessionDescription].
     *
     * When the SDP contains no AES key/IV (unencrypted or missing keys), null is passed —
     * [AudioPlayer.initialize] skips cipher setup entirely and writes audio payload directly.
     * This prevents a zero-key cipher from producing garbage audio (S6-4 fix).
     */
    private fun startAudioPlayer(session: SessionDescription) {
        audioPlayer = AudioPlayer().also { player ->
            player.initialize(
                aesKey     = session.aesKey.takeIf { session.isAudioEncrypted },
                aesIv      = session.aesIv.takeIf  { session.isAudioEncrypted },
                sampleRate = session.sampleRate,
                channels   = session.channels,
                codec      = session.audioCodec,
                alacFramesPerPacket = session.alacFramesPerPacket
            )
        }
        Logger.i("AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                 "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})")

        startAudioUdpReceiver()
    }

    /**
     * Opens a UDP socket on [AUDIO_RTP_PORT] and feeds every received packet to
     * [AudioPlayer.playAudioPacket].
     *
     * WHY UDP: AirPlay audio is sent as RTP over UDP — low latency is more important
     * than guaranteed delivery. A missing packet produces a brief audio glitch,
     * which is far less disruptive than the buffering delays that TCP would introduce.
     *
     * The socket is closed in [releaseMediaComponents] when streaming ends.
     */
    private fun startAudioUdpReceiver() {
        scope.launch(Dispatchers.IO) {
            // Bind failures are their own failure mode: a fast stop→start restart can
            // race the OS releasing the port. Logging this as "expected shutdown noise"
            // (the old audioSocket != null heuristic did) hides a receiver that is up
            // but permanently deaf to audio, so it gets its own loud, honest error.
            val socket = try {
                DatagramSocket(AirPlayPorts.AUDIO_RTP)
            } catch (e: Exception) {
                Logger.e("Audio UDP bind failed on port ${AirPlayPorts.AUDIO_RTP} — audio will not work", e)
                return@launch
            }
            audioSocket = socket
            Logger.i("Audio UDP receiver listening on port ${AirPlayPorts.AUDIO_RTP}")

            try {
                val buf    = ByteArray(MAX_AUDIO_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive) {
                    socket.receive(packet)
                    // copyOf trims to actual packet length before passing to the player
                    audioPlayer?.playAudioPacket(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                // Classify on the LOCAL socket's state, not on the audioSocket field:
                // releaseMediaComponents closes the socket BEFORE nulling the field, so
                // the field-based check misreported every normal shutdown as an
                // unexpected error. A closed local socket (or a cancelled scope) is the
                // shutdown path; anything else is a genuine, loggable failure.
                if (socket.isClosed || !isActive) {
                    Logger.d("Audio socket closed (expected during shutdown)")
                } else {
                    Logger.e("Audio UDP receiver error (unexpected)", e)
                }
            }
        }
    }

    // ─── Private: AirPlay 2 mirroring ─────────────────────────────────────────

    /**
     * Mirror SETUP msg 1: stash the decrypted AES key + pairing secret, open the event
     * channel (macOS connects to it), and switch the UI to the streaming surface.
     * @return the event channel's TCP port.
     */
    private fun startMirrorKeys(
        aesKey: ByteArray,
        ecdhSecret: ByteArray,
        aesIv: ByteArray,
        remoteAddress: java.net.InetAddress,
        senderTimingPort: Int,
    ): Pair<Int, Int> {
        mirrorAesKey = aesKey
        mirrorEcdhSecret = ecdhSecret
        mirrorAesIv = aesIv
        val event = ServerSocket(0)
        eventSocket = event
        // Accept + drain the event connection. We don't act on events yet, but macOS expects
        // the advertised event port to be connectable, so keep it open and readable.
        scope.launch(Dispatchers.IO) {
            try {
                event.accept().use { s ->
                    eventClientSocket = s
                    Logger.i("Event channel: macOS connected from ${s.inetAddress.hostAddress}")
                    val buf = ByteArray(4096)
                    val input = s.getInputStream()
                    while (isActive && input.read(buf) != -1) { /* drain */ }
                }
            } catch (e: Exception) {
                if (eventSocket != null) Logger.d("Event channel closed")
            } finally {
                eventClientSocket = null
            }
        }
        // AirPlay 2 NTP is receiver-initiated: poll the sender's timing port so macOS proceeds.
        val ntp = AirPlayNtpClient(remoteAddress, senderTimingPort).also { ntpClient = it; it.start(scope) }
        onSenderNameChanged("AirPlay")
        emitState(ProtocolState.CONNECTED)
        Logger.i("Mirror keys set; eventPort=${event.localPort} timingPort=${ntp.localPort}")
        return event.localPort to ntp.localPort
    }

    /**
     * Mirror SETUP msg 2: start the data-stream server for the requested stream.
     * @return the data server's TCP port (macOS connects here to send H.264).
     */
    private fun startMirrorStream(streamConnectionId: Long): Int {
        val aesKey = mirrorAesKey ?: run { Logger.e("mirror stream start before keys set"); return 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0
        return MirrorStreamServer(aesKey, ecdhSecret, streamConnectionId, videoSurfaceProvider, mirrorWidth, mirrorHeight)
            .also {
                mirrorServer = it
                it.start(scope)
                onNowPlayingChanged(nowPlaying.onMirrorStarted())
            }
            .dataPort
            .also { Logger.i("Mirror data server started on port $it") }
    }

    /** Mirror SETUP audio stream (type 96): start the AAC-ELD / AAC-LC / ALAC audio server. @return (dataPort, controlPort). */
    private fun startMirrorAudio(sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int): Pair<Int, Int> {
        val aesKey = mirrorAesKey ?: run { Logger.e("audio start before keys set"); return 0 to 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0 to 0
        val aesIv = mirrorAesIv ?: return 0 to 0
        val server = AudioStreamServer(aesKey, ecdhSecret, aesIv, sampleRate, channels, codecType, framesPerPacket)
            .also { audioServer = it; it.start(scope) }
        onNowPlayingChanged(nowPlaying.onAudioStarted())
        Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
        return server.dataPort to server.controlPort
    }

    /** Stops ONLY the mirror audio stream (macOS dynamic-stream TEARDOWN) — video keeps running. */
    private fun stopMirrorAudio() {
        audioServer?.stop()
        audioServer = null
        onNowPlayingChanged(nowPlaying.onAudioStopped())
        Logger.i("Mirror audio stream stopped (video mirroring continues)")
    }

    /** Stops ONLY the mirror video stream (macOS dynamic-stream TEARDOWN) — audio keeps playing. */
    private fun stopMirrorVideo() {
        mirrorServer?.stop()
        mirrorServer = null
        // audio may still be playing → now-playing card can take over
        onNowPlayingChanged(nowPlaying.onVideoStopped())
        Logger.i("Mirror video stream stopped (audio playback continues)")
    }

    /**
     * AirPlay video URL mode (non-mirroring): show the streaming surface and hand the URL to
     * [AirPlayVideoPlayer], which fetches + plays it via MediaPlayer onto the same Surface.
     *
     * Mirroring is stopped first. Both producers write to the *same* Surface, and a sender that
     * hands over a video has normally torn its mirror streams down already — this makes the
     * exclusion true even when it has not, instead of leaving two writers racing for one buffer.
     * [referer] is the sender's `Referer` for the media request, when it supplied one.
     */
    private fun startUrlVideo(url: String, startFraction: Double, referer: String?) {
        if (mirrorServer != null) {
            Logger.i("URL playback requested — stopping the mirror video stream (one Surface, one producer)")
            stopMirrorVideo()
        }
        if (audioServer != null) {
            Logger.i("URL playback requested — stopping the mirror audio stream")
            stopMirrorAudio()
        }
        onSenderNameChanged("AirPlay")
        emitState(ProtocolState.CONNECTED)   // shows StreamingScreen → Surface becomes available
        val player = urlVideoPlayer ?: AirPlayVideoPlayer(
            context = context,
            surfaceProvider = videoSurfaceProvider,
            onEnded = { stopUrlVideo() }
        ).also { urlVideoPlayer = it }
        player.play(url, startFraction, referer)
        Logger.i("AirPlay URL video started: $url (start=$startFraction, referer=${referer ?: "none"})")
    }

    /**
     * Applies the "Video URL mode" setting without restarting the receiver (FR-60).
     *
     * Turning it off **while a URL is playing** stops that playback immediately: the
     * user asked for mirroring only, and leaving the video running until the sender
     * disconnects would be the opposite of what the switch says. The RTSP session
     * survives, so the sender can start mirroring straight away.
     */
    fun setVideoUrlModeEnabled(enabled: Boolean) {
        if (videoUrlModeEnabled == enabled) return
        videoUrlModeEnabled = enabled
        if (!enabled && urlVideoPlayer != null) {
            Logger.i("Video URL mode disabled while playing — stopping URL playback")
            stopUrlVideo()
        }
    }

    /** Stops AirPlay video URL playback (POST /stop or end-of-media) and ends the session. */
    private fun stopUrlVideo() {
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        onStreamingStopped()
        Logger.i("AirPlay URL video stopped")
    }

    /** Starts the AirPlay 2 buffered audio-only stream (type 103, Apple Music → TV); returns its TCP port. */
    private fun startBufferedAudio(): Int {
        bufferedAudioServer?.stop()
        val server = BufferedAudioServer().also { bufferedAudioServer = it; it.start(scope) }
        // buffered audio (type 103) is always audio-only
        onNowPlayingChanged(nowPlaying.onAudioStarted())
        Logger.i("Buffered audio server started: dataPort=${server.dataPort}")
        return server.dataPort
    }

    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private fun stopBufferedAudio() {
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        onNowPlayingChanged(nowPlaying.onAudioStopped())
        Logger.i("Buffered audio stream stopped")
    }

    /** Clears the video NAL callback, closes the audio socket, and releases media components. */
    private fun releaseMediaComponents() {
        rtspHandler?.onVideoNalUnit = null
        try { audioSocket?.close() } catch (e: Exception) { Logger.d("Close audioSocket failed (non-fatal): ${e.message}") }
        audioSocket = null
        mirrorServer?.stop()
        mirrorServer = null
        audioServer?.stop()
        audioServer = null
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        ntpClient?.stop()
        ntpClient = null
        try { eventClientSocket?.close() } catch (e: Exception) { Logger.d("Close eventClientSocket failed (non-fatal): ${e.message}") }
        eventClientSocket = null
        try { eventSocket?.close() } catch (e: Exception) { Logger.d("Close eventSocket failed (non-fatal): ${e.message}") }
        eventSocket = null
        // Clear the FairPlay/ECDH keys on FULL teardown only. This method runs on a genuine session
        // end (last-stream / session TEARDOWN, or control-connection close) — NOT on a per-stream
        // teardown, which goes through stopMirrorAudio/stopMirrorVideo and leaves the keys intact so
        // macOS can re-add a dynamic stream on the same live session without re-sending keys (that
        // dynamic-readd path is why the keys must survive a stream stop). Clearing here prevents a
        // brand-new control connection from reusing a previous session's stale keys.
        mirrorAesKey = null
        mirrorEcdhSecret = null
        mirrorAesIv = null
        videoDecoder?.release()
        videoDecoder = null
        audioPlayer?.release()
        audioPlayer = null
        // Session fully torn down — clear now-playing so the UI leaves the audio card.
        onNowPlayingChanged(nowPlaying.onStreamEnded())
    }

    // ─── Private: state emission ─────────────────────────────────────────────

    /** Dispatches [state] on the Main thread (Android UI rule). */
    private fun emitState(state: ProtocolState) {
        scope.launch {
            withContext(Dispatchers.Main) {
                onStateChanged(state)
            }
        }
    }

    companion object {
        // Hint dimensions for MediaCodec configuration.
        // Real resolution is encoded in the H.264 SPS NAL unit.
        private const val DEFAULT_VIDEO_WIDTH  = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080

        /**
         * Maximum UDP audio packet size in bytes.
         * ALAC frames are typically ≤ 8 KB. 16 KB is a safe upper bound.
         */
        private const val MAX_AUDIO_PACKET_BYTES = 16 * 1024
    }
}

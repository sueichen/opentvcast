package tv.opentvcast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import tv.opentvcast.MainActivity
import tv.opentvcast.R
import android.view.Surface
import tv.opentvcast.airplay.AirPlayReceiver
import tv.opentvcast.dlna.DlnaReceiver
import tv.opentvcast.airplay.AirPlayPorts
import tv.opentvcast.airplay.RtspHandler
import tv.opentvcast.core.net.RefCountedMulticastLock
import tv.opentvcast.platform.net.AndroidMulticastLock
import tv.opentvcast.platform.net.AndroidReceiverEnvironment
import tv.opentvcast.settings.AppSettings
import tv.opentvcast.settings.SettingsRepository
import tv.opentvcast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.opentvcast.PhotoFrame
import tv.opentvcast.core.protocol.ProtocolState

/**
 * CastService — Android ForegroundService that hosts all receiver protocols.
 *
 * WHY: The AirPlay/Miracast/Cast receivers need to run continuously in the background.
 * Android may kill background processes. A ForegroundService with a persistent
 * notification keeps the app alive and shows the user that opentvcast is active.
 *
 * HOW: Bind to this service from [MainActivity] to receive state updates.
 * Use [ServiceController] to send start/stop/restart commands.
 *
 * Service lifecycle:
 *   startForegroundService() → onCreate() → onStartCommand() → [running in background]
 *   stopSelf() / stopService() → onDestroy() → all receivers stopped
 *
 * Commands via Intent actions (sent by [ServiceController]):
 *   ACTION_START   — starts all enabled receivers
 *   ACTION_STOP    — stops all receivers and stops the service
 *   ACTION_RESTART — stops then starts all receivers (service keeps running)
 */
class CastService : Service() {

    // Binder for Activity binding (returns this service directly)
    private val binder = LocalBinder()

    // Coroutine scope — cancelled in onDestroy() to clean up all coroutines
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Observable state — Activities and Fragments observe this via the binder
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _airPlayState = MutableStateFlow(ProtocolState.DISABLED)
    val airPlayState: StateFlow<ProtocolState> = _airPlayState.asStateFlow()

    private val _dlnaState = MutableStateFlow(ProtocolState.DISABLED)
    val dlnaState: StateFlow<ProtocolState> = _dlnaState.asStateFlow()

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    private val _photoFrame = MutableStateFlow<PhotoFrame?>(null)
    val photoFrame: StateFlow<PhotoFrame?> = _photoFrame.asStateFlow()

    // Non-null while AirPlay audio is playing WITHOUT video — drives the now-playing overlay.
    private val _nowPlaying = MutableStateFlow<tv.opentvcast.airplay.NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<tv.opentvcast.airplay.NowPlayingInfo?> = _nowPlaying.asStateFlow()

    // Non-null while a PIN should be shown on screen for SRP pair-setup (PIN access control).
    private val _pairingPin = MutableStateFlow<String?>(null)
    val pairingPin: StateFlow<String?> = _pairingPin.asStateFlow()

    // Surface provider — supplied by MainActivity after binding (Sprint 5).
    // The lambda captures this field so it always uses the latest provider even if
    // setVideoSurfaceProvider() is called after startAirPlay().
    @Volatile private var videoSurfaceProvider: (() -> Surface?)? = null

    // Receiver instances — null when not running
    private var airPlayReceiver: AirPlayReceiver? = null
    private var dlnaReceiver: DlnaReceiver? = null
    // TODO(P5): hold a DlnaReceiver here once :dlna is implemented. The state
    // holder below already exists so the UI can render the card; only the
    // receiver instance and its start/stop wiring are outstanding.

    /**
     * Reference-counted multicast lock.
     *
     * Android filters inbound multicast unless a lock is held, which is what makes
     * a receiver invisible while logging nothing. The manifest has declared
     * `CHANGE_WIFI_MULTICAST_STATE` all along; this is the part that was missing.
     *
     * Counting rather than a plain acquire/release because more than one thing
     * needs it and they do not share a lifetime: mDNS advertising runs for the
     * whole receiver lifetime, while the DACP discovery client only runs during a
     * session. Releasing on the first of them to finish would leave the other deaf.
     */
    private lateinit var multicastLock: RefCountedMulticastLock

    /**
     * Network-contract environment shared by every receiver protocol.
     *
     * Lazily created on the first [startReceivers] (it needs the display name from
     * settings, which loads asynchronously), kept for the service's lifetime, and
     * closed in [onDestroy] — closing unregisters the connectivity callback, which
     * would otherwise pin the process after the service dies.
     *
     * Consumers today: port allocation (below). From Phase 7 the protocols
     * themselves receive this environment instead of reaching for Android APIs.
     */
    private var environment: AndroidReceiverEnvironment? = null

    /** Ports granted to AirPlay by [environment], released when the receiver stops. */
    private var allocatedAirPlayPorts: List<Int>? = null

    // Settings — read once when starting, re-read on restart
    private lateinit var settingsRepository: SettingsRepository

    // ─── Service Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Logger.i("CastService created")
        settingsRepository = SettingsRepository(applicationContext)
        multicastLock = RefCountedMulticastLock(AndroidMulticastLock(applicationContext))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately with a persistent notification
        startForeground(NOTIFICATION_ID, buildNotification(isRunning = false))

        when (intent?.action) {
            ACTION_START   -> serviceScope.launch { startReceivers() }
            ACTION_STOP    -> serviceScope.launch { stopReceivers(); stopSelf() }
            ACTION_RESTART -> serviceScope.launch { restartReceivers() }
            ACTION_APPLY_SETTINGS -> serviceScope.launch { applyLiveSettings() }
            else           -> serviceScope.launch { startReceivers() } // default: start
        }

        // START_STICKY: if the system kills the service, restart it with a null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The app was swiped away from recents. Cleanly stop all receivers (which closes the RTSP
     * connection so an active mirror ends on the sender too) and stop the service — don't let
     * START_STICKY silently resurrect it as a zombie that keeps advertising/streaming invisibly.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i("App task removed — stopping receivers + service")
        stopReceivers()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Called by [MainActivity] after it binds, to supply the [Surface] for video rendering.
     *
     * The lambda is invoked lazily — only when a stream is actually being started — so it
     * is safe to call this before or after [startAirPlay]. The lambda should return null
     * if the Activity's StreamingScreen is not yet available (e.g., surface not yet created).
     *
     * Call with `{ null }` (or simply don't call) during Activity destruction so we stop
     * holding a reference to the Activity's Surface after the window is gone.
     *
     * @param provider Lambda that returns the current [Surface], or null if unavailable.
     */
    fun setVideoSurfaceProvider(provider: () -> Surface?) {
        videoSurfaceProvider = provider
    }

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or skip what the
     * Mac/iPhone is streaming. Bound Activities call this from media-key events. No-op if no AirPlay
     * sender has advertised a DACP identity.
     */
    fun sendAirPlayRemoteCommand(command: String) {
        airPlayReceiver?.sendRemoteCommand(command)
    }

    override fun onDestroy() {
        Logger.i("CastService destroying")
        stopAllReceiversInternal()
        environment?.close()
        environment = null
        serviceJob.cancel()
        super.onDestroy()
    }

    // ─── Service Control ─────────────────────────────────────────────────────

    /**
     * Starts all receivers that are enabled in Settings.
     *
     * Reads current settings, then starts AirPlay and/or DLNA receivers
     * according to the enabled flags.
     */
    private suspend fun startReceivers() {
        val settings = settingsRepository.settingsFlow.first()
        Logger.i("Starting receivers: AirPlay=${settings.airPlayEnabled}, DLNA=${settings.dlnaEnabled}")

        _serviceState.value = ServiceState.Running
        updateNotification(isRunning = true)

        if (settings.airPlayEnabled) startAirPlay(settings)
        if (settings.dlnaEnabled) startDlna(settings)
    }

    /**
     * Stops all active receivers and updates the service state to Stopped.
     * Does NOT call stopSelf() — use [ACTION_STOP] for that.
     */
    private fun stopReceivers() {
        Logger.i("Stopping all receivers")
        stopAllReceiversInternal()
        _serviceState.value = ServiceState.Stopped
        _activeConnection.value = null
        updateNotification(isRunning = false)
    }

    /**
     * Restarts all receivers: stops them, waits briefly, then starts them again.
     * Used for applying settings changes or recovering from errors.
     */
    /**
     * Applies settings that can change without restarting the receivers (FR-60).
     *
     * So far only one qualifies — the video-URL-mode switch — and it qualifies because the
     * RTSP handler reads it per request: pushing the new value keeps an open session (and
     * any mirror the user is watching) alive. Settings the receivers read once at startup
     * still go through [restartReceivers].
     */
    private suspend fun applyLiveSettings() {
        val settings = settingsRepository.settingsFlow.first()
        airPlayReceiver?.setVideoUrlModeEnabled(settings.videoUrlModeEnabled)
        Logger.i("Live settings applied: videoUrlModeEnabled=${settings.videoUrlModeEnabled}")
    }

    private suspend fun restartReceivers() {
        Logger.i("Restarting all receivers")
        _serviceState.value = ServiceState.Restarting
        updateNotification(isRunning = false)
        stopAllReceiversInternal()
        kotlinx.coroutines.delay(500) // brief pause to ensure ports are released
        startReceivers()
    }

    // ─── Individual Protocol Starters ────────────────────────────────────────

    /**
     * Creates and starts the [AirPlayReceiver].
     *
     * The display name comes from settings — blank means use the Android device name,
     * which [MdnsService] resolves at runtime.
     *
     * Surface is not available here (it lives in the Activity/Fragment).
     * The surface provider is wired up from [MainActivity] in Sprint 5.
     * Until then, video frames are silently discarded and only audio plays.
     *
     * @param settings Current app settings; read once per start/restart cycle.
     */
    private fun startAirPlay(settings: AppSettings) {
        // Mirror the debug-overlay setting into the shared stats bus that StreamingScreen reads.
        tv.opentvcast.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay

        // Idempotent: a redundant ACTION_START (e.g. the activity being recreated while the
        // foreground service is still alive) must NOT spin up a second AirPlayReceiver competing
        // for port 7000. The existing receiver keeps running and picks up the new Surface via the
        // surfaceProvider. A genuine restart goes through ACTION_RESTART (stop → delay → start).
        if (airPlayReceiver != null) {
            Logger.i("AirPlay receiver already running — skipping duplicate start")
            return
        }

        // Network-contract environment: created on first start (needs the display
        // name from settings), reused across restarts, closed in onDestroy.
        val env = environment ?: AndroidReceiverEnvironment.create(
            applicationContext, serviceScope, settings.effectiveDisplayName
        ).also { environment = it }

        // Ask the allocator once and pass on what it GRANTED. The receiver both binds
        // and advertises these values, so the bound and advertised ports can never
        // drift — the failure mode upstream had when three files hardcoded 7000.
        val granted = listOf(
            env.allocatePort(AirPlayPorts.RTSP),
            env.allocatePort(AirPlayPorts.AUDIO_RTP),
            env.allocatePort(AirPlayPorts.TIMING),
        )
        allocatedAirPlayPorts = granted

        // Acquire before mDNS registration: on devices that filter multicast, a
        // registration attempted while the filter is closed can be silently dropped
        // and never retried, which looks like "the TV is not in the AirPlay menu".
        multicastLock.acquire(MULTICAST_OWNER_AIRPLAY)

        // Captures the sender name reported by AirPlayReceiver before CONNECTED fires.
        // onSenderNameChanged is called synchronously before emitState(CONNECTED), so
        // this assignment happens-before the Main-thread read in onStateChanged.
        var pendingSenderName = RtspHandler.DEFAULT_SENDER_NAME

        airPlayReceiver = AirPlayReceiver(
            context = applicationContext,
            displayName = settings.effectiveDisplayName,
            mirrorWidth = settings.mirrorWidth,
            mirrorHeight = settings.mirrorHeight,
            audioEnabled = settings.mirrorAudioEnabled,
            pinAuthEnabled = settings.airPlayPinAuthEnabled,
            videoUrlModeEnabled = settings.videoUrlModeEnabled,
            // Delegate to the current provider at call time — captures the field, not a fixed value.
            // When MainActivity calls setVideoSurfaceProvider(), future surface requests use it.
            videoSurfaceProvider = { videoSurfaceProvider?.invoke() },
            onSenderNameChanged = { name ->
                pendingSenderName = name.ifEmpty { RtspHandler.DEFAULT_SENDER_NAME }
            },
            onPhotoReceived = { bytes, imageType ->
                _photoFrame.value = PhotoFrame(
                    bytes = bytes.copyOf(),
                    mimeType = imageType.mimeType
                )
                updateNotification(isRunning = true)
            },
            onPhotoCleared = {
                _photoFrame.value = null
            },
            onNowPlayingChanged = { info ->
                _nowPlaying.value = info
            },
            onPinChanged = { pin ->
                _pairingPin.value = pin
            },
            onStateChanged = { state ->
                _airPlayState.value = state

                // The decision table lives in ConnectionMapping so the JVM test can
                // assert on the real logic instead of a copy of it.
                _activeConnection.value =
                    ConnectionMapping.connectionFor(state, pendingSenderName, Protocol.AIRPLAY)

                if (state == ProtocolState.CONNECTED) {
                    // A live session supersedes any still image the sender pushed.
                    _photoFrame.value = null
                    updateNotification(isRunning = true, streamingSenderName = pendingSenderName)
                } else {
                    // CONNECTING/ADVERTISING keep the service reported as running;
                    // DISABLED/ERROR do not. A dropped connection never changes the
                    // notification text back to the generic "running" string.
                    updateNotification(isRunning = ConnectionMapping.isRunning(state))
                }
            },
            rtspPort = granted[0],
            audioPort = granted[1],
            timingPort = granted[2],
        ).also { it.start() }
        Logger.d(
            "AirPlay receiver started (displayName='${settings.effectiveDisplayName}' " +
                "rtsp=${granted[0]} audio=${granted[1]} timing=${granted[2]})"
        )
    }

    /**
     * Creates and starts the [DlnaReceiver] (UPnP AV MediaRenderer).
     *
     * Same idempotence rule as AirPlay: a redundant ACTION_START must not spin
     * up a second renderer competing for the same HTTP port.
     *
     * The receiver allocates its own port rather than assuming 8200, which is
     * why the port it publishes in SSDP `LOCATION` is always the granted one.
     */
    private fun startDlna(settings: AppSettings) {
        if (dlnaReceiver != null) {
            Logger.i("DLNA receiver already running — skipping duplicate start")
            return
        }

        val env = environment ?: AndroidReceiverEnvironment.create(
            applicationContext, serviceScope, settings.effectiveDisplayName
        ).also { environment = it }

        val receiver = DlnaReceiver()
        dlnaReceiver = receiver

        // Mirror lifecycle state into the flow the home screen renders (FR-38).
        // The receiver is the source of truth; this just forwards it.
        serviceScope.launch {
            receiver.state.collect { state ->
                _dlnaState.value = state
                if (state == ProtocolState.CONNECTED) {
                    updateNotification(isRunning = true)
                } else {
                    updateNotification(isRunning = ConnectionMapping.isRunning(state))
                }
            }
        }

        serviceScope.launch {
            try {
                receiver.start(env)
                Logger.i("DLNA receiver started")
            } catch (e: Exception) {
                Logger.e("DLNA receiver failed to start", e)
                _dlnaState.value = ProtocolState.ERROR
            }
        }
    }

    private fun stopAllReceiversInternal() {
        try { airPlayReceiver?.stop() } catch (e: Exception) { Logger.e("AirPlay stop error", e) }
        airPlayReceiver = null

        // DLNA releases its own HTTP port and its own multicast reference; the
        // environment is shared, so the environment itself is closed in onDestroy.
        serviceScope.launch {
            try { dlnaReceiver?.stop() } catch (e: Exception) { Logger.e("DLNA stop error", e) }
            dlnaReceiver = null
        }

        // Return the granted ports before anything else can ask for them. The owner
        // tag matches the environment's reservation, so only AirPlay's own ports go.
        allocatedAirPlayPorts?.forEach { port -> environment?.releasePort(port) }
        allocatedAirPlayPorts = null

        // Only actually released once every holder has let go; a DLNA receiver added
        // later takes its own reference and is not disturbed by this call.
        multicastLock.release(MULTICAST_OWNER_AIRPLAY)

        _airPlayState.value = ProtocolState.DISABLED
        _dlnaState.value = ProtocolState.DISABLED
        _photoFrame.value = null
        _nowPlaying.value = null
        _pairingPin.value = null
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // LOW: no sound, minimal visual interruption
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent notification for the ForegroundService.
     *
     * The notification shows the service status and provides quick actions
     * so users can Stop or Restart without opening the app.
     *
     * @param isRunning            True if receivers are active; false if stopped/restarting.
     * @param notificationContentText Override for the notification body text.
     *   When null, the default running/stopped status string is used.
     *   Pass the sender name here (e.g. "Streaming from MacBook Pro") when connected.
     */
    private fun buildNotification(
        isRunning: Boolean,
        notificationContentText: String? = null
    ): Notification {
        // Tapping the notification opens the app
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action — sends ACTION_STOP to this service
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CastService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Restart" action — sends ACTION_RESTART to this service
        val restartIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CastService::class.java).apply { action = ACTION_RESTART },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isRunning) R.string.notification_status_running
                         else           R.string.notification_status_stopped

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationContentText ?: getString(statusText))
            .setContentIntent(openAppIntent)
            .setOngoing(true)                   // Prevents user from swiping away
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stop,    getString(R.string.action_stop),    stopIntent)
            .addAction(R.drawable.ic_restart, getString(R.string.action_restart), restartIntent)
            .build()
    }

    private fun updateNotification(isRunning: Boolean, streamingSenderName: String? = null) {
        val contentText = streamingSenderName?.let {
            getString(R.string.notification_status_streaming, it)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isRunning, contentText))
    }

    // ─── Binder ─────────────────────────────────────────────────────────────

    /**
     * LocalBinder — Provides direct access to [CastService] for bound Activities.
     *
     * WHY: Binding (rather than just starting) the service gives the Activity a
     * direct reference, so it can observe the service's StateFlows without
     * using broadcasts or a shared ViewModel.
     */
    inner class LocalBinder : Binder() {
        fun getService(): CastService = this@CastService
    }

    companion object {
        // Aliases of [CastServiceContract] — the single source of truth for
        // these wire strings. The standalone JVM test runner compiles THIS
        // file out and substitutes src/stubs/CastService.kt; because the stub
        // re-exports the same contract object, the two cannot drift apart.
        const val CHANNEL_ID      = CastServiceContract.CHANNEL_ID
        const val NOTIFICATION_ID = CastServiceContract.NOTIFICATION_ID
        const val ACTION_START    = CastServiceContract.ACTION_START
        const val ACTION_STOP     = CastServiceContract.ACTION_STOP
        const val ACTION_RESTART  = CastServiceContract.ACTION_RESTART

        /** Pushes restart-free settings changes into the running receivers (FR-60). */
        const val ACTION_APPLY_SETTINGS = CastServiceContract.ACTION_APPLY_SETTINGS

        /**
         * Multicast-lock owner tag for AirPlay's advertising.
         *
         * One tag per *concern*, not per class — that is what makes the counting
         * meaningful. A second concern (DACP discovery, or DLNA's SSDP advertising)
         * takes its own tag so that releasing one does not drop the lock the other
         * is still using.
         */
        private const val MULTICAST_OWNER_AIRPLAY = "airplay"
    }
}

package tv.opentvcast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tv.opentvcast.settings.SettingsRepository
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import tv.opentvcast.service.CastService
import tv.opentvcast.service.ServiceController
import tv.opentvcast.airplay.NowPlayingInfo
import tv.opentvcast.ui.HomeFragment
import tv.opentvcast.ui.NowPlayingScreen
import tv.opentvcast.ui.PhotoScreen
import tv.opentvcast.ui.PinScreen
import tv.opentvcast.ui.SettingsFragment
import tv.opentvcast.ui.StreamingScreen
import tv.opentvcast.ui.PanelOrientationState
import timber.log.Timber
import tv.opentvcast.core.flow.RebindableCollectorGroup
import tv.opentvcast.core.protocol.ProtocolState

/**
 * MainActivity — The single Activity hosting opentvcast's navigation and fragments.
 *
 * WHY: opentvcast uses a single-Activity architecture with Fragment-based navigation.
 * This is the recommended pattern for Android TV apps: one Activity with swappable
 * Fragments avoids the overhead of Activity transitions and keeps the Leanback
 * launcher integration simple.
 *
 * Layout structure:
 *   ┌─ Nav Panel ──┬─ Content (FrameLayout) ─────────────────┐
 *   │  Home        │  HomeFragment  OR  SettingsFragment      │
 *   │  Settings    │                                          │
 *   └──────────────┴──────────────────────────────────────────┘
 *   [streaming_container] — full-screen overlay (GONE when idle)
 *
 * HOW: D-pad left/right navigation between nav panel and content area.
 * The nav panel items switch fragments. CastService is started on app launch.
 */
class MainActivity : AppCompatActivity() {

    // UI references
    private lateinit var navItemHome: TextView
    private lateinit var navItemSettings: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var streamingContainer: FrameLayout

    // The SurfaceView for full-screen video output
    private lateinit var streamingScreen: StreamingScreen
    private lateinit var photoScreen: PhotoScreen
    private lateinit var nowPlayingScreen: NowPlayingScreen
    private lateinit var pinScreen: PinScreen

    // Service binding — gives access to state flows for showing/hiding the streaming overlay
    private var service: CastService? = null
    private var isBound = false
    private var currentAirPlayState = ProtocolState.DISABLED
    private var currentPhotoFrame: PhotoFrame? = null
    private var currentNowPlaying: NowPlayingInfo? = null
    private var currentPin: String? = null

    // One bind round of overlay collectors; rebind() on the next bind cycle
    // cancels the previous round instead of stacking collectors on top of it.
    private val overlayCollectors = RebindableCollectorGroup(lifecycleScope)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? CastService.LocalBinder)?.getService()
            isBound = true
            Timber.d("MainActivity: bound to CastService")

            // Wire the streaming Surface so the service can pass it to VideoDecoder
            service?.setVideoSurfaceProvider { getVideoSurface() }

            // Show/hide the full-screen overlay for video streams and photos.
            observeOverlayState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            Timber.d("MainActivity: unbound from CastService")
        }
    }

    // Currently selected nav item index (0 = Home, 1 = Settings)
    private var selectedNavIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Timber.d("MainActivity created")
        bindViews()
        setupOverlayScreens()
        setupNavigation()

        // Show HomeFragment on first launch
        if (savedInstanceState == null) {
            navigateTo(HomeFragment(), navItemHome)
        }

        // Start the service immediately so it's running before any sender discovers us
        ServiceController.start(this)

        // Android 13+ requires an explicit runtime grant for POST_NOTIFICATIONS
        requestNotificationPermission()

        applyPanelOrientation()
    }

    /**
     * Publishes the "Portrait TV" setting for [StreamingScreen] to act on (FR-58).
     *
     * Deliberately *not* `requestedOrientation`: Android TV refuses an application's
     * display-orientation request (see [PanelOrientationMapping]), so the rotation is
     * applied to the video inside this window instead. The window, the app UI and the TV's
     * own settings are untouched — leaving the app ends the rotation with it.
     */
    private fun applyPanelOrientation() {
        lifecycleScope.launch {
            SettingsRepository(applicationContext).settingsFlow.collect { settings ->
                PanelOrientationState.apply(settings.mirrorOrientation, settings.portraitRotation)
                Timber.i("Panel orientation published: ${settings.mirrorOrientation}/${settings.portraitRotation}")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind so we can observe StateFlows and supply the video Surface
        val intent = Intent(this, CastService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Clear surface reference before unbinding to avoid holding a dead Surface
        service?.setVideoSurfaceProvider { null }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A user-initiated exit (Back out of the app) should end any active mirror — closing the
        // service stops the receiver, which drops the RTSP connection so the sender stops mirroring
        // too. isFinishing distinguishes a real exit from a config-change recreation (where the
        // service must keep running). Backgrounding via Home goes through onStop only (no destroy),
        // so the receiver keeps advertising for a quick return.
        if (isFinishing) {
            Timber.d("MainActivity finishing — stopping service so mirroring doesn't linger")
            ServiceController.stop(this)
        } else {
            Timber.d("MainActivity destroyed (recreation) — leaving service running")
        }
    }

    // ─── View Setup ──────────────────────────────────────────────────────────

    private fun bindViews() {
        navItemHome       = findViewById(R.id.nav_item_home)
        navItemSettings   = findViewById(R.id.nav_item_settings)
        contentContainer  = findViewById(R.id.content_container)
        streamingContainer = findViewById(R.id.streaming_container)
    }

    /**
     * Creates the StreamingScreen (SurfaceView for video) and adds it to the
     * streaming_container. Created eagerly so the Surface is ready before streaming starts.
     */
    private fun setupOverlayScreens() {
        streamingScreen = StreamingScreen(this)
        photoScreen = PhotoScreen(this)
        nowPlayingScreen = NowPlayingScreen(this)
        pinScreen = PinScreen(this)
        streamingContainer.addView(streamingScreen)
        streamingContainer.addView(photoScreen)
        streamingContainer.addView(nowPlayingScreen)
        streamingContainer.addView(pinScreen)
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
    }

    /**
     * Sets up click listeners for the navigation panel items.
     * Also updates the visual selected state (text color) of the active item.
     */
    private fun setupNavigation() {
        navItemHome.setOnClickListener {
            if (selectedNavIndex != 0) {
                navigateTo(HomeFragment(), navItemHome)
            }
        }
        navItemSettings.setOnClickListener {
            if (selectedNavIndex != 1) {
                navigateTo(SettingsFragment(), navItemSettings)
            }
        }

        // Set initial selected state
        setNavSelected(navItemHome, true)
        setNavSelected(navItemSettings, false)
    }

    /**
     * Replaces the content_container fragment with [fragment] and updates
     * the nav panel selection highlight.
     *
     * @param fragment  The Fragment to show in the content area.
     * @param navItem   The nav panel TextView that was clicked (for highlight update).
     */
    private fun navigateTo(fragment: Fragment, navItem: TextView) {
        // Update nav highlight
        setNavSelected(navItemHome, navItem == navItemHome)
        setNavSelected(navItemSettings, navItem == navItemSettings)
        selectedNavIndex = if (navItem == navItemHome) 0 else 1

        // Replace fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }

    /**
     * Updates the nav panel item's visual state.
     *
     * @param item     The nav item TextView.
     * @param selected True if this item is currently active.
     */
    private fun setNavSelected(item: TextView, selected: Boolean) {
        item.isSelected = selected
        item.setTextColor(
            getColor(if (selected) R.color.text_primary else R.color.nav_item_normal)
        )
    }

    /**
     * Shows the full-screen streaming overlay (called by CastService
     * via a state update or broadcast when a stream becomes active).
     *
     * Hides the nav panel and content area to give the stream the full screen.
     */
    fun showStreamingScreen() {
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }

    fun showPhotoScreen(photoFrame: PhotoFrame) {
        if (photoScreen.showPhoto(photoFrame.bytes)) {
            streamingScreen.visibility = View.GONE
            nowPlayingScreen.visibility = View.GONE
            pinScreen.visibility = View.GONE
            photoScreen.visibility = View.VISIBLE
            streamingContainer.visibility = View.VISIBLE
            streamingContainer.bringToFront()
        }
    }

    /** Shows the audio-only now-playing card (AirPlay audio with no video). */
    fun showNowPlayingScreen(info: NowPlayingInfo) {
        nowPlayingScreen.update(info)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }

    /**
     * Hides the streaming overlay and returns to the normal app UI.
     * Called when a stream ends.
     */
    fun hideStreamingScreen() {
        photoScreen.clearPhoto()
        photoScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.GONE
    }

    /**
     * The Surface the decoder should render to: the SurfaceView's on a normal panel, the
     * TextureView's when "Portrait TV" is on (a SurfaceView's content cannot be rotated).
     */
    fun getVideoSurface() = streamingScreen.getSurface()

    /**
     * Routes TV-remote media keys to the AirPlay sender (DACP reverse control) while audio-only or a
     * stream is showing — so the remote can play/pause/skip what the Mac/iPhone is streaming. Returns
     * false for other keys so normal navigation is unaffected.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val overlayActive = currentNowPlaying != null || currentAirPlayState == ProtocolState.CONNECTED
        if (overlayActive) {
            val command = when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> tv.opentvcast.airplay.DacpClient.CMD_PLAY_PAUSE
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> tv.opentvcast.airplay.DacpClient.CMD_NEXT
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> tv.opentvcast.airplay.DacpClient.CMD_PREV
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> tv.opentvcast.airplay.DacpClient.CMD_FF
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> tv.opentvcast.airplay.DacpClient.CMD_REW
                else -> null
            }
            if (command != null) {
                service?.sendAirPlayRemoteCommand(command)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Requests POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * On older versions the permission is granted automatically with the manifest declaration.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_NOTIFICATIONS
                )
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1001
    }

    // ─── Streaming overlay ────────────────────────────────────────────────────

    /**
     * Observes [CastService.airPlayState] and [CastService.photoFrame]
     * and shows the appropriate full-screen overlay.
     *
     * Called once after the service is bound. The coroutine is automatically cancelled
     * by [lifecycleScope] when the Activity stops.
     */
    private fun observeOverlayState() {
        val svc = service ?: return

        // Each bind cycle replaces the collector round: without this, every
        // onStart → onStop → onStart transition stacks another set of
        // collectors on a scope that outlives the cycle.
        overlayCollectors.rebind()
        overlayCollectors.collect(svc.airPlayState) { state ->
            currentAirPlayState = state
            updateOverlay()
        }
        overlayCollectors.collect(svc.photoFrame) { frame ->
            currentPhotoFrame = frame
            updateOverlay()
        }
        overlayCollectors.collect(svc.nowPlaying) { info ->
            currentNowPlaying = info
            updateOverlay()
        }
        overlayCollectors.collect(svc.pairingPin) { pin ->
            currentPin = pin
            updateOverlay()
        }
    }

    private fun updateOverlay() {
        val photoFrame = currentPhotoFrame
        val nowPlaying = currentNowPlaying
        val pin = currentPin
        // The priority (PIN > now-playing > live video > photo) lives in
        // [OverlayDecision] so the tests exercise the exact production logic.
        when (OverlayDecision.resolve(pin, nowPlaying, currentAirPlayState, photoFrame)) {
            OverlayDecision.Overlay.PIN -> showPinScreen(pin!!)
            OverlayDecision.Overlay.NOW_PLAYING -> showNowPlayingScreen(nowPlaying!!)
            OverlayDecision.Overlay.STREAMING -> showStreamingScreen()
            OverlayDecision.Overlay.PHOTO -> showPhotoScreen(photoFrame!!)
            OverlayDecision.Overlay.NONE -> hideStreamingScreen()
        }
    }

    /** Shows the AirPlay pairing PIN over the full screen during SRP pair-setup. */
    fun showPinScreen(pin: String) {
        pinScreen.setPin(pin)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }
}

package tv.opentvcast.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import android.widget.FrameLayout
import android.widget.TextView
import tv.opentvcast.airplay.StreamStats
import tv.opentvcast.core.video.MirrorFit
import tv.opentvcast.util.Logger

/**
 * StreamingScreen — Full-screen view that displays the AirPlay video stream.
 *
 * WHY: The decoded video from MediaCodec must be rendered to a [Surface].
 * A [SurfaceView] provides a dedicated, hardware-accelerated drawing surface
 * that can receive MediaCodec output directly — no intermediate bitmap copies.
 * This is the lowest-latency way to display video on Android.
 *
 * HOW: Add this view to the streaming_container in activity_main.xml.
 * Call [getSurface] to get the Surface to pass to [VideoDecoder.initialize].
 * The Surface is valid as long as this view is attached to the window.
 *
 * IMPORTANT: The Surface becomes available asynchronously after the view is
 * laid out. [getSurface] returns null if called before the Surface is ready.
 * [VideoDecoder.initialize] must not be called until [getSurface] returns non-null.
 *
 * Example:
 *   val streamingScreen = StreamingScreen(context)
 *   container.addView(streamingScreen)
 *   // Later, when surface is ready:
 *   val surface = streamingScreen.getSurface()
 *   videoDecoder.initialize(spsBytes, ppsBytes, width, height)
 */
class StreamingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // The SurfaceView that provides the hardware-accelerated rendering surface
    private val surfaceView: SurfaceView = SurfaceView(context)

    // The Surface is created asynchronously by SurfaceView — stored here when ready
    private var surface: Surface? = null

    // Optional debug HUD (Settings → "Debug overlay"), drawn on top of the video.
    private val debugView = TextView(context).apply {
        setTextColor(Color.parseColor("#FF00FF66"))
        setBackgroundColor(Color.parseColor("#A6000000"))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(24, 16, 24, 16)
        visibility = GONE
    }
    // Last applied surface size, so we only re-layout on an actual change (rotation/resolution switch).
    private var lastSurfaceW = Int.MIN_VALUE
    private var lastSurfaceH = Int.MIN_VALUE

    // Last applied picture rotation, for the same reason: the setting can change mid-session.
    private var lastRotation = Int.MIN_VALUE

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            applyAspectFit()
            if (StreamStats.overlayEnabled) {
                debugView.visibility = VISIBLE
                debugView.text = StreamStats.summary()
            } else if (debugView.visibility != GONE) {
                debugView.visibility = GONE
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        // Black backing so the letterbox/pillarbox bars (when the video is sized to its aspect ratio
        // and doesn't fill 16:9) are black — without this, those margins are transparent and the home
        // menu shows through behind the streaming overlay. The SurfaceView punches its own hole on top.
        setBackgroundColor(Color.BLACK)

        // "Portrait TV" lays the video out as a rectangle taller than this window and
        // rotates it into place (see MirrorFit). The overhang is empty space — the picture
        // itself lands inside the window — so clipping it away would cut the picture, not
        // the overhang. The parent container turns its own clipping off for the same reason.
        clipChildren = false

        // SurfaceView is centred; its size is set to the video's aspect ratio by applyAspectFit().
        addView(surfaceView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        // Debug HUD overlay, top-left, above the video surface.
        addView(debugView, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = 48; leftMargin = 48 })

        // Register a callback to track when the Surface is created/destroyed
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Surface is now ready — store reference for VideoDecoder
                surface = holder.surface
                Logger.d("StreamingScreen: Surface created")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Called when the surface size changes (e.g., resolution change)
                // MediaCodec handles this automatically — no action needed here
                Logger.d("StreamingScreen: Surface changed ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Surface is gone (e.g., screen turned off) — VideoDecoder must stop
                surface = null
                Logger.d("StreamingScreen: Surface destroyed")
            }
        })
    }

    /**
     * Returns the [Surface] where decoded video frames will be rendered.
     *
     * Returns null if the Surface has not been created yet (the view hasn't
     * been laid out) or if it has been destroyed. The caller must check for
     * null before passing this to [VideoDecoder.initialize].
     *
     * @return The rendering Surface, or null if not yet available.
     */
    fun getSurface(): Surface? = surface

    /**
     * Sizes the SurfaceView to the decoded video's aspect ratio (letterbox/pillarbox), rotates it
     * when the panel is declared rotated, and never stretches. Falls back to filling the container
     * while the video size is still unknown.
     *
     * The arithmetic lives in [MirrorFit] so it can be tested without a device; this method only
     * applies its answer. Two details are load-bearing here:
     *
     * - **The box is the transpose of what is displayed.** Rotating a view swaps its extents, so a
     *   portrait picture on a landscape window needs a view *taller than the window*. That is why
     *   `clipChildren` is off.
     * - **Rotation is re-applied when the setting changes**, not only when the video size does —
     *   the user can flip "Portrait TV" while a mirror is running.
     */
    private fun applyAspectFit() {
        val degrees = PanelOrientationState.rotationDegrees
        val box = MirrorFit.boxFor(
            videoWidth = StreamStats.videoWidth,
            videoHeight = StreamStats.videoHeight,
            containerWidth = width,
            containerHeight = height,
            rotationDegrees = degrees,
        )
        val (targetW, targetH) = box?.let { it.widthPx to it.heightPx }
            ?: (LayoutParams.MATCH_PARENT to LayoutParams.MATCH_PARENT)

        if (targetW == lastSurfaceW && targetH == lastSurfaceH && degrees == lastRotation) return
        lastSurfaceW = targetW
        lastSurfaceH = targetH
        lastRotation = degrees

        if (surfaceView.rotation != degrees.toFloat()) {
            surfaceView.rotation = degrees.toFloat()
            Logger.d("StreamingScreen: picture rotated ${degrees}°")
        }
        surfaceView.layoutParams = (surfaceView.layoutParams as LayoutParams).apply {
            width = targetW; height = targetH; gravity = Gravity.CENTER
        }
        Logger.d("StreamingScreen: video ${StreamStats.videoWidth}x${StreamStats.videoHeight} → box ${targetW}x$targetH (rotation ${degrees}°)")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)                 // drive aspect-fit + debug HUD
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val REFRESH_MS = 200L
    }
}

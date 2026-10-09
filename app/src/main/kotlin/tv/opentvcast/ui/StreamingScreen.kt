/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.ui

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import tv.opentvcast.airplay.StreamStats
import tv.opentvcast.core.video.MirrorFit
import tv.opentvcast.util.Logger

/**
 * StreamingScreen — Full-screen view that displays the AirPlay video stream.
 *
 * WHY TWO VIEWS: the picture is carried by a [SurfaceView] on a normal panel and by a [TextureView]
 * when the panel is declared rotated, and the reason is a hard platform limit rather than a
 * preference:
 *
 * > "Because a SurfaceView's content does not live in the application's window, it cannot be
 * > transformed (moved, scaled, rotated) efficiently."
 *
 * A [SurfaceView] composites its own surface in SurfaceFlinger, so `rotation` moves the *hole* the
 * view punches in the window and leaves the decoded picture upright inside it. Measured exactly that
 * on a real Android TV: the transposed layout box was applied, the rotation was not, and a portrait
 * phone stream came out with its long edge along the panel's short edge. A [TextureView] draws
 * through the view hierarchy and does honour `rotation`.
 *
 * The SplitView costs nothing for the ordinary case: a landscape panel — the default, and the case
 * that already worked — keeps the SurfaceView and its zero-copy path. Only a rotated panel pays for
 * the TextureView's extra GPU copy.
 *
 * HOW: both views exist from construction and exactly one is visible. The invisible one is
 * `INVISIBLE`, never `GONE`, so it stays attached and laid out and keeps a surface ready for a
 * switch. [getSurface] returns the surface of whichever view is currently showing; a change of the
 * setting therefore changes which surface the decoder writes to, which `MirrorStreamServer` already
 * handles by rebuilding the decoder on its next pass.
 *
 * IMPORTANT: the Surface becomes available asynchronously, so [getSurface] can return null — before
 * the overlay is first shown, and briefly around a switch. The mirroring path polls for it
 * (`MirrorStreamServer.awaitSurface`); the URL player retries.
 */
class StreamingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Carries the picture on a panel that stands the way it shipped — the ordinary case. */
    private val surfaceView: SurfaceView = SurfaceView(context)

    /** Carries the picture on a panel that has been rotated, where the content must turn with it. */
    private val textureView: TextureView = TextureView(context)

    // Each view's Surface, stored when it is created and cleared when it is destroyed.
    @Volatile private var surfaceViewSurface: Surface? = null
    @Volatile private var textureViewSurface: Surface? = null

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

        // "Portrait TV" lays the video out as a rectangle taller than this window and rotates it into
        // place (see MirrorFit). The overhang is empty space — the picture itself lands inside the
        // window — so clipping it away would cut the picture, not the overhang. The parent container
        // turns its own clipping off for the same reason.
        clipChildren = false

        // Both are centred; their size is set to the video's aspect ratio by applyAspectFit().
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            .apply { gravity = Gravity.CENTER })
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            .apply { gravity = Gravity.CENTER })

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceViewSurface = holder.surface
                Logger.d("StreamingScreen: SurfaceView surface created")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // MediaCodec handles a surface resize on its own — no action needed here.
                Logger.d("StreamingScreen: SurfaceView surface changed ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceViewSurface = null
                Logger.d("StreamingScreen: SurfaceView surface destroyed")
            }
        })

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                textureViewSurface = Surface(st)
                Logger.i("StreamingScreen: TextureView surface ready (${width}x${height})")
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                Logger.d("StreamingScreen: TextureView surface changed ${width}x${height}")
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                textureViewSurface?.release()
                textureViewSurface = null
                Logger.d("StreamingScreen: TextureView surface destroyed")
                return true                      // we released our Surface; the texture goes with it
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }

        // Start on the SurfaceView: a landscape panel is the default, and a non-rotated picture on a
        // TextureView would pay a GPU copy for nothing.
        textureView.visibility = INVISIBLE
    }

    /**
     * True when the picture is carried by the [TextureView] — i.e. the panel is declared rotated.
     *
     * One predicate behind both [activeView] and [getSurface], so "which view the user sees" and
     * "which surface the decoder writes to" cannot disagree. (They disagreed once: the box was
     * transposed for a rotated panel while the picture went to the SurfaceView, which cannot rotate,
     * and the result was a sideways picture on a real TV.)
     *
     * It also delegates the *definition* of "rotated" to [MirrorFit.isRotated] rather than testing
     * `!= 0` itself, because the same question is asked when the box is computed: two rules that
     * agreed on every angle the settings produce, and disagreed on every other one, is exactly how
     * the bug above came about.
     *
     * [degrees] is a parameter so a test can ask about an angle the settings cannot produce.
     */
    internal fun usesTextureView(degrees: Int = PanelOrientationState.rotationDegrees): Boolean =
        MirrorFit.isRotated(degrees)

    /** The view currently showing the picture. `internal` so a test can assert the switch. */
    internal fun activeView(): View = if (usesTextureView()) textureView else surfaceView

    /**
     * Returns the [Surface] where decoded video frames will be rendered.
     *
     * Null while the surface is not ready: before the streaming overlay is first shown, or briefly
     * around a switch between the two views. Callers must tolerate null — the mirror path polls
     * (`MirrorStreamServer.awaitSurface`) and the URL player retries.
     *
     * @return The rendering Surface, or null if not available.
     */
    fun getSurface(): Surface? =
        if (usesTextureView()) textureViewSurface else surfaceViewSurface

    /**
     * Sizes the active view to the decoded video's aspect ratio (letterbox/pillarbox), rotates it when
     * the panel is declared rotated, and never stretches. While the size is unknown — URL playback,
     * or the moment before the first frame — it falls back to the container, transposed when rotated.
     *
     * The arithmetic lives in [MirrorFit] so it can be tested without a device; this method only
     * applies its answer. Three details are load-bearing here:
     *
     * - **The box is the transpose of what is displayed.** Rotating a view swaps its extents, so a
     *   portrait picture on a landscape window needs a view *taller than the window*. That is why
     *   `clipChildren` is off.
     * - **Rotation goes on the TextureView only.** The SurfaceView cannot carry it, and pretending
     *   otherwise is the bug this split fixes.
     * - **Both views get the geometry**, so a mid-session switch is instant instead of waiting for the
     *   next pass.
     *
     * `internal` rather than private so a unit test can call it without waiting for the 200 ms tick.
     * The wiring it performs — read the angle, ask [MirrorFit], apply box and rotation, pick the view
     * — is the part a pure `MirrorFit` test cannot cover.
     */
    internal fun applyAspectFit() {
        val degrees = PanelOrientationState.rotationDegrees
        val box = MirrorFit.boxFor(
            videoWidth = StreamStats.videoWidth,
            videoHeight = StreamStats.videoHeight,
            containerWidth = width,
            containerHeight = height,
            rotationDegrees = degrees,
        ) ?: MirrorFit.fallbackBoxFor(width, height, degrees)
        val (targetW, targetH) = box?.let { it.widthPx to it.heightPx }
            ?: (LayoutParams.MATCH_PARENT to LayoutParams.MATCH_PARENT)

        if (targetW == lastSurfaceW && targetH == lastSurfaceH && degrees == lastRotation) return
        lastSurfaceW = targetW
        lastSurfaceH = targetH
        lastRotation = degrees

        val useTexture = usesTextureView(degrees)
        surfaceView.visibility = if (useTexture) INVISIBLE else VISIBLE
        textureView.visibility = if (useTexture) VISIBLE else INVISIBLE

        surfaceView.layoutParams = (surfaceView.layoutParams as LayoutParams).apply {
            width = targetW; height = targetH; gravity = Gravity.CENTER
        }
        surfaceView.rotation = 0f                     // never asked to rotate: it cannot

        textureView.layoutParams = (textureView.layoutParams as LayoutParams).apply {
            width = targetW; height = targetH; gravity = Gravity.CENTER
        }
        if (textureView.rotation != degrees.toFloat()) {
            textureView.rotation = degrees.toFloat()
            Logger.d("StreamingScreen: picture rotated ${degrees}°")
        }
        Logger.d(
            "StreamingScreen: video ${StreamStats.videoWidth}x${StreamStats.videoHeight} → " +
                "box ${targetW}x$targetH (rotation ${degrees}°, view ${if (useTexture) "TextureView" else "SurfaceView"})"
        )
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

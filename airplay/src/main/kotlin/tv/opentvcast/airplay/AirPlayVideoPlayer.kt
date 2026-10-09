package tv.opentvcast.airplay

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import tv.opentvcast.util.Logger

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,        // 0.0 = paused, 1.0 = playing
    val readyToPlay: Boolean,
)

/**
 * AirPlayVideoPlayer — plays an AirPlay "video URL" stream (the non-mirroring mode: a sender app
 * like Safari or a TV app says "AirPlay this video" and POSTs a URL via `/play`). We hand the URL to
 * Android's [MediaPlayer] and render to the same streaming [Surface] the mirror decoder uses.
 *
 * Distinct from screen mirroring (H.264 over a data stream): here the TV fetches and plays the media
 * itself, and the sender only drives transport (`/rate`, `/scrub`, `/stop`) + polls `/playback-info`.
 *
 * TWO things a bare `setDataSource(url)` gets wrong, both of which show up as a black screen rather
 * than an error a user can act on:
 *
 * - **The sender's `Referer` is dropped.** A video handed over by a web page is normally served by a
 *   CDN that refuses requests without it. It travels in the `/play` body ([RtspVideoControl]) and is
 *   forwarded here via [mediaHeaders]; sending it needs the `Context` overload of `setDataSource`,
 *   which is why this class holds a `Context` at all.
 * - **`MediaPlayer`'s reported video size is not used for framing.** It was tried and measured
 *   wrong: it reported a container-declared 540×540 for a stream that renders as 16:9, and laying
 *   the picture out by it produced a letterboxed square instead of a full-screen picture. The
 *   player already letterboxes correctly inside whatever surface it is given, so the surface is
 *   sized by geometry alone — see `MirrorFit.fallbackBoxFor`.
 * - **A `Surface` that is not ready yet is never retried.** The streaming overlay is created when the
 *   session connects, so the Surface usually exists — but the *first* `/play` can arrive before it
 *   does, and a `MediaPlayer` without a surface plays audio and shows nothing. The mirroring path has
 *   always polled for its surface (`MirrorStreamServer.awaitSurface`); this now does the same.
 *
 * Methods are `@Synchronized` because RTSP control verbs arrive on the RTSP thread while MediaPlayer
 * callbacks fire on its own thread.
 */
class AirPlayVideoPlayer(
    /** Needed for the header-bearing `setDataSource` overload — see the class docs. */
    private val context: Context,
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit = {},
) {
    private var mp: MediaPlayer? = null
    /** The Surface the player was last given, so a *changed* one can be noticed — see below. */
    @Volatile private var attachedSurface: Surface? = null
    @Volatile private var prepared = false
    @Volatile private var startFraction = 0.0
    private val retryHandler = Handler(Looper.getMainLooper())

    /** Starts playing [url], seeking to [startPositionFraction] (0..1 of duration) once prepared. */
    @Synchronized
    fun play(url: String, startPositionFraction: Double, referer: String? = null) {
        release()
        startFraction = startPositionFraction.coerceIn(0.0, 1.0)
        Logger.i("AirPlay video: play url=$url start=$startFraction referer=${referer ?: "none"}")
        val player = MediaPlayer()
        mp = player
        prepared = false
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            attachSurface(player)
            player.setOnPreparedListener { onPrepared(it) }
            player.setOnCompletionListener { Logger.i("AirPlay video: completed"); onEnded() }
            player.setOnErrorListener { _, what, extra ->
                Logger.e("AirPlay video error what=$what extra=$extra")
                true   // handled — don't also fire onCompletion
            }
            player.setDataSource(context, Uri.parse(url), mediaHeaders(referer))
            player.prepareAsync()
        }.onFailure { Logger.e("AirPlay video: setup failed", it); release() }
    }

    @Synchronized
    private fun onPrepared(player: MediaPlayer) {
        // onPrepared fires on MediaPlayer's own thread and can race release()/a new play(): if the
        // player was released (or replaced) while preparing, mp no longer points at it — bail before
        // touching a dead MediaPlayer (which would throw IllegalStateException).
        if (mp !== player) return
        // The Surface usually doesn't exist at setDataSource() time (the Activity creates it only
        // after CONNECTED) — attach it now that prepare has completed, and keep asking if it is
        // still not there: a player with no surface plays sound and renders nothing.
        if (!attachSurface(player)) scheduleSurfaceRetry(player, attempt = 1)
        if (startFraction > 0.0) runCatching { player.seekTo((startFraction * player.duration).toInt()) }
        prepared = true
        runCatching { player.start() }
        Logger.i("AirPlay video: prepared dur=${runCatching { player.duration }.getOrDefault(0)}ms → playing")
    }

    /**
     * Attaches the current Surface, if there is one, and remembers it. Returns whether the player now
     * has a surface.
     *
     * A failure is **reported rather than swallowed**: the caller's whole job is to keep asking until
     * the player has one, and returning `true` on a failed `setSurface` would stop the retries on a
     * picture that never appears — a black screen with sound, which is the failure the retry exists
     * to prevent.
     */
    @Synchronized
    private fun attachSurface(player: MediaPlayer): Boolean {
        val surface = surfaceProvider() ?: return false
        val attached = runCatching { player.setSurface(surface) }
            .onFailure { Logger.w("AirPlay video: setSurface failed", it) }
            .isSuccess
        if (attached) attachedSurface = surface
        return attached
    }

    /**
     * Re-attaches if the view has produced a **different** Surface since the last attach.
     *
     * The picture lives on a Surface that belongs to whichever view is showing it, and that Surface is
     * replaced when the streaming overlay is hidden and shown again, or when "Portrait TV" switches
     * between the two views. Mirroring notices by itself — `MirrorStreamServer` compares the provider
     * with its configured surface on every frame and rebuilds its decoder. This path has no frame
     * loop, so it checks on the transport verbs a sender already sends during playback
     * (`/playback-info` polls, `/rate`, `/scrub`). Worst case the picture is black until the sender's
     * next poll, which is normally about a second.
     *
     * Not unit-tested: it acts on a concrete `MediaPlayer`, and this class has no seam for one (the
     * same deviation the receiver's live URL stop carries). Covered by hand on hardware instead.
     */
    @Synchronized
    private fun reattachSurfaceIfChanged() {
        val player = mp ?: return
        val current = surfaceProvider() ?: return       // nothing to attach to right now
        if (current === attachedSurface) return
        Logger.i("AirPlay video: Surface changed — re-attaching")
        attachSurface(player)
    }

    /** Keeps asking for a Surface every [SURFACE_ATTACH_RETRY_MS] for about two seconds. */
    private fun scheduleSurfaceRetry(player: MediaPlayer, attempt: Int) {
        if (attempt > SURFACE_ATTACH_TRIES) {
            Logger.w(
                "AirPlay video: no Surface after ${SURFACE_ATTACH_TRIES * SURFACE_ATTACH_RETRY_MS} ms " +
                    "— playing audio only until the next /play"
            )
            return
        }
        retryHandler.postDelayed({
            if (mp !== player) return@postDelayed          // released or replaced meanwhile
            if (attachSurface(player)) {
                Logger.i("AirPlay video: Surface attached after ${attempt * SURFACE_ATTACH_RETRY_MS} ms")
            } else {
                scheduleSurfaceRetry(player, attempt + 1)
            }
        }, SURFACE_ATTACH_RETRY_MS)
    }

    /** rate ≤ 0 pauses, > 0 resumes. */
    @Synchronized
    fun setRate(rate: Float) {
        reattachSurfaceIfChanged()
        val player = mp ?: return
        runCatching {
            if (rate <= 0f) { if (player.isPlaying) player.pause() }
            else { if (!player.isPlaying) player.start() }
        }
    }

    /** Seeks to [positionSec] seconds. */
    @Synchronized
    fun scrub(positionSec: Double) {
        reattachSurfaceIfChanged()
        runCatching { mp?.seekTo((positionSec * 1000).toInt()) }
    }

    /** Current playback snapshot for `/playback-info`, or null if nothing is loaded. */
    @Synchronized
    fun info(): PlaybackInfo? {
        reattachSurfaceIfChanged()
        val player = mp ?: return null
        if (!prepared) return PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        val dur = runCatching { player.duration }.getOrDefault(0)
        val pos = runCatching { player.currentPosition }.getOrDefault(0)
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        return PlaybackInfo(dur / 1000.0, pos / 1000.0, if (playing) 1.0 else 0.0, readyToPlay = true)
    }

    @Synchronized
    fun release() {
        retryHandler.removeCallbacksAndMessages(null)
        mp?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        mp = null
        attachedSurface = null
        prepared = false
    }

    companion object {

        /** Retry budget for the first Surface, in 100 ms ticks — ~2 s, matching the mirror path. */
        private const val SURFACE_ATTACH_TRIES = 20
        private const val SURFACE_ATTACH_RETRY_MS = 100L

        /**
         * The request headers the receiver sends when it fetches media, and the only place that
         * decides which ones.
         *
         * A pure function so the policy — "forward what the sender told us, invent nothing" — has a
         * test. Adding a header here is a decision about what identity we present to a third-party
         * CDN, so it should be a deliberate edit with a test, not a line added in passing.
         */
        internal fun mediaHeaders(referer: String?): Map<String, String> =
            if (referer.isNullOrBlank()) emptyMap() else mapOf("Referer" to referer)
    }
}

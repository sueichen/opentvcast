package tv.opentvcast.settings

/**
 * AppSettings — Immutable data model for all user-configurable opentvcast settings.
 *
 * WHY: Centralizing all settings in one data class gives a single source of truth.
 * Any component that needs a setting reads from here. Any component that changes a
 * setting creates a new copy via [copy]. This makes settings changes explicit and
 * easy to test.
 *
 * HOW: Settings are persisted via [SettingsRepository]. Get the current settings
 * from [SettingsRepository.settingsFlow] and update them via [SettingsRepository.update].
 *
 * Example:
 *   // Read settings
 *   val settings = settingsRepository.settingsFlow.first()
 *   if (settings.airPlayEnabled) { ... }
 *
 *   // Change a setting
 *   settingsRepository.update { it.copy(displayName = "My TV") }
 */
data class AppSettings(

    // ─── Display ───────────────────────────────────────────────────────────
    /**
     * The name shown in sender pickers (AirPlay menu on Mac, Cast picker in Chrome, etc.).
     * If empty, the Android device name is used as a fallback.
     * Validated: max 63 characters, must not be blank after trimming.
     */
    val displayName: String = "",

    // ─── Protocols ─────────────────────────────────────────────────────────
    /**
     * Whether the AirPlay 2 receiver is enabled.
     * When false: mDNS advertisement is stopped, RTSP port 7000 is not opened.
     */
    val airPlayEnabled: Boolean = true,

    /**
     * Whether the DLNA / UPnP AV MediaRenderer is enabled.
     * When false: SSDP advertisement stops and the control HTTP port is closed.
     */
    val dlnaEnabled: Boolean = true,

    // ─── AirPlay specific ──────────────────────────────────────────────────
    /**
     * Whether AirPlay connections require PIN authentication.
     * When true: the user must confirm a 4-digit PIN shown on screen.
     * When false (default): any nearby Mac can connect without confirmation.
     */
    val airPlayPinAuthEnabled: Boolean = false,

    // ─── Service behavior ──────────────────────────────────────────────────
    /**
     * Whether CastService starts automatically on device boot.
     * Requires the RECEIVE_BOOT_COMPLETED permission to be effective.
     */
    val startOnBoot: Boolean = false,

    // ─── Developer / Debug ─────────────────────────────────────────────────
    /**
     * Overlays a debug HUD on the streaming screen showing:
     * - Current frames per second
     * - Estimated A/V latency (ms)
     * - Active protocol name
     * Only useful for development and testing.
     */
    val showDebugOverlay: Boolean = false,

    // ─── Video ─────────────────────────────────────────────────────────────
    /**
     * When true, advertise a higher mirroring resolution (1440p) in the AirPlay `/info`
     * `displays` record so macOS renders/encodes the mirror at 2560×1440 instead of 1920×1080.
     * The TV surface is 1080p, so frames are downscaled (sharper text via supersampling) at
     * the cost of more decode work — heavier on low-end SoCs.
     */
    val forceHighResolution: Boolean = false,

    /**
     * When true, accept the mirroring audio stream (type 96, AAC-ELD). EXPERIMENTAL: macOS uses
     * realtime audio clock-sync (RTCP) that isn't fully implemented yet, which can make macOS tear
     * the whole mirror session down after a couple of seconds — so this defaults OFF to keep video
     * mirroring rock-solid. Turn on to experiment with audio.
     */
    val mirrorAudioEnabled: Boolean = true,

    /**
     * Whether AirPlay **video URL playback** is allowed (FR-60).
     *
     * On (default): a sender app may hand the TV a URL and the TV plays the media
     * itself. Off: the receiver refuses `POST /play` and mirrors only, so what appears
     * on the TV is always the sender's screen.
     *
     * The wording of this setting must not promise that a sender will mirror instead —
     * it will not; a video app that is refused usually reports that it cannot play on
     * this TV, and the user has to start Screen Mirroring.
     */
    val videoUrlModeEnabled: Boolean = true,

    /**
     * Physical orientation of the panel this TV is running on (FR-58).
     *
     * `LANDSCAPE` (default) is the ordinary case. `PORTRAIT` means the user rotated the
     * panel 90°, and the app asks the platform for a portrait window so vertical content
     * fills the panel's long edge instead of sitting pillarboxed in the middle.
     *
     * Activity-scoped by design: the request lasts only while this app is on screen, so
     * leaving the app returns the TV to its system state.
     */
    val mirrorOrientation: MirrorOrientation = MirrorOrientation.LANDSCAPE,

    /**
     * Which way the panel was turned, for [MirrorOrientation.PORTRAIT] (FR-59).
     *
     * The two values give the two possible upright results; one of them will look upside
     * down, so this is a user choice rather than something to detect.
     */
    val portraitRotation: PortraitRotation = PortraitRotation.LEFT
) {

    /** Advertised mirroring display size: 2560×1440 when [forceHighResolution], else 1920×1080. */
    val mirrorWidth: Int get() = if (forceHighResolution) 2560 else 1920
    val mirrorHeight: Int get() = if (forceHighResolution) 1440 else 1080

    /**
     * Returns the validated, trimmed display name.
     * If the stored name is blank, returns an empty string so callers
     * can fall back to the system device name.
     */
    val effectiveDisplayName: String
        get() = displayName.trim()

    /**
     * Returns true if at least one protocol is enabled.
     *
     * When this is false the service has nothing to advertise, which is what the
     * home screen uses to explain why nothing is discoverable.
     */
    val anyProtocolEnabled: Boolean
        get() = airPlayEnabled || dlnaEnabled

    companion object {
        /** The default settings instance used on first launch. */
        val DEFAULT = AppSettings()

        /** Maximum allowed length for the display name (mDNS limit). */
        const val DISPLAY_NAME_MAX_LENGTH = 63

        /**
         * The single normalization a display-name input goes through before it
         * may be stored: surrounding whitespace is trimmed (a pure-whitespace
         * entry therefore becomes the system default) and the mDNS length cap
         * is enforced as a hard clamp. The settings dialog and any future
         * caller must use this instead of hand-rolling trim/take.
         */
        fun sanitizeDisplayName(raw: String): String =
            raw.trim().take(DISPLAY_NAME_MAX_LENGTH)
    }
}

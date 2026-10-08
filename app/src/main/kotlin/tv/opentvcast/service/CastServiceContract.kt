package tv.opentvcast.service

/**
 * CastServiceContract — the wire-visible constants of [CastService] as a plain
 * Kotlin object with zero Android dependencies.
 *
 * WHY THIS EXISTS: [CastService] cannot compile in the standalone JVM test
 * runner (android.app.Service + AndroidX + generated R), so
 * `test-runner/src/stubs/CastService.kt` substitutes a stub class. Before this
 * object, the action strings and IDs lived as hand-copied literals in BOTH
 * files — the stub's own KDoc demanded "every value here MUST match the real
 * service exactly" and relied on CastServiceTest to catch drift after the
 * fact. Now both the real companion and the stub re-export this one source, so
 * drift is structurally impossible — the same guarantee [ConnectionMapping]
 * already gives the state-mapping table.
 *
 * WIRE CONTRACT: the ACTION strings are sent by [ServiceController] and
 * matched in [CastService.onStartCommand]; CHANNEL_ID / NOTIFICATION_ID are
 * stable-across-upgrade identifiers (renaming the channel abandons the old
 * channel's user settings).
 */
object CastServiceContract {
    /** Notification channel ID — stable across upgrades; do not rename casually. */
    const val CHANNEL_ID = "opentvcast_service_channel"

    /** Foreground notification ID — stable across upgrades. */
    const val NOTIFICATION_ID = 1001

    /** Starts all enabled receivers. */
    const val ACTION_START = "tv.opentvcast.action.START"

    /** Stops all receivers and stops the service. */
    const val ACTION_STOP = "tv.opentvcast.action.STOP"

    /** Stops then starts all receivers (the service itself keeps running). */
    const val ACTION_RESTART = "tv.opentvcast.action.RESTART"

    /**
     * Pushes settings that can change without a restart into the running receivers
     * (FR-60). Distinct from [ACTION_RESTART] on purpose: restarting drops an active
     * session, which is the wrong price for a switch the protocol reads per request.
     */
    const val ACTION_APPLY_SETTINGS = "tv.opentvcast.action.APPLY_SETTINGS"
}

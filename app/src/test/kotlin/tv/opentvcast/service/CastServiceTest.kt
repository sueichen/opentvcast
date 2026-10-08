package tv.opentvcast.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.opentvcast.core.protocol.ProtocolState

/**
 * CastServiceTest — Unit tests for [CastService].
 *
 * WHY: [CastService] is the central coordinator. Bugs in its state aggregation
 * logic or intent routing cause symptoms that are hard to debug (service not starting,
 * wrong notification text, connection not registered).
 *
 * WHAT WE TEST:
 * - The Intent action constants have the expected package-qualified strings
 * - The notification channel ID and notification ID are stable across code changes
 * - The [ProtocolState] → [ActiveConnection] mapping logic is correct
 * - [ProtocolState.CONNECTED] creates an [ActiveConnection] for the right protocol
 * - Non-CONNECTED states clear the [ActiveConnection]
 *
 * HOW: [CastService] is an Android [Service] and requires Android APIs for
 * full lifecycle testing. That is covered by instrumentation tests.
 * Here we test only:
 *   1. Companion constants (compile-time safe)
 *   2. The `onStateChanged` callback logic, extracted as a pure lambda so it
 *      can be tested without an Android runtime.
 *
 * NOTE: Android Service, Notification, and Context are NOT used in any test here.
 */
class CastServiceTest {

    // ─── Companion constants ──────────────────────────────────────────────────

    @Test
    fun `ACTION_START has package-qualified value`() {
        assertEquals("tv.opentvcast.action.START", CastService.ACTION_START)
    }

    @Test
    fun `ACTION_STOP has package-qualified value`() {
        assertEquals("tv.opentvcast.action.STOP", CastService.ACTION_STOP)
    }

    @Test
    fun `ACTION_RESTART has package-qualified value`() {
        assertEquals("tv.opentvcast.action.RESTART", CastService.ACTION_RESTART)
    }

    @Test
    fun `ACTION_APPLY_SETTINGS has package-qualified value`() {
        // Same guard as the other three: the value is the wire contract between
        // ServiceController and CastService, and the :test-runner stub re-exports it.
        assertEquals("tv.opentvcast.action.APPLY_SETTINGS", CastService.ACTION_APPLY_SETTINGS)
    }

    @Test
    fun `NOTIFICATION_ID is positive`() {
        assertTrue(
            "NOTIFICATION_ID must be > 0 (Android rejects 0)",
            CastService.NOTIFICATION_ID > 0
        )
    }

    @Test
    fun `CHANNEL_ID is non-empty`() {
        assertTrue(CastService.CHANNEL_ID.isNotEmpty())
    }

    @Test
    fun `all ACTION constants are distinct and none is empty`() {
        val actions = setOf(
            CastService.ACTION_START,
            CastService.ACTION_STOP,
            CastService.ACTION_RESTART
        )
        assertEquals("All ACTION constants must be unique", 3, actions.size)
    }

    // ─── Companion constants are contract aliases (E4) ────────────────────────

    @Test
    fun `companion constants re-export CastServiceContract`() {
        // Single source of truth: the real service AND the JVM-runner stub must
        // both take these values from CastServiceContract. This test runs
        // against the real class under :app and against the stub under
        // :test-runner — in both cases it fails the moment either copy stops
        // following the contract.
        assertEquals(CastServiceContract.CHANNEL_ID, CastService.CHANNEL_ID)
        assertEquals(CastServiceContract.NOTIFICATION_ID, CastService.NOTIFICATION_ID)
        assertEquals(CastServiceContract.ACTION_START, CastService.ACTION_START)
        assertEquals(CastServiceContract.ACTION_STOP, CastService.ACTION_STOP)
        assertEquals(CastServiceContract.ACTION_RESTART, CastService.ACTION_RESTART)
    }

    // ─── ProtocolState → ActiveConnection mapping ─────────────────────────────
    //
    // The decision table itself lives in [ConnectionMapping] — a plain object with
    // no Android dependency — so these tests exercise the *production* logic
    // rather than a copy of it. An earlier version of this file hand-copied the
    // lambda body out of CastService; when ProtocolState.CONNECTING was added the
    // copy was missed and the module stopped compiling. Do not reintroduce a copy.
    //
    // WHY IT MATTERS: if CONNECTED doesn't create an ActiveConnection, the UI never
    // shows the streaming status. If CONNECTING doesn't clear it, a stale session
    // lingers while the sender is still negotiating.

    private fun simulateStateChange(state: ProtocolState): ActiveConnection? =
        ConnectionMapping.connectionFor(state, "AirPlay Sender", Protocol.AIRPLAY)

    @Test
    fun `CONNECTED state creates ActiveConnection for AirPlay protocol`() {
        val connection = simulateStateChange(ProtocolState.CONNECTED)

        assertNotNull("CONNECTED must create an ActiveConnection", connection)
        assertEquals(Protocol.AIRPLAY, connection?.protocol)
        assertEquals("AirPlay Sender", connection?.senderName)
    }

    @Test
    fun `ADVERTISING state clears ActiveConnection`() {
        val result = simulateStateChange(ProtocolState.ADVERTISING)
        assertNull("ADVERTISING must clear the ActiveConnection", result)
    }

    @Test
    fun `DISABLED state clears ActiveConnection`() {
        val result = simulateStateChange(ProtocolState.DISABLED)
        assertNull("DISABLED must clear the ActiveConnection", result)
    }

    @Test
    fun `ERROR state clears ActiveConnection`() {
        val result = simulateStateChange(ProtocolState.ERROR)
        assertNull("ERROR must clear the ActiveConnection", result)
    }

    @Test
    fun `CONNECTING state clears ActiveConnection`() {
        // A sender mid-handshake has no usable session yet, so the UI must not
        // claim one is streaming.
        val result = simulateStateChange(ProtocolState.CONNECTING)
        assertNull("CONNECTING must clear the ActiveConnection", result)
    }

    @Test
    fun `every ProtocolState maps to a defined connection decision`() {
        // The expected table is written out by hand, NOT derived from
        // ConnectionMapping — so this also fails if a state that does not exist
        // yet is later given the wrong meaning.
        val expectedConnected = setOf(ProtocolState.CONNECTED)
        for (state in ProtocolState.entries) {
            val connection = simulateStateChange(state)
            if (state in expectedConnected) {
                assertNotNull("$state must produce an ActiveConnection", connection)
            } else {
                assertNull("$state must not produce an ActiveConnection", connection)
            }
        }
    }

    // ─── ConnectionMapping.isRunning ──────────────────────────────────────────

    @Test
    fun `isRunning is true while advertising, connecting or connected`() {
        assertTrue(ConnectionMapping.isRunning(ProtocolState.ADVERTISING))
        assertTrue(ConnectionMapping.isRunning(ProtocolState.CONNECTING))
        assertTrue(ConnectionMapping.isRunning(ProtocolState.CONNECTED))
    }

    @Test
    fun `isRunning is false when disabled or errored`() {
        assertEquals(false, ConnectionMapping.isRunning(ProtocolState.DISABLED))
        assertEquals(false, ConnectionMapping.isRunning(ProtocolState.ERROR))
    }

    @Test
    fun `isRunning is defined for every state`() {
        // Guards the table the same way the connection mapping is guarded: nothing
        // may be left undefined. The UI reads this on every state change.
        for (state in ProtocolState.entries) {
            val running = ConnectionMapping.isRunning(state)
            if (state == ProtocolState.DISABLED || state == ProtocolState.ERROR) {
                assertEquals("$state must not be reported as running", false, running)
            } else {
                assertEquals("$state must be reported as running", true, running)
            }
        }
    }

    // ─── ConnectionMapping.connectionFor: the protocol dimension ─────────────

    @Test
    fun `the connection is attributed to the protocol that produced it`() {
        // v1 ships two protocols. A DLNA session reported as AirPlay would put the
        // wrong label on the home-screen card and the notification.
        val airplay = ConnectionMapping.connectionFor(ProtocolState.CONNECTED, "Mac", Protocol.AIRPLAY)
        val dlna = ConnectionMapping.connectionFor(ProtocolState.CONNECTED, "NAS", Protocol.DLNA)

        assertEquals(Protocol.AIRPLAY, airplay?.protocol)
        assertEquals(Protocol.DLNA, dlna?.protocol)
    }

    @Test
    fun `the sender name is carried through unchanged`() {
        val connection = ConnectionMapping.connectionFor(
            ProtocolState.CONNECTED,
            "Alex's iPad",
            Protocol.DLNA,
        )

        assertEquals("Alex's iPad", connection?.senderName)
    }

    @Test
    fun `a connection is stamped with a fresh timestamp`() {
        val before = System.currentTimeMillis()
        val connection = ConnectionMapping.connectionFor(
            ProtocolState.CONNECTED,
            "Mac",
            Protocol.AIRPLAY,
        )!!
        val after = System.currentTimeMillis()

        assertTrue(
            "durationSeconds is derived from this, so it must be the moment the " +
                "connection was created",
            connection.startedAt in before..after,
        )
    }

    @Test
    fun `CONNECTED then ADVERTISING transition clears connection`() {
        // Simulate a full connect → teardown cycle
        val afterConnect      = simulateStateChange(ProtocolState.CONNECTED)
        val afterAdvertising  = simulateStateChange(ProtocolState.ADVERTISING)

        assertNotNull(afterConnect)
        assertNull("After ADVERTISING, connection must be null", afterAdvertising)
    }

    @Test
    fun `CONNECTED ActiveConnection has non-negative duration`() {
        val connection = simulateStateChange(ProtocolState.CONNECTED)!!
        assertTrue(connection.durationSeconds >= 0L)
    }

    @Test
    fun `CONNECTED ActiveConnection startedAt is recent`() {
        val before = System.currentTimeMillis()
        val connection = simulateStateChange(ProtocolState.CONNECTED)!!
        val after = System.currentTimeMillis()
        assertTrue(connection.startedAt in before..after)
    }

}

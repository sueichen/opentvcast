package tv.opentvcast.service

import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ServiceControllerTest — Unit tests for [ServiceController].
 *
 * WHY: [ServiceController] is the single point through which the UI controls the
 * background service. Bugs here mean the user can't start/stop/restart the receiver.
 * We verify that each method sends the correct Intent action to the service.
 *
 * WHAT WE TEST:
 * - [ServiceController.start] dispatches ACTION_START to CastService
 * - [ServiceController.stop] dispatches ACTION_STOP to CastService
 * - [ServiceController.restart] dispatches ACTION_RESTART to CastService
 * - Intent target component is CastService
 *
 * HOW: Context is mocked with MockK. We capture the Intent passed to
 * startForegroundService / startService and assert its action and target.
 *
 * NOTE: ServiceController uses Context.startForegroundService (API 26+) or
 * Context.startService (API < 26) — no AndroidX dependency.
 * Runs under Robolectric so android.content.Intent (action/component) behaves for real; the
 * Context is still a MockK relaxed mock so we can capture the dispatched Intent. @Config sdk=34
 * means Build.VERSION.SDK_INT = 34, so startForegroundService is used for start() and restart().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceControllerTest {

    private lateinit var context: Context
    private val fgIntentSlot = slot<Intent>()

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.startForegroundService(capture(fgIntentSlot)) } returns null
    }

    @Test
    fun `start() sends ACTION_START intent via startForegroundService`() {
        ServiceController.start(context)

        verify { context.startForegroundService(any()) }
        assertEquals(CastService.ACTION_START, fgIntentSlot.captured.action)
    }

    @Test
    fun `stop() sends ACTION_STOP intent via startService`() {
        val stopSlot = slot<Intent>()
        every { context.startService(capture(stopSlot)) } returns mockk()

        ServiceController.stop(context)

        verify { context.startService(any()) }
        assertEquals(CastService.ACTION_STOP, stopSlot.captured.action)
    }

    @Test
    fun `restart() sends ACTION_RESTART intent via startForegroundService`() {
        ServiceController.restart(context)

        verify { context.startForegroundService(any()) }
        assertEquals(CastService.ACTION_RESTART, fgIntentSlot.captured.action)
    }

    @Test
    fun `applySettings() sends ACTION_APPLY_SETTINGS intent via startService`() {
        // startService, not startForegroundService: applying a restart-free setting must
        // not be the thing that spins the service up (or promote it to foreground).
        val slot2 = slot<Intent>()
        every { context.startService(capture(slot2)) } returns mockk()

        ServiceController.applySettings(context)

        verify { context.startService(any()) }
        assertEquals(CastService.ACTION_APPLY_SETTINGS, slot2.captured.action)
    }

    @Test
    fun `start() intent targets CastService class`() {
        ServiceController.start(context)

        assertEquals(
            CastService::class.java.name,
            fgIntentSlot.captured.component?.className
        )
    }
}

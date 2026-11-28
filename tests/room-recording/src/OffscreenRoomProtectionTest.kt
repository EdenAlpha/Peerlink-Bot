package com.peerlink.app.check

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import com.peerlink.app.core.AppState
import com.peerlink.app.godmode.OffscreenRoomProtection
import com.peerlink.app.play.BotRoomBackend
import com.peerlink.app.play.PlayRoomController
import com.peerlink.app.play.RoomState
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.peerlink.app.godmode.PrimeClient", isInAndroidSdk = false)
class OffscreenPrimeShadow {
    companion object {
        data class Start(val token: String, val task: Int)
        val starts = LinkedBlockingQueue<Start>()
        val stops = LinkedBlockingQueue<String>()
        var activityDisplay = 8
    }

    @Implementation(methodName = "executeQuiet\$app_debug")
    fun reply(command: String, timeoutMs: Int): String? {
        val args = command.lineSequence().first().split(" ")
        val token = args[3]
        return when (args[2]) {
            "start" -> {
                starts.add(Start(token, args.last().toInt()))
                OffscreenRoomProtection.activityShown(token, activityDisplay)
                "DISPLAY_ID=8"
            }
            "stop" -> {
                stops.add(token)
                "DISPLAY_CLOSED"
            }
            else -> null
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, instrumentedPackages = ["com.peerlink.app.godmode"],
    shadows = [OffscreenPrimeShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class OffscreenRoomProtectionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        OffscreenPrimeShadow.starts.clear()
        OffscreenPrimeShadow.stops.clear()
        OffscreenPrimeShadow.activityDisplay = 8
        shadowOf(context.packageManager)
            .setSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS, true)
        PlayRoomController.onRoomCreated("12345678")
    }

    @After fun stop() {
        AppState.isGodModeActive.set(false)
        PlayRoomController.onRoomFailed("test finished")
    }

    @Test fun sameRoomUsesOneDisplayAndRoomFailureReleasesIt() = runBlocking {
        assertTrue(OffscreenRoomProtection.prepare(context, "12345678", 37))
        val token = OffscreenPrimeShadow.starts.remove().token
        assertNull(OffscreenRoomProtection.roomOnDisplay(token, 0))
        assertNull(OffscreenRoomProtection.roomOnDisplay("old token", 8))
        assertEquals("12345678", OffscreenRoomProtection.roomOnDisplay(token, 8))
        assertTrue(OffscreenRoomProtection.prepare(context, "12345678", 37))
        assertTrue(OffscreenPrimeShadow.starts.isEmpty())

        PlayRoomController.onRoomFailed("room closed")
        assertEquals(token, OffscreenPrimeShadow.stops.poll(2, TimeUnit.SECONDS))
        assertNull(OffscreenRoomProtection.roomOnDisplay(token, 8))
    }

    @Test fun wrongActivityDisplayReleasesTheHelperAndKeepsTheRoomReady() = runBlocking {
        OffscreenPrimeShadow.activityDisplay = 9
        assertFalse(OffscreenRoomProtection.prepare(context, "12345678", 37))
        assertEquals(OffscreenPrimeShadow.starts.remove().token, OffscreenPrimeShadow.stops.remove())
        assertTrue(OffscreenRoomProtection.roomReady("12345678"))
        assertEquals("Off-screen display unavailable", (PlayRoomController.state.value as RoomState.Ready).offscreenNote)
    }

    @Test fun unsupportedPhonesSkipPrimeAndLeaveTheRoomReady() = runBlocking {
        shadowOf(context.packageManager)
            .setSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS, false)
        assertFalse(OffscreenRoomProtection.prepare(context, "12345678", 37))
        assertTrue(OffscreenPrimeShadow.starts.isEmpty())
        assertTrue(OffscreenRoomProtection.roomReady("12345678"))
    }

    @Test fun primeRoomStartsDisplayWithNoActivityOrOpenGameTap() = runBlocking {
        PlayRoomController.onRoomFailed("reset")
        AppState.isGodModeActive.set(true)
        PlayRoomController.attach(object : BotRoomBackend {
            override suspend fun createRoom(ctx: Context, allowResourceDownload: Boolean,
                                            verifyEngine: ((Int) -> Unit)?, onMatchStarting: (() -> Unit)?) = "87654321"
        }, context)
        PlayRoomController.createRoom()

        val ready = withTimeout(3_000) {
            PlayRoomController.state.filterIsInstance<RoomState.Ready>().first { it.offscreenNote == "Off-screen display active" }
        }
        val start = OffscreenPrimeShadow.starts.remove()
        assertEquals(0, start.task)
        assertEquals("87654321", ready.number)
        assertEquals(ready.number, OffscreenRoomProtection.roomOnDisplay(start.token, 8))
    }
}

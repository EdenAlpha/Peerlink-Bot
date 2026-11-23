package com.peerlink.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.peerlink.app.core.AppState
import com.peerlink.app.core.PeerInfo
import com.peerlink.app.core.PeerSessionPhase
import com.peerlink.app.godmode.GodModeManager
import com.peerlink.app.bot.BotPacketCounts
import com.peerlink.app.play.RoomState
import com.peerlink.app.play.BotRoomBackend
import com.peerlink.app.play.PlayRoomController
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/** Real Compose rendering and interaction with a local, service-free fixture. */
class PeerLinkUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(active: Boolean = false, largeText: Boolean = false, connect: () -> Unit = {}, disconnect: () -> Unit = {}, prime: () -> Unit = {}) {
        GodModeManager.init(context)
        context.getSharedPreferences("peerlink_prefs", Context.MODE_PRIVATE).edit().putString("username", "Emmanuel").commit()
        AppState.isRunning.set(active)
        AppState.sessionPhase.set(if (active) PeerSessionPhase.ACTIVE else PeerSessionPhase.IDLE)
        AppState.sessionError.set(null)
        AppState.connectedPeerName = if (active) "Alex" else ""
        AppState.sessionStartedElapsedMs.set(if (active) android.os.SystemClock.elapsedRealtime() else 0)
        compose.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, if (largeText) 1.5f else 1f)) {
                PeerLinkTheme {
                    PeerLinkScreen(context, listOf(PeerInfo("Alex", "192.0.2.2")),
                        DiscoveryUiState(phase = DiscoveryPhase.PEERS_FOUND, message = "One player nearby", peerCount = 1),
                        PeerLinkActions(saveName = {}, connectToPeer = { connect() }, disconnect = disconnect,
                            onSetupPrimeMode = prime))
                }
            }
        }
    }

    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "ui-checks").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val preview = Bitmap.createScaledBitmap(bitmap, 540, (bitmap.height * 540f / bitmap.width).toInt(), true)
        File(dir, "$name-preview.jpg").outputStream().use { preview.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    @After fun reset() {
        AppState.isRunning.set(false)
        AppState.sessionPhase.set(PeerSessionPhase.IDLE)
        AppState.connectedPeerName = ""
        AppState.sessionStartedElapsedMs.set(0)
    }

    @Test fun nearbyPlayerCanBeSelected() {
        var selected = 0
        show(connect = { selected++ })
        compose.onNodeWithText("Good games.", substring = true).assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Alex"))
        compose.onNodeWithText("Alex").performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("Activity", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your activity").assertIsDisplayed()
        screenshot("activity")
    }

    @Test fun connectionActionsRequireConfirmationToDisconnect() {
        var disconnected = 0
        show(active = true, disconnect = { disconnected++ })
        compose.onNodeWithText("Open eFootball").assertIsDisplayed()
        screenshot("connected")
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Disconnect"))
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("End this connection?").assertIsDisplayed()
        compose.onNodeWithText("Keep playing").performClick()
        compose.runOnIdle { assertEquals(0, disconnected) }
    }

    @Test fun largeTextKeepsPlayerAndSettingsReachable() {
        show(largeText = true)
        screenshot("large-text")
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Alex"))
        compose.onNodeWithText("Alex").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        compose.onNodeWithText("Make it yours.").assertIsDisplayed()
        screenshot("settings-large-text")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("home-list").assertIsDisplayed()
    }

    @Test fun botPageHasOneClearRoomAction() {
        show()
        compose.onNodeWithText("Play PeerLink", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("bot-match-page").assertIsDisplayed()
        compose.onNodeWithTag("bot-create-room").assertIsDisplayed()
        compose.onAllNodesWithText("Create room").assertCountEquals(1)
        screenshot("bot-match")
    }

    @Test fun settingsOpensPrimeSetupAndKeepsCrashLogsReachable() {
        var openedPrime = 0
        show(prime = { openedPrime++ })
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settings-prime").performClick()
        compose.runOnIdle { assertEquals(1, openedPrime) }
        screenshot("settings")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Crash logs & advanced"))
        compose.onNodeWithText("Crash logs & advanced").performClick()
        compose.onNodeWithText("Crash Logs").assertIsDisplayed()
    }

    @Test fun readyBotRoomShowsCodeAndSeparateGameTraffic() {
        compose.setContent {
            PeerLinkTheme {
                Surface(Modifier.fillMaxSize(), color = PL.bg) {
                PlayPeerLinkContent(context, RoomState.Ready("123456789", "your game joined -- the bot is firing its half"), primeReady = false, primeActive = false,
                    botSession = true, traffic = BotPacketCounts(2765, 2774, android.os.SystemClock.elapsedRealtime()),
                    onExportLogs = {})
                }
            }
        }
        compose.onNodeWithTag("bot-room-code").assertTextEquals("123456789")
        compose.onNodeWithText("Your game joined. The bot is getting ready.").assertIsDisplayed()
        compose.onNodeWithTag("bot-open-game").assertIsDisplayed()
        screenshot("bot-room-ready")
        compose.onNodeWithTag("bot-match-page").performScrollToNode(hasTestTag("bot-traffic-card"))
        compose.onNodeWithText("From your game").assertIsDisplayed()
        compose.onNodeWithText("Sent to your game").assertIsDisplayed()
        compose.onNodeWithText(java.text.NumberFormat.getIntegerInstance().format(2765)).assertIsDisplayed()
        compose.onNodeWithText(java.text.NumberFormat.getIntegerInstance().format(2774)).assertIsDisplayed()
        screenshot("bot-traffic")
    }

    @Test fun failedBotRoomOffersDiagnostics() {
        var exported = 0
        compose.setContent {
            PeerLinkTheme {
                Surface(Modifier.fillMaxSize(), color = PL.bg) {
                PlayPeerLinkContent(context, RoomState.Failed("The room could not be reached."), false, false,
                    false, BotPacketCounts(), onExportLogs = { exported++ })
                }
            }
        }
        compose.onNodeWithText("Couldn't open the room").assertIsDisplayed()
        compose.onNodeWithText("Export diagnostics").performClick()
        compose.runOnIdle { assertEquals(1, exported) }
        screenshot("bot-error")
    }

    @Test fun startupFailureLeavesPreparationAndShowsRetry() {
        val previous = PlayRoomController.backend
        PlayRoomController.attach(object : BotRoomBackend {
            override suspend fun createRoom(ctx: Context, allowResourceDownload: Boolean, verifyEngine: ((Int) -> Unit)?,
                                            onMatchStarting: (() -> Unit)?): String {
                error("The graphics-free bot could not start.")
            }
        }, context)
        try {
            show()
            compose.onNodeWithText("Play PeerLink", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("bot-create-room").performClick()
            compose.waitUntil(5000) { PlayRoomController.state.value is RoomState.Failed }
            compose.onNodeWithText("Couldn't open the room").assertIsDisplayed()
            compose.onNodeWithText("Try again").assertIsDisplayed()
            compose.onNodeWithText("Export diagnostics").assertIsDisplayed()
            compose.onNodeWithText("Setting things up").assertDoesNotExist()
            screenshot("bot-startup-failure")
        } finally {
            PlayRoomController.backend = previous
            // No service was started by this deliberately failing backend.
            PlayRoomController.endSession()
            compose.waitUntil(5000) { PlayRoomController.state.value is RoomState.Idle }
        }
    }

    @Test fun primeSetupShowsPairingActions() {
        GodModeManager.init(context)
        compose.setContent { PeerLinkTheme { PrimeSetupScreen(context, onBack = {}, onStartPairing = {}) } }
        compose.onNodeWithTag("prime-setup-screen").assertIsDisplayed()
        compose.onNodeWithText("Prime Mode").assertIsDisplayed()
        screenshot("prime-setup")
    }

    @Test fun launcherIconRendersAsAnAndroidDrawable() {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, bitmap.width, bitmap.height)
        icon.draw(android.graphics.Canvas(bitmap))
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(128, 128)) > 0)
        val dir = File(context.getExternalFilesDir(null), "ui-checks").apply { mkdirs() }
        File(dir, "launcher-icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class BotRematchSocketTest {
    @Test fun secondRoundKeepsTheRoomsLocalPortAndForgetsThePreviousOpponent() {
        val port = PeerSlot.reserveEndpoint()
        try {
            PeerSlot.arm(31L, 30L, "", "127.0.0.1", 42232)
            PeerSlot.engineAttached = true
            assertTrue(PeerSlot.ownsListener(port))
            PeerSlot.finishRound()
            assertFalse(PeerSlot.engineAttached)
            assertFalse(PeerSlot.ownsListener(port))
            assertEquals(port, PeerSlot.reserveEndpoint())
            PeerSlot.arm(52L, 30L, "", "127.0.0.1", 42233)
            assertTrue(PeerSlot.ownsListener(port))
            assertEquals(52L, PeerSlot.gameUid)
            assertTrue(PeerSlot.ENGINE_PORT > 0 && PeerSlot.ENGINE_REFLEXIVE_PORT > 0)
        } finally { PeerSlot.disarm("test_end") }
    }
}

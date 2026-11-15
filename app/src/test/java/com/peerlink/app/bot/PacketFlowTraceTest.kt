package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class PacketFlowTraceTest {
    @Test fun distinguishesPacketsReceivedFromPacketsActuallyHandedToAnOutgoingSocket() {
        var time = 1000L
        val flow = PacketFlowTrace { time }
        flow.record(PacketFlowTrace.Point.GAME_RX, false, 256)
        flow.drop("game_to_engine_no_socket")
        flow.record(PacketFlowTrace.Point.ENGINE_RX, true, 64)
        flow.record(PacketFlowTrace.Point.GAME_TX, true, 64)
        time += 250
        val text = flow.summary()
        assertTrue(text.contains("host={game_rx=1/256B/ageMs:250 engine_tx=0/0B/ageMs:-1"))
        assertTrue(text.contains("engine_rx=1/64B/ageMs:250 game_tx=1/64B/ageMs:250"))
        assertTrue(text.contains("drops={game_to_engine_no_socket=1}"))
        assertTrue(text.contains("txMeans=accepted_by_local_socket"))
    }
}

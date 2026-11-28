package com.peerlink.app.godmode

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** A room status view on the private display, independent of the phone's main task. */
class RoomDisplayActivity : Activity() {
    private val token by lazy { intent.getStringExtra("room_token") }

    @Suppress("DEPRECATION")
    private fun displayId() = windowManager.defaultDisplay.displayId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val room = OffscreenRoomProtection.roomOnDisplay(token, displayId())
        if (room == null) {
            finishAndRemoveTask()
            return
        }
        setContentView(TextView(this).apply {
            gravity = Gravity.CENTER
            text = "PeerLink room $room"
        })
    }

    override fun onResume() {
        super.onResume()
        val id = displayId()
        if (OffscreenRoomProtection.roomOnDisplay(token, id) == null) finishAndRemoveTask()
        else OffscreenRoomProtection.activityShown(token, id)
    }
}

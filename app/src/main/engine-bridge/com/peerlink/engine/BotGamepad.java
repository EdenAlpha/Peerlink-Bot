package com.peerlink.engine;

/** JNI input-provider port for this engine's software controller, never Android's global input inventory. */
public final class BotGamepad {
    private BotGamepad() {}
    public static String getControllerInfo() {
        // Native parser: device count, fields/device, then id/name/product/vendor.
        return "1,4,4242,PeerLink Bot Controller,654,1118";
    }
    public static boolean hasShoulderTriggers(int deviceId) { return deviceId == 4242; }
}

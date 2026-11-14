package jp.konami.android.common;

/** Input-provider port for the isolated bot engine's own software gamepad. */
public final class GameController {
    private GameController() {}
    public static String getControllerInfo() {
        // Same binary name and JNI signature as the original provider. ART's
        // native class lookup cannot rely on a loadClass alias to another name.
        return "1,4,4242,PeerLink Bot Controller,654,1118";
    }
    public static boolean hasShoulderTriggers(int deviceId) { return deviceId == 4242; }
}

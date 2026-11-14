package com.peerlink.app.bot;
/** Compile-only parent-loader API, never packaged in the bridge dex. */
public final class GameAndroidRuntime {
    public static ClassLoader gameLoader() { throw new UnsupportedOperationException(); }
    public static android.content.Context gameContext() { throw new UnsupportedOperationException(); }
    public static java.io.File storageRoot() { throw new UnsupportedOperationException(); }
    public static void activityCreated(android.app.Activity value) { throw new UnsupportedOperationException(); }
    public static void activityFailed(Throwable value) { throw new UnsupportedOperationException(); }
}

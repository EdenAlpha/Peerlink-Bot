package com.peerlink.app.bot;
import android.app.NativeActivity;
import android.os.Bundle;

/** Invoke the real framework lifecycle without running the game's menu/login Activity glue. */
public final class FrameworkActivityCalls {
    static { System.loadLibrary("peerlinkbackend"); }
    private FrameworkActivityCalls() {}
    public static native void create(NativeActivity activity, Bundle state);
    public static native void lifecycle(NativeActivity activity, int event);
}

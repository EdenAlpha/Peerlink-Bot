package com.epicgames.ue4;

/** Compile-only signatures. This class is NEVER packaged; the installed game supplies it. */
public class GameActivity extends android.app.NativeActivity {
    public native void nativeSetGlobalActivity(boolean externalFiles, boolean logs,
        String internal, String external, boolean insideApk, String apk);
    public native void nativeResumeMainInit();
    public boolean AndroidThunkJava_IsGamepadAttached() { throw new UnsupportedOperationException(); }
    public InputDeviceInfo AndroidThunkJava_GetInputDeviceInfo(int id) { throw new UnsupportedOperationException(); }
    public class InputDeviceInfo {}
}

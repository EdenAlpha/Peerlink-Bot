package com.peerlink.engine;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import com.epicgames.ue4.GameActivity;
import com.peerlink.app.bot.GameAndroidRuntime;
import com.peerlink.app.bot.FrameworkActivityCalls;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Framework-attached native host in the bot service; never receives human screen input. */
public final class PhoneGameHost extends GameActivity {
    private Resources gameResources;
    public PhoneGameHost() { super(); }
    private Context game() { return GameAndroidRuntime.gameContext(); }

    @Override public void onCreate(Bundle state) {
        try {
            Field activity = GameActivity.class.getDeclaredField("_activity");
            activity.setAccessible(true);
            if (activity.get(null) != null) throw new IllegalStateException("Game instance already exists");
            activity.set(null, this);
            Field assets = GameActivity.class.getDeclaredField("AssetManagerReference");
            assets.setAccessible(true); assets.set(this, getAssets());
            // These original startup calls populate SystemInfo's CPU/GPU
            // strings. The SDK reads them while constructing its services.
            // Query graphics before starting native rendering; its tiny probe
            // context must not terminate the renderer's later EGL display.
            java.lang.reflect.Method graphics = GameActivity.class.getDeclaredMethod("getGraphicsInformation");
            graphics.setAccessible(true); graphics.invoke(this);
            Class.forName("jp.konami.android.common.SystemInfo", true, getClassLoader())
                .getMethod("InitSOCInfo", String.class).invoke(null, android.os.Build.HARDWARE);
            Class.forName("jp.konami.android.common.FirebaseAnalyticsImpl", true, getClassLoader())
                .getMethod("onCreate", Context.class).invoke(null, this);
            // The original GameActivity initializes this before its resume
            // callbacks. This initializes the public SDK; it does not sign in.
            Class.forName("com.google.android.gms.games.PlayGamesSdk", true, getClassLoader())
                .getMethod("initialize", Context.class).invoke(null, getApplicationContext());
            nativeSetGlobalActivity(true, false, getFilesDir().getAbsolutePath(),
                getExternalFilesDir(null).getAbsolutePath(), false, game().getPackageCodePath());
            // NativeActivity allocates its real NativeCode, callback pipe,
            // window and input queues. No ANativeActivity record is manufactured.
            FrameworkActivityCalls.create(this, state);
            nativeResumeMainInit();
            GameAndroidRuntime.activityCreated(this);
        } catch (Exception | LinkageError error) {
            GameAndroidRuntime.activityFailed(error);
            throw new IllegalStateException("Bot Activity startup failed", error);
        }
    }
    @Override public void onResume() {
        try {
            Class.forName("jp.konami.AndroidUtil", true, getClassLoader()).getMethod("OnResumeCallback").invoke(null);
            FrameworkActivityCalls.lifecycle(this, 0);
        } catch (Exception error) { throw new IllegalStateException("Bot Activity resume failed", error); }
    }
    @Override protected void onPause() { FrameworkActivityCalls.lifecycle(this, 1); }
    @Override public void onStop() { FrameworkActivityCalls.lifecycle(this, 2); }
    @Override public void onDestroy() { FrameworkActivityCalls.lifecycle(this, 3); }
    @Override public void onWindowFocusChanged(boolean focus) {
        FrameworkActivityCalls.lifecycle(this, focus ? 4 : 5);
    }

    @Override public ClassLoader getClassLoader() { return GameAndroidRuntime.gameLoader(); }
    @Override public AssetManager getAssets() { return game().getAssets(); }
    @Override public Resources getResources() {
        if (gameResources == null) {
            Resources display = super.getResources();
            gameResources = new Resources(getAssets(), display.getDisplayMetrics(), display.getConfiguration());
        }
        return gameResources;
    }
    @Override public ApplicationInfo getApplicationInfo() { return game().getApplicationInfo(); }
    @Override public String getPackageName() { return GameAndroidRuntime.hostPackageName(); }
    @Override public String getPackageCodePath() { return game().getPackageCodePath(); }
    @Override public String getPackageResourcePath() { return game().getPackageResourcePath(); }
    @Override public File getFilesDir() { return directory("internal"); }
    @Override public File getCacheDir() { return directory("cache"); }
    @Override public File getExternalFilesDir(String type) { return directory(type == null ? "external" : "external/" + type); }
    @Override public File getObbDir() { return directory("obb"); }
    private File directory(String name) {
        File result = new File(GameAndroidRuntime.storageRoot(), name);
        if (!result.isDirectory() && !result.mkdirs()) throw new IllegalStateException("Cannot create bot storage");
        return result;
    }
    @Override public boolean AndroidThunkJava_IsGamepadAttached() { return true; }
    @Override public InputDeviceInfo AndroidThunkJava_GetInputDeviceInfo(int id) {
        if (id != 4242) return super.AndroidThunkJava_GetInputDeviceInfo(id);
        try {
            Constructor<?> ctor = InputDeviceInfo.class.getDeclaredConstructor(GameActivity.class,
                int.class, int.class, int.class, int.class, String.class, String.class);
            ctor.setAccessible(true);
            return (InputDeviceInfo)ctor.newInstance(this, id, 1118, 654, 1,
                "PeerLink Bot Controller", "peerlink:bot:4242");
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Bot controller unavailable", error); }
    }
}

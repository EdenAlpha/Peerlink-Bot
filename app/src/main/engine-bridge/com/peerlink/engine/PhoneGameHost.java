package com.peerlink.engine;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Looper;
import android.view.Window;
import com.epicgames.ue4.GameActivity;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Own game instance. It never attaches to, launches, or injects into the user's Activity. */
public final class PhoneGameHost extends GameActivity {
    private final Context owner;
    private final Context game;
    private final File storage;
    private final Window window;
    private final ClassLoader inputLoader;

    public PhoneGameHost(Context owner, Context game, File storage, Window window) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
        this.owner = owner;
        this.game = game;
        this.storage = storage;
        this.window = window;
        inputLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                // The native JNI input facade queries a provider by name. Supply our
                // explicit software-device port; keep all game bytecode unchanged.
                if (name.equals("jp.konami.android.common.GameController")) return BotGamepad.class;
                return super.loadClass(name);
            }
        };
        attachBaseContext(owner);
        setIntent(new Intent(Intent.ACTION_MAIN));
        Field activity = GameActivity.class.getDeclaredField("_activity");
        activity.setAccessible(true);
        if (activity.get(null) != null) throw new IllegalStateException("Game instance already exists");
        activity.set(null, this);
        Field assets = GameActivity.class.getDeclaredField("AssetManagerReference");
        assets.setAccessible(true);
        assets.set(this, game.getAssets());
    }

    @Override public ClassLoader getClassLoader() { return inputLoader == null ? getClass().getClassLoader() : inputLoader; }
    @Override public AssetManager getAssets() { return game == null ? super.getAssets() : game.getAssets(); }
    @Override public Resources getResources() { return game == null ? super.getResources() : game.getResources(); }
    @Override public ApplicationInfo getApplicationInfo() { return game.getApplicationInfo(); }
    @Override public String getPackageName() { return "jp.konami.pesam"; }
    @Override public String getPackageCodePath() { return game.getPackageCodePath(); }
    @Override public String getPackageResourcePath() { return game.getPackageResourcePath(); }
    @Override public Context getApplicationContext() { return owner; }
    // Services retain PeerLink's real UID and attribution, even when game helpers query game metadata.
    @Override public Object getSystemService(String name) { return owner.getSystemService(name); }
    @Override public Window getWindow() { return window; }
    @Override public android.view.WindowManager getWindowManager() {
        return (android.view.WindowManager)owner.getSystemService(Context.WINDOW_SERVICE);
    }
    @Override public File getFilesDir() { return directory("internal"); }
    @Override public File getCacheDir() { return directory("cache"); }
    @Override public File getExternalFilesDir(String type) { return directory(type == null ? "external" : "external/" + type); }
    @Override public File getObbDir() { return directory("obb"); }
    private File directory(String name) {
        File result = new File(storage, name);
        if (!result.isDirectory() && !result.mkdirs()) throw new IllegalStateException("Cannot create bot storage");
        return result;
    }

    // Only this isolated engine sees the software controller. No InputManager hooks or screen events.
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

    public void bindStorage(String project) {
        nativeSetGlobalActivity(false, false, getFilesDir().getAbsolutePath(),
            getExternalFilesDir(null).getAbsolutePath(), false, game.getPackageCodePath());
    }
    public void resumeAssets() { nativeResumeMainInit(); }
    public void resumeGame() throws Exception {
        Class.forName("jp.konami.AndroidUtil", true, getClassLoader()).getMethod("OnResumeCallback").invoke(null);
    }
}

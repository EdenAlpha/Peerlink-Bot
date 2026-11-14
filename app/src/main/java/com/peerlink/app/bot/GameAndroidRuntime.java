package com.peerlink.app.bot;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Application;
import android.app.NativeActivity;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.view.ContextThemeWrapper;
import android.view.SurfaceHolder;
import android.view.Surface;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.content.pm.ApplicationInfo;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import dalvik.system.PathClassLoader;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Framework-created NativeCode with an app-owned offscreen surface, inside the bot service. */
public final class GameAndroidRuntime implements AutoCloseable {
    private static volatile GameAndroidRuntime current;
    private final Context game;
    private final File storage;
    private final ImageReader frames;
    private final Instrumentation lifecycle = new Instrumentation();
    private final NativeActivity activity;
    private final SurfaceHolder surface;
    private final ClassLoader loader;
    private final CompletableFuture<Activity> attached = new CompletableFuture<>();

    private GameAndroidRuntime(Context owner, Context game, File root) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Android main thread required");
        this.game = game; storage = root;
        frames = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3);
        frames.setOnImageAvailableListener(reader -> {
            try (Image image = reader.acquireLatestImage()) { /* Drain without displaying frames. */ }
        }, new Handler(Looper.getMainLooper()));
        surface = new OffscreenSurface(frames.getSurface());
        try {
            File bridge = new File(root, "game-host-bridge.zip");
            File pending = File.createTempFile(".bridge-", ".zip", root);
            try {
                try (InputStream input = owner.getAssets().open("game-host/bridge.zip");
                     FileOutputStream output = new FileOutputStream(pending)) {
                    if (!pending.setReadOnly()) throw new IOException("Cannot protect game bridge");
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                    output.getFD().sync();
                }
                if (!pending.renameTo(bridge)) throw new IOException("Cannot save game bridge");
            } finally { pending.delete(); }
            ApplicationInfo info = game.getApplicationInfo();
            ArrayList<String> paths = new ArrayList<>(); paths.add(bridge.getAbsolutePath()); paths.add(info.sourceDir);
            if (info.splitSourceDirs != null) paths.addAll(Arrays.asList(info.splitSourceDirs));
            String libraries = new File(owner.getFilesDir(), "engine").getAbsolutePath() + File.pathSeparator + owner.getApplicationInfo().nativeLibraryDir;
            if (info.nativeLibraryDir != null) libraries += File.pathSeparator + info.nativeLibraryDir;
            loader = new InputLoader(String.join(File.pathSeparator, paths), libraries, owner.getClassLoader());
            current = this;
            ComponentName component = new ComponentName(owner.getPackageName(), "com.peerlink.engine.PhoneGameHost");
            Intent launch = new Intent().setComponent(component);
            ActivityInfo infoForHost = owner.getPackageManager().getActivityInfo(component, PackageManager.GET_META_DATA);
            Context themed = new ContextThemeWrapper(owner, android.R.style.Theme_NoTitleBar_Fullscreen);
            // This public API attaches a real framework Window and Activity
            // context without asking ActivityManager to launch a display task.
            activity = (NativeActivity)lifecycle.newActivity(loader.loadClass(component.getClassName()),
                themed, new Binder(), (Application)owner.getApplicationContext(), launch,
                infoForHost, "PeerLink bot", null, null, null);
            activity.setTheme(android.R.style.Theme_NoTitleBar_Fullscreen);
            activity.getClass().getMethod("onCreate", android.os.Bundle.class).invoke(activity, new Object[]{null});
            lifecycle.callActivityOnStart(activity);
            lifecycle.callActivityOnResume(activity);
            // NativeActivity's public SurfaceHolder callbacks allocate/attach
            // the real ANativeWindow from our own Surface. No screen is used.
            activity.surfaceCreated(surface);
            activity.surfaceChanged(surface, PixelFormat.RGBA_8888, 320, 180);
            activity.onWindowFocusChanged(true);
        } catch (Exception | LinkageError error) {
            android.util.Log.e("PeerlinkEngineHost", "Framework host startup failed", error);
            // NativeActivity may already own worker threads which still need
            // their class loader until the isolated service process is stopped.
            frames.close(); throw error;
        }
    }
    private static final class InputLoader extends PathClassLoader {
        InputLoader(String dex, String libraries, ClassLoader parent) { super(dex, libraries, parent); }
        @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (name.equals("jp.konami.android.common.GameController")) return super.loadClass("com.peerlink.engine.BotGamepad");
            return super.loadClass(name);
        }
    }
    static GameAndroidRuntime create(Context owner, Context game, File root) throws Exception {
        GameAndroidRuntime runtime = EngineAndroidHost.onMainThread(() -> new GameAndroidRuntime(owner, game, root));
        try { runtime.attached.get(60, TimeUnit.SECONDS); return runtime; }
        catch (Exception error) { runtime.close(); throw error; }
    }
    public static ClassLoader gameLoader() { return requireCurrent().loader; }
    public static Context gameContext() { return requireCurrent().game; }
    public static File storageRoot() { return requireCurrent().storage; }
    public static void activityCreated(Activity activity) { requireCurrent().attached.complete(activity); }
    public static void activityFailed(Throwable error) { requireCurrent().attached.completeExceptionally(error); }
    private static GameAndroidRuntime requireCurrent() {
        GameAndroidRuntime value = current;
        if (value == null) throw new IllegalStateException("Bot service must prepare its native host first");
        return value;
    }
    android.app.NativeActivity activity() { return (android.app.NativeActivity)attached.getNow(null); }
    @Override public void close() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                activity.onWindowFocusChanged(false);
                activity.surfaceDestroyed(surface);
                lifecycle.callActivityOnPause(activity);
                lifecycle.callActivityOnStop(activity);
                lifecycle.callActivityOnDestroy(activity);
            } finally {
                frames.close();
                if (current == this) current = null;
            }
        });
    }
    /** A Surface producer owned only by the bot; never an Android screen input queue. */
    private static final class OffscreenSurface implements SurfaceHolder {
        private final Surface surface;
        OffscreenSurface(Surface value) { surface = value; }
        @Override public Surface getSurface() { return surface; }
        @Override public Rect getSurfaceFrame() { return new Rect(0, 0, 320, 180); }
        @Override public boolean isCreating() { return false; }
        @Override public void addCallback(Callback callback) { }
        @Override public void removeCallback(Callback callback) { }
        @Override public void setType(int type) { }
        @Override public void setFixedSize(int width, int height) { }
        @Override public void setSizeFromLayout() { }
        @Override public void setFormat(int format) { }
        @Override public void setKeepScreenOn(boolean keepOn) { }
        @Override public Canvas lockCanvas() { return surface.lockCanvas(null); }
        @Override public Canvas lockCanvas(Rect dirty) { return surface.lockCanvas(dirty); }
        @Override public Canvas lockHardwareCanvas() { return surface.lockHardwareCanvas(); }
        @Override public void unlockCanvasAndPost(Canvas canvas) { surface.unlockCanvasAndPost(canvas); }
    }
}

package com.peerlink.app.bot;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import dalvik.system.PathClassLoader;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** A real OS-created NativeActivity on an app-owned private display. */
public final class GameAndroidRuntime implements AutoCloseable {
    private static volatile GameAndroidRuntime current;
    private final Context game;
    private final File storage;
    private final ImageReader frames;
    private final VirtualDisplay display;
    private final ClassLoader loader;
    private final CompletableFuture<Activity> attached = new CompletableFuture<>();

    private GameAndroidRuntime(Context owner, Context game, File root) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Android main thread required");
        this.game = game; storage = root;
        frames = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3);
        frames.setOnImageAvailableListener(reader -> {
            try (Image image = reader.acquireLatestImage()) { /* Drain without displaying frames. */ }
        }, new Handler(Looper.getMainLooper()));
        VirtualDisplay created = null;
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
            DisplayManager manager = (DisplayManager)owner.getSystemService(Context.DISPLAY_SERVICE);
            created = manager.createVirtualDisplay("PeerLink bot", 320, 180, 160, frames.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION |
                DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL);
            if (created == null) throw new IllegalStateException("Could not create bot display");
            display = created;
            current = this;
            Intent launch = new Intent().setClassName(owner.getPackageName(), "com.peerlink.engine.PhoneGameHost")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplay().getDisplayId());
            owner.startActivity(launch, options.toBundle());
        } catch (Exception | LinkageError error) {
            if (created != null) created.release(); frames.close(); current = null; throw error;
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
        if (value == null) throw new IllegalStateException("Bot service must prepare its private display first");
        return value;
    }
    android.app.NativeActivity activity() { return (android.app.NativeActivity)attached.getNow(null); }
    @Override public void close() {
        new Handler(Looper.getMainLooper()).post(() -> {
            Activity activity = attached.getNow(null);
            if (activity != null) activity.finishAndRemoveTask();
            display.release(); frames.close();
            if (current == this) current = null;
        });
    }
}

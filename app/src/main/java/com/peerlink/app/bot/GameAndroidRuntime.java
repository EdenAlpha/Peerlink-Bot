package com.peerlink.app.bot;

import android.app.Dialog;
import android.app.NativeActivity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import dalvik.system.PathClassLoader;
import java.io.*;
import java.util.*;

/** Holds a draining offscreen surface and the original GameActivity in the engine process. */
final class GameAndroidRuntime implements AutoCloseable {
    private final ImageReader frames;
    private final NativeActivity activity;
    private final Class<?> hostType;
    private final Dialog windowOwner;

    private GameAndroidRuntime(Context owner, Context game, File root) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Android main thread required");
        frames = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3);
        frames.setOnImageAvailableListener(reader -> {
            try (Image image = reader.acquireLatestImage()) { /* Consume frames without displaying them. */ }
        }, new Handler(Looper.getMainLooper()));
        windowOwner = new Dialog(owner, android.R.style.Theme_Material_Light_NoActionBar);
        try {
            File bridge = new File(root, "game-host-bridge.zip");
            // Android 14+ requires dynamically loaded code to be read-only before loading.
            File pending = File.createTempFile(".bridge-", ".zip", root);
            try {
                try (InputStream input = owner.getAssets().open("game-host/bridge.zip");
                     FileOutputStream output = new FileOutputStream(pending)) {
                    if (!pending.setReadOnly()) throw new IOException("Cannot protect game bridge");
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.getFD().sync();
                }
                if (!pending.renameTo(bridge)) throw new IOException("Cannot commit game bridge");
            } finally { pending.delete(); }
            ApplicationInfo info = game.getApplicationInfo();
            ArrayList<String> paths = new ArrayList<>(); paths.add(bridge.getAbsolutePath()); paths.add(info.sourceDir);
            if (info.splitSourceDirs != null) paths.addAll(Arrays.asList(info.splitSourceDirs));
            String libraryPath = new File(owner.getFilesDir(), "engine").getAbsolutePath();
            if (info.nativeLibraryDir != null) libraryPath += File.pathSeparator + info.nativeLibraryDir;
            PathClassLoader loader = new PathClassLoader(String.join(File.pathSeparator, paths), libraryPath,
                owner.getClassLoader());
            hostType = Class.forName("com.peerlink.engine.PhoneGameHost", true, loader);
            activity = (NativeActivity)hostType.getConstructor(Context.class, Context.class, File.class,
                android.view.Window.class).newInstance(owner, game, root, windowOwner.getWindow());
            if (activity.getClass().getSuperclass().getClassLoader() != loader)
                throw new IllegalStateException("Original GameActivity was replaced");
            Class.forName("jp.konami.android.common.FirebaseAnalyticsImpl", true, loader)
                .getMethod("onCreate", Context.class).invoke(null, activity);
            hostType.getMethod("bindStorage", String.class).invoke(activity, new File(root, "project/PesMobile").getAbsolutePath());
        } catch (Exception | LinkageError error) { frames.close(); throw error; }
    }

    static GameAndroidRuntime create(Context owner, Context game, File root) throws Exception {
        return EngineAndroidHost.onMainThread(() -> new GameAndroidRuntime(owner, game, root));
    }
    NativeActivity activity() { return activity; }
    Surface surface() { return frames.getSurface(); }
    void resumeAssets() throws Exception { hostType.getMethod("resumeAssets").invoke(activity); }
    void resumeGame() throws Exception { hostType.getMethod("resumeGame").invoke(activity); }
    @Override public void close() { frames.close(); }
}

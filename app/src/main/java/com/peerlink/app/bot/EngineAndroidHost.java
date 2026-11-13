package com.peerlink.app.bot;

import android.app.NativeActivity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** Real Android receiver for the engine's JNI helpers, without starting its UI. */
public final class EngineAndroidHost extends NativeActivity {
    private static final String GAME_PACKAGE = "jp.konami.pesam";
    private final ClassLoader gameLoader;

    /** The context supplies real OS services; the loader supplies the game's code. */
    public EngineAndroidHost(Context context, ClassLoader loader) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Construct the engine Android host on the main thread");
        }
        if (context == null || loader == null) {
            throw new IllegalArgumentException("Android application context and game loader required");
        }
        Context application = context.getApplicationContext();
        attachBaseContext(application != null ? application : context);
        gameLoader = loader;
    }

    @Override public ClassLoader getClassLoader() {
        return gameLoader;
    }

    /** Uses read-only installed APKs; no game class is copied or replaced. */
    public static EngineAndroidHost fromInstalledGame(Context context) throws Exception {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        ApplicationInfo game = app.getPackageManager().getApplicationInfo(GAME_PACKAGE, 0);
        ArrayList<String> apks = new ArrayList<>();
        addApk(apks, game.sourceDir);
        if (game.splitSourceDirs != null) {
            for (String path : game.splitSourceDirs) addApk(apks, path);
        }
        String dexPath = String.join(File.pathSeparator, apks);
        // Keep Android's existing native-library namespaces. Adding every system
        // directory here causes duplicate Android libraries and TLS/ABI failures.
        ClassLoader loader = new PathClassLoader(dexPath, app.getClassLoader());
        Class<?> http = Class.forName("jp.konami.android.common.HttpImpl", false, loader);
        if (!Thread.class.isAssignableFrom(http) || http.getClassLoader() != loader) {
            throw new IllegalStateException("The original game HTTP class is not available");
        }
        http.getConstructor(boolean.class);
        http.getMethod("SendRequest", String.class, boolean.class, String.class,
                byte[].class, boolean.class);
        http.getMethod("IsEnd");
        http.getMethod("IsError");
        http.getMethod("GetReceivedData");
        final Context application = app;
        return onMainThread(() -> new EngineAndroidHost(application, loader));
    }

    public static <T> T onMainThread(Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        if (!new Handler(Looper.getMainLooper()).post(task)) {
            throw new IllegalStateException("Android main thread is shutting down");
        }
        try {
            return task.get(15, TimeUnit.SECONDS);
        } finally {
            if (!task.isDone()) task.cancel(false);
        }
    }

    private static void addApk(ArrayList<String> apks, String path) {
        if (path == null || !new File(path).isFile() || !new File(path).canRead()) {
            throw new IllegalStateException("An installed game APK is not readable");
        }
        apks.add(path);
    }
}

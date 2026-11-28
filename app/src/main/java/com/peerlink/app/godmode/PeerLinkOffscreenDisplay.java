package com.peerlink.app.godmode;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import com.peerlink.app.bot.BotRenderSurface;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/** Owns the private display until the room or diagnostic recorder releases it. */
public final class PeerLinkOffscreenDisplay {
    private static final int SHELL_UID = 2000;
    // Framework's Android 15 DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED.
    private static final int TRUSTED = 1 << 10;
    private static final int DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int OWN_FOCUS = 1 << 14;
    private static final int STEAL_TOP_FOCUS_DISABLED = 1 << 16;
    // Display.getFlags() uses different bits from the virtual display request.
    private static final int DISPLAY_STEAL_TOP_FOCUS_DISABLED = 1 << 12;
    private static final int WIDTH = 320;
    private static final int HEIGHT = 480;
    private static final int DENSITY_DPI = 160;
    private static final int MAX_LIFETIME_SECONDS = 1_020;
    private static final long ROOM_LEASE_MS = 65_000L;

    public static void main(String[] args) throws Exception {
        boolean room = args.length == 2 && "room".equals(args[1]);
        if ((!room && args.length != 3) || Process.myUid() != SHELL_UID) {
            throw new IllegalArgumentException("Run the display helper from Prime Shell.");
        }
        File directory = new File(args[0]);
        DisplayManager manager = shellDisplayManager();
        HandlerThread frames = new HandlerThread("PeerLink-display-frames");
        frames.start();
        boolean published = false;
        try (BotRenderSurface surface = new BotRenderSurface(WIDTH, HEIGHT, new Handler(frames.getLooper()))) {
            // The display has no physical output and only renders its own content.
            // Android checks that the shell has ADD_TRUSTED_DISPLAY.
            boolean separateFocus = room && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | TRUSTED;
            if (room) flags |= DESTROY_CONTENT_ON_REMOVAL;
            if (separateFocus) flags |= OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED;
            VirtualDisplay display = manager.createVirtualDisplay(
                    "PeerLink off-screen", WIDTH, HEIGHT, DENSITY_DPI, surface.getSurface(), flags);
            if (display == null) {
                throw new IllegalStateException("Android refused to allocate a virtual display.");
            }
            try {
                if (separateFocus && (display.getDisplay().getFlags() & DISPLAY_STEAL_TOP_FOCUS_DISABLED) == 0) {
                    throw new IllegalStateException("Android did not keep the main screen's focus.");
                }
                publishDisplayId(directory, display.getDisplay().getDisplayId());
                published = true;
                if (room) awaitRoomEnd(directory);
                else awaitRecorderEnd(directory, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            } finally {
                display.release();
            }
        } finally {
            frames.quitSafely();
            if (room && published) {
                new File(directory, "display-id").delete();
                if (new File(directory, "lease").exists()) cleanRoomFiles(directory);
            }
        }
    }

    private static void awaitRoomEnd(File directory) throws InterruptedException {
        File lease = new File(directory, "lease");
        File stop = new File(directory, "display-stop");
        long updated = lease.lastModified();
        long deadline = SystemClock.elapsedRealtime() + ROOM_LEASE_MS;
        while (updated != 0 && !stop.exists() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(500);
            long current = lease.lastModified();
            if (current != updated) {
                updated = current;
                deadline = SystemClock.elapsedRealtime() + ROOM_LEASE_MS;
            }
        }
    }

    private static void awaitRecorderEnd(File directory, int lifetime, int ownerPid) throws InterruptedException {
        if (lifetime < 1 || lifetime > MAX_LIFETIME_SECONDS || ownerPid < 2) {
            throw new IllegalArgumentException("Invalid display lifetime or recorder PID.");
        }
        File recorder = new File("/proc/" + ownerPid);
        File stop = new File(directory, "display-stop");
        long deadline = SystemClock.elapsedRealtime() + 1_000L * lifetime;
        while (recorder.isDirectory() && directory.isDirectory() && !stop.exists()
                && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(250);
        }
    }

    private static void cleanRoomFiles(File directory) {
        new File(directory, "display-id.pending").delete();
        new File(directory, "display-stop").delete();
        new File(directory, "helper-pid").delete();
        new File(directory, "display-helper.log").delete();
        new File(directory, "launch.log").delete();
        new File(directory, "lease").delete();
        directory.delete();
    }

    private static DisplayManager shellDisplayManager() throws ReflectiveOperationException {
        Context shellIdentity = new ContextWrapper(null) {
            @Override public String getPackageName() {
                return "com.android.shell";
            }

            @Override public String getOpPackageName() {
                return getPackageName();
            }
        };
        // app_process starts with the shell UID and without an application Context.
        return DisplayManager.class.getConstructor(Context.class).newInstance(shellIdentity);
    }

    private static void publishDisplayId(File directory, int displayId) throws IOException {
        File pending = new File(directory, "display-id.pending");
        try (FileWriter output = new FileWriter(pending)) {
            output.write(Integer.toString(displayId));
            output.write("\n");
        }
        if (!pending.renameTo(new File(directory, "display-id"))) {
            throw new IOException("Could not publish the virtual display ID.");
        }
    }
}

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import com.peerlink.app.bot.BotRenderSurface;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/** Creates one shell-owned display on a private surface for the priority check. */
public final class PeerLinkOffscreenDisplay {
    private static final int SHELL_UID = 2000;
    // Framework's Android 15 DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED.
    private static final int TRUSTED = 1 << 10;
    private static final int WIDTH = 320;
    private static final int HEIGHT = 480;
    private static final int DENSITY_DPI = 160;
    private static final long MAX_LIFETIME_MS = 180_000;

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || Process.myUid() != SHELL_UID) {
            throw new IllegalArgumentException("Run the display check from Prime Shell.");
        }
        File directory = new File(args[0]);
        File stop = new File(directory, "display-stop");
        DisplayManager manager = shellDisplayManager();
        HandlerThread frames = new HandlerThread("PeerLink-display-frames");
        frames.start();
        try (BotRenderSurface surface = new BotRenderSurface(WIDTH, HEIGHT, new Handler(frames.getLooper()))) {
            // The display has no physical output and only renders its own content.
            // Android checks that the shell has ADD_TRUSTED_DISPLAY.
            VirtualDisplay display = manager.createVirtualDisplay(
                    "PeerLink off-screen", WIDTH, HEIGHT, DENSITY_DPI, surface.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | TRUSTED);
            if (display == null) {
                throw new IllegalStateException("Android refused to allocate a virtual display.");
            }
            try {
                publishDisplayId(directory, display.getDisplay().getDisplayId());
                long deadline = SystemClock.elapsedRealtime() + MAX_LIFETIME_MS;
                while (directory.isDirectory() && !stop.exists()
                        && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(250);
                }
            } finally {
                display.release();
            }
        } finally {
            frames.quitSafely();
        }
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

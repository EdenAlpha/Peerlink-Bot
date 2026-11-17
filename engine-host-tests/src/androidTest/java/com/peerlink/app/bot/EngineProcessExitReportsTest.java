package com.peerlink.app.bot;

import android.app.ApplicationExitInfo;
import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EngineProcessExitReportsTest {
    @Test public void actualChildProcessExitIsRecoveredAndStillAvailableAtLaterExport() throws Exception {
        checkExit(2);
    }
    @Test public void nativeCrashExportIncludesTheActualCrashingFunction() throws Exception {
        checkExit(3);
    }
    private void checkExit(int command) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        long startedAt = System.currentTimeMillis();
        AtomicInteger childPid = new AtomicInteger();
        AtomicReference<Messenger> remote = new AtomicReference<>();
        AtomicReference<String> recorded = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1), identified = new CountDownLatch(1), observed = new CountDownLatch(1);
        Messenger replies = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) {
                childPid.set(message.arg1);
                identified.countDown();
            }
        });
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote.set(new Messenger(binder));
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                context.unbindService(this);
                EngineProcessExitReports.observe(context, childPid.get(), startedAt, line -> {
                    if (!line.contains("reason=not_available")) {
                        recorded.set(line);
                        observed.countDown();
                    }
                });
            }
        };
        assertTrue(context.bindService(new Intent(context, EngineExitProbeService.class),
                connection, Context.BIND_AUTO_CREATE));
        try {
            assertTrue("Child service did not bind", connected.await(10, TimeUnit.SECONDS));
            Message identify = Message.obtain(null, 1);
            identify.replyTo = replies;
            remote.get().send(identify);
            assertTrue("Child process did not identify itself", identified.await(10, TimeUnit.SECONDS));
            assertNotEquals(android.os.Process.myPid(), childPid.get());
            remote.get().send(Message.obtain(null, command));
            assertTrue("Android exit reason never arrived", observed.await(40, TimeUnit.SECONDS));
            assertTrue(recorded.get(), recorded.get().contains("pid=" + childPid.get() + " "));
            ApplicationExitInfo exit = EngineProcessExitReports.read(context).stream()
                    .filter(item -> item.getPid() == childPid.get() && item.getTimestamp() >= startedAt)
                    .findFirst().orElseThrow(() -> new AssertionError("Exit history lost the child record"));
            assertTrue(recorded.get(), recorded.get().contains("reasonCode=" + exit.getReason()));
            // This fresh query is independent of the disconnect callback and
            // works after the bot is already gone, as the user's next export will be.
            String laterExport = EngineProcessExitReports.export(context);
            assertTrue(laterExport, laterExport.contains(EngineProcessExitReports.format(exit) + " lookup=export"));
            assertFalse(laterExport, laterExport.contains("queryError="));
            if (command == 3) {
                assertEquals(laterExport, ApplicationExitInfo.REASON_CRASH_NATIVE, exit.getReason());
                assertTrue(laterExport, laterExport.contains("signal=SIGABRT"));
                assertTrue(laterExport, laterExport.contains("module=libpeerlinkpatchtests.so"));
                assertTrue(laterExport, laterExport.contains("EngineExitProbeService_nativeCrash"));
                assertFalse(laterExport, laterExport.contains("nativeTraceError="));
            }
        } finally {
            try { context.unbindService(connection); } catch (IllegalArgumentException ignored) {}
        }
    }
}

package com.peerlink.app.bot;

import android.app.NativeActivity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Looper;
import android.os.PowerManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.Callable;
import static org.junit.Assert.*;

/** Genuine Android framework/Binder tests. No mocked services or game replies. */
@RunWith(AndroidJUnit4.class)
public class EngineAndroidHostTest {
    @Test public void attachedHostCanCallRealSystemServicesBeforeNativeGameStartup() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EngineAndroidHost host = EngineAndroidHost.onMainThread(() ->
                new EngineAndroidHost(context, context.getClassLoader()));
        assertNotNull(host.getBaseContext());
        ConnectivityManager network = (ConnectivityManager)host.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNotNull(network);
        // Binder calls into the emulator's actual Android services. A null
        // network is valid offline; unavailable services or permission errors fail.
        network.getActiveNetwork();
        PowerManager power = (PowerManager)host.getSystemService(Context.POWER_SERVICE);
        assertNotNull(power);
        power.isInteractive();
        assertNotNull(host.getAssets());
        assertSame(context.getClassLoader(), NativeActivity.class.getMethod("getClassLoader").invoke(host));
    }

    @Test public void mainThreadHandoffUsesTheActualAndroidMainLooper() throws Exception {
        Boolean onMain = EngineAndroidHost.onMainThread((Callable<Boolean>)() ->
                Looper.myLooper() == Looper.getMainLooper());
        assertTrue(onMain);
    }

    @Test public void constructingAnUnattachedHostOnAWorkerIsRejected() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            // Activity's own constructor requires a Looper. Give this worker
            // one so the production host's MAIN-thread guard is what we test.
            Looper.prepare();
            try { new EngineAndroidHost(context, context.getClassLoader()); }
            catch (Throwable error) { failure.set(error); }
        }, "host-construction-test");
        worker.start(); worker.join(5000);
        assertFalse("Host constructor hung", worker.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException);
        assertTrue(failure.get().getMessage().contains("main thread"));
    }
}

package com.peerlink.app.bot;

import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ExecutablePatchTest {
    static { System.loadLibrary("peerlinkpatchtests"); }
    private static native String check(String path, boolean enforcing);

    @Test public void executesPatchedCodeWithSELinuxEnforcing() throws Exception {
        String mode;
        try (FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("getenforce"))) {
            mode = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("allowDisabledSELinux")))
            assertEquals("This regression must run with Android's memory protections enabled", "Enforcing", mode);
        File path = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "code-page.bin");
        try {
            String result = check(path.getAbsolutePath(), "Enforcing".equals(mode));
            android.util.Log.i("PeerlinkPatchTest", "SELinux=" + mode + " " + result);
            assertTrue(result, result.startsWith("OK legacy_errno="));
        } finally { path.delete(); }
    }
}

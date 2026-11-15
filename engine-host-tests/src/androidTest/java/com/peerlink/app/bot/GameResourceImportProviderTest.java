package com.peerlink.app.bot;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GameResourceImportProviderTest {
    private String shell(String command, byte[] bytes) throws Exception {
        ParcelFileDescriptor[] fds = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation().executeShellCommandRw(command);
        try (ParcelFileDescriptor outputFd = fds[1];
             FileOutputStream output = new FileOutputStream(outputFd.getFileDescriptor())) {
            try { output.write(bytes); } catch (java.io.IOException ignored) { /* denied command may close stdin */ }
        }
        try (ParcelFileDescriptor fd = fds[0];
             FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void shellCanCopyOnlyTheGrantedFileAndGrantExpires() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target = File.createTempFile("resource-import-", ".tmp", context.getCacheDir());
        byte[] bytes = "verified-game-pack".getBytes(StandardCharsets.UTF_8);
        Uri uri = GameResourceImportProvider.grant(context, target);
        try {
            String result = shell("content write --uri " + uri, bytes);
            assertFalse(result, result.contains("Exception"));
            assertEquals("verified-game-pack", Files.readString(target.toPath()));
            String read = shell("content read --uri " + uri, new byte[0]);
            assertFalse("A write grant exposed private bytes", read.contains("verified-game-pack"));
            GameResourceImportProvider.revoke(context, uri);
            shell("content write --uri " + uri, "unwanted replacement".getBytes(StandardCharsets.UTF_8));
            assertEquals("verified-game-pack", Files.readString(target.toPath()));
            assertThrows(java.io.FileNotFoundException.class, () ->
                context.getContentResolver().openFileDescriptor(uri, "w"));
        } finally {
            GameResourceImportProvider.revoke(context, uri);
            target.delete();
        }
    }
}

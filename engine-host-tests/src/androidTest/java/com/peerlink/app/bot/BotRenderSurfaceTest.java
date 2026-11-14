package com.peerlink.app.bot;

import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class BotRenderSurfaceTest {
    @Test(timeout = 10000) public void rgbProducerReproducesTheReportedFailureOfTheOldRgbaReader() throws Exception {
        try (ImageReader reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 3);
             ImageWriter writer = ImageWriter.newInstance(reader.getSurface(), 3, PixelFormat.RGB_888)) {
            Image produced = writer.dequeueInputImage();
            assertEquals(PixelFormat.RGB_888, produced.getFormat());
            writer.queueInputImage(produced);
            long deadline = System.nanoTime() + 5000000000L;
            while (System.nanoTime() < deadline) {
                try (Image unexpected = reader.acquireLatestImage()) {
                    if (unexpected != null) fail("The fixed RGBA reader accepted RGB888 unexpectedly");
                } catch (UnsupportedOperationException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains("0x3"));
                    assertTrue(expected.getMessage(), expected.getMessage().contains("0x1"));
                    return;
                }
                Thread.sleep(5);
            }
            fail("No RGB buffer reached the old RGBA reader");
        }
    }

    @Test(timeout = 20000) public void productionSurfaceDrainsRgbRgbxAndRgbaWithoutCpuPixelReads() throws Exception {
        HandlerThread callbacks = new HandlerThread("bot-surface-test"); callbacks.start();
        try {
            for (int format : new int[]{PixelFormat.RGB_888, PixelFormat.RGBX_8888, PixelFormat.RGBA_8888}) {
                try (BotRenderSurface surface = new BotRenderSurface(64, 64, new Handler(callbacks.getLooper()));
                     ImageWriter writer = ImageWriter.newInstance(surface.getSurface(), 3, format)) {
                    // More than maxImages: merely accepting the first buffer
                    // would miss a consumer that later stalls the game loop.
                    for (int frame = 0; frame < 8; frame++) {
                        Image produced = writer.dequeueInputImage();
                        assertEquals(format, produced.getFormat());
                        writer.queueInputImage(produced);
                        long deadline = System.nanoTime() + 5000000000L;
                        while (surface.drainedFrameCount() < frame + 1 && System.nanoTime() < deadline) Thread.sleep(5);
                        assertEquals("Frame did not drain for producer format " + format,
                                frame + 1, surface.drainedFrameCount());
                    }
                }
            }
        } finally { callbacks.quitSafely(); callbacks.join(5000); }
    }
}

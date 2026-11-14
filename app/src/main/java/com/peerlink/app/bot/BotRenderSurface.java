package com.peerlink.app.bot;

import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.view.Surface;

/** Drains the engine's own frames without displaying or reading their pixels. */
public final class BotRenderSurface implements AutoCloseable {
    private final ImageReader reader;
    private boolean closed;
    private long drainedFrames;

    public BotRenderSurface(int width, int height, Handler handler) {
        // EGL producers choose device-specific RGB/RGBX/RGBA formats. PRIVATE
        // accepts those opaque buffers, avoiding ImageReader's fixed-format
        // check and CPU pixel mapping. The bot consumes no rendered pixels.
        reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        reader.setOnImageAvailableListener(source -> {
            synchronized (this) {
                if (closed) return;
                try (Image image = source.acquireLatestImage()) {
                    if (image != null) drainedFrames++;
                }
            }
        }, handler);
    }

    public Surface getSurface() { return reader.getSurface(); }
    synchronized long drainedFrameCount() { return drainedFrames; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        reader.setOnImageAvailableListener(null, null);
        reader.close();
    }
}

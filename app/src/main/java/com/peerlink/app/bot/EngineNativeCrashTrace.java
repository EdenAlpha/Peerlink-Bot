package com.peerlink.app.bot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Decode only the signal and crashing thread's stack from Android's tombstone protobuf.
 * Field numbers: AOSP system/core/debuggerd/proto/tombstone.proto (Android 14/15).
 * Registers, memory dumps, logs, command lines and file descriptors are never exported. */
public final class EngineNativeCrashTrace {
    private EngineNativeCrashTrace() {}

    public static String read(InputStream input) throws IOException {
        if (input == null) return "nativeTrace=not_available\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > 8 * 1024 * 1024) return "nativeTrace=too_large\n";
            bytes.write(buffer, 0, count);
        }
        Reader root = new Reader(bytes.toByteArray());
        long tid = 0;
        List<Reader> threads = new ArrayList<>();
        StringBuilder result = new StringBuilder();
        while (root.next()) {
            if (root.field == 6 && root.wire == 0) tid = root.value;
            if (root.field == 10 && root.wire == 2) {
                Reader signal = root.message();
                while (signal.next()) {
                    if (signal.field == 1 && signal.wire == 0) result.append("signalNumber=").append(signal.value).append(' ');
                    if ((signal.field == 2 || signal.field == 4) && signal.wire == 2)
                        result.append(signal.field == 2 ? "signal=" : "signalCode=").append(signal.text()).append(' ');
                }
                result.append('\n');
            }
            if (root.field == 16 && root.wire == 2) {
                Reader entry = root.message();
                while (entry.next()) if (entry.field == 2 && entry.wire == 2) threads.add(entry.message());
            }
        }
        int frames = 0;
        for (Reader thread : threads) {
            long id = 0;
            String name = "";
            List<Reader> stack = new ArrayList<>();
            while (thread.next()) {
                if (thread.field == 1 && thread.wire == 0) id = thread.value;
                if (thread.field == 2 && thread.wire == 2) name = thread.text();
                if (thread.field == 4 && thread.wire == 2) stack.add(thread.message());
            }
            if (id != tid) continue;
            result.append("crashingThread=").append(name).append(" tid=").append(id).append('\n');
            for (Reader frame : stack) {
                if (frames >= 128) break;
                long pc = 0, offset = 0;
                String function = "", module = "", build = "";
                while (frame.next()) {
                    if (frame.field == 1 && frame.wire == 0) pc = frame.value;
                    if (frame.field == 5 && frame.wire == 0) offset = frame.value;
                    if (frame.wire == 2) {
                        if (frame.field == 4) function = frame.text();
                        if (frame.field == 6) module = frame.text();
                        if (frame.field == 8) build = frame.text();
                    }
                }
                module = module.substring(module.lastIndexOf('/') + 1);
                result.append("frame=").append(frames++).append(" module=").append(module)
                        .append(" relPc=0x").append(Long.toHexString(pc)).append(" function=").append(function)
                        .append(" functionOffset=").append(offset).append(" buildId=").append(build).append('\n');
            }
        }
        return "nativeTraceFrames=" + frames + "\n" + result;
    }

    private static final class Reader {
        final byte[] data;
        final int end;
        int cursor, field, wire, start, stop;
        long value;
        Reader(byte[] data) { this(data, 0, data.length); }
        Reader(byte[] data, int start, int end) { this.data = data; this.cursor = start; this.end = end; }
        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (cursor >= end) throw new IOException("Truncated protobuf");
                int b = data[cursor++] & 255;
                value |= (long)(b & 127) << shift;
                if ((b & 128) == 0) return value;
            }
            throw new IOException("Invalid protobuf integer");
        }
        boolean next() throws IOException {
            if (cursor >= end) return false;
            long tag = varint(); field = (int)(tag >>> 3); wire = (int)(tag & 7);
            if (field <= 0) throw new IOException("Invalid protobuf field");
            value = 0; start = stop = cursor;
            if (wire == 0) value = varint();
            else {
                long length = wire == 2 ? varint() : wire == 1 ? 8 : wire == 5 ? 4 : -1;
                if (length < 0 || length > end - cursor) throw new IOException("Invalid protobuf length");
                start = cursor; cursor += (int)length; stop = cursor;
            }
            return true;
        }
        Reader message() { return new Reader(data, start, stop); }
        String text() {
            return new String(data, start, Math.min(stop - start, 512), StandardCharsets.UTF_8)
                    .replace('\n', ' ').replace('\r', ' ');
        }
    }
}

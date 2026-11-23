package com.peerlink.app.bot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
        ProtoFields root = new ProtoFields(bytes.toByteArray());
        long tid = 0;
        List<ProtoFields> threads = new ArrayList<>();
        StringBuilder result = new StringBuilder();
        while (root.next()) {
            if (root.field == 6 && root.wire == 0) tid = root.value;
            if (root.field == 10 && root.wire == 2) {
                ProtoFields signal = root.message();
                while (signal.next()) {
                    if (signal.field == 1 && signal.wire == 0) result.append("signalNumber=").append(signal.value).append(' ');
                    if ((signal.field == 2 || signal.field == 4) && signal.wire == 2)
                        result.append(signal.field == 2 ? "signal=" : "signalCode=").append(signal.text()).append(' ');
                }
                result.append('\n');
            }
            if (root.field == 16 && root.wire == 2) {
                ProtoFields entry = root.message();
                while (entry.next()) if (entry.field == 2 && entry.wire == 2) threads.add(entry.message());
            }
        }
        int frames = 0;
        for (ProtoFields thread : threads) {
            long id = 0;
            String name = "";
            List<ProtoFields> stack = new ArrayList<>();
            while (thread.next()) {
                if (thread.field == 1 && thread.wire == 0) id = thread.value;
                if (thread.field == 2 && thread.wire == 2) name = thread.text();
                if (thread.field == 4 && thread.wire == 2) stack.add(thread.message());
            }
            if (id != tid) continue;
            result.append("crashingThread=").append(name).append(" tid=").append(id).append('\n');
            for (ProtoFields frame : stack) {
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
}

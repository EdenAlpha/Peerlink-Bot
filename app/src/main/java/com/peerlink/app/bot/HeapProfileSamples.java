package com.peerlink.app.bot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read allocation stacks and profiler health for the actual bot PID. */
final class HeapProfileSamples {
    static final class Result {
        boolean sawTarget;
        boolean hasStack;
        long latestDumpPacketNs;
        private boolean disconnected, overran, corrupted, guardrail, busy;
        private long clientError;

        String problem() {
            if (overran || clientError == 1) return "buffer_overrun";
            if (corrupted) return "buffer_corrupted";
            if (guardrail) return "profiler_guardrail";
            if (busy) return "concurrent_recording";
            if (clientError == 2) return "invalid_stack_bounds";
            if (clientError != 0) return "client_error_" + clientError;
            return disconnected ? "client_disconnected" : null;
        }
    }

    private static final class Sequence {
        final HashMap<Long, List<Long>> stacks = new HashMap<>();
        final Set<Long> mappedFrames = new HashSet<>();
        final Set<Long> allocations = new HashSet<>();
        boolean hasMappedAllocation() {
            for (long id : allocations) {
                List<Long> frames = stacks.get(id);
                if (frames != null) for (long frame : frames) if (mappedFrames.contains(frame)) return true;
            }
            return false;
        }
    }

    static Result inspect(InputStream stream, int pid) throws IOException {
        Result result = new Result();
        if (pid <= 0) return result;
        DataInputStream in = new DataInputStream(new BufferedInputStream(stream));
        HashMap<Long, Sequence> writers = new HashMap<>();
        byte[] buffer = new byte[8192];
        // A live file can end mid-packet. Only complete packets qualify.
        try {
            for (int tag; (tag = in.read()) != -1;) {
                if (tag != 10) throw new IOException("Invalid Perfetto trace packet");
                int length = packetLength(in);
                if (length > buffer.length) buffer = new byte[length];
                in.readFully(buffer, 0, length);
                ProtoFields packet = new ProtoFields(buffer, 0, length);
                long sequenceId = 0, packetTimeNs = 0;
                boolean reset = false;
                ProtoFields interned = null, profile = null;
                while (packet.next()) {
                    if (packet.field == 8 && packet.wire == 0) packetTimeNs = packet.value;
                    if (packet.field == 10) sequenceId = packet.value;
                    if ((packet.field == 13 && (packet.value & 1) != 0) || (packet.field == 41 && packet.value != 0)) reset = true;
                    if (packet.field == 12 && packet.wire == 2) interned = packet.message();
                    if (packet.field == 37 && packet.wire == 2) profile = packet.message();
                }
                if (reset) {
                    Sequence old = writers.remove(sequenceId);
                    if (old != null && old.hasMappedAllocation()) result.hasStack = true;
                }
                if (interned == null && profile == null) continue;
                Sequence writer = writers.computeIfAbsent(sequenceId, key -> new Sequence());
                if (interned != null) while (interned.next()) {
                    if (interned.field == 6 && interned.wire == 2) recordFrame(interned.message(), writer);
                    if (interned.field == 7 && interned.wire == 2) recordStack(interned.message(), writer);
                }
                if (profile != null) while (profile.next()) {
                    if (profile.wire != 2) continue;
                    if (profile.field == 2) recordFrame(profile.message(), writer);
                    if (profile.field == 3) recordStack(profile.message(), writer);
                    if (profile.field == 5) recordSamples(profile.message(), pid, writer, result, packetTimeNs);
                }
            }
        } catch (EOFException partialPacket) {
            // Completed packets may already contain a usable sample.
        }
        for (Sequence writer : writers.values()) if (writer.hasMappedAllocation()) result.hasStack = true;
        return result;
    }

    private static int packetLength(DataInputStream input) throws IOException {
        long size = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            int part = input.readUnsignedByte();
            size |= (long)(part & 127) << shift;
            if ((part & 128) == 0 && size <= 4L * 1024 * 1024) return (int)size;
            if ((part & 128) == 0) break;
        }
        throw new IOException("Oversized Perfetto packet");
    }

    private static void recordFrame(ProtoFields frame, Sequence writer) throws IOException {
        long id = 0, mapping = 0, pc = 0;
        while (frame.next()) {
            if (frame.field == 1) id = frame.value;
            if (frame.field == 3) mapping = frame.value;
            if (frame.field == 4) pc = frame.value;
        }
        if (id > 0 && mapping > 0 && pc > 0) writer.mappedFrames.add(id);
    }

    private static void recordStack(ProtoFields stack, Sequence writer) throws IOException {
        long id = 0;
        List<Long> frames = new ArrayList<>();
        while (stack.next()) {
            if (stack.field == 1) id = stack.value;
            if (stack.field == 2 && stack.wire == 0) frames.add(stack.value);
            if (stack.field == 2 && stack.wire == 2) {
                ProtoFields packed = stack.message();
                while (packed.cursor < packed.end) frames.add(packed.varint());
            }
        }
        if (!frames.isEmpty() && id > 0) writer.stacks.put(id, frames);
    }

    private static void recordSamples(ProtoFields dump, int pid, Sequence writer, Result result,
                                      long packetTimeNs) throws IOException {
        // Protobuf fields can arrive in any order. Check identity before reading samples.
        int start = dump.cursor;
        boolean target = false;
        while (dump.next()) if (dump.field == 1 && dump.value == pid) target = true;
        if (!target) return;
        result.sawTarget = true;
        result.latestDumpPacketNs = Math.max(result.latestDumpPacketNs, packetTimeNs);
        dump.cursor = start;
        while (dump.next()) {
            // A disconnect can arrive after valid samples, even in the very same dump.
            if (dump.wire == 0 && dump.value != 0) {
                switch (dump.field) {
                    case 4: result.busy = true; break;
                    case 6: result.disconnected = true; break;
                    case 7: result.overran = true; break;
                    case 8: result.corrupted = true; break;
                    case 10: result.guardrail = true; break;
                    case 14: result.clientError = dump.value; break;
                }
            }
            if (dump.field != 2 || dump.wire != 2) continue;
            ProtoFields sample = dump.message();
            long id = 0;
            boolean allocated = false;
            while (sample.next()) {
                if (sample.field == 1) id = sample.value;
                if ((sample.field == 2 || sample.field == 5) && sample.value > 0) allocated = true;
            }
            if (allocated && id > 0) writer.allocations.add(id);
        }
    }
}

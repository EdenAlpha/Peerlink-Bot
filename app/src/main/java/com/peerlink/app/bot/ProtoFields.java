package com.peerlink.app.bot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded view of fields in one protobuf message. */
final class ProtoFields {
    final byte[] data;
    final int end;
    int cursor, field, wire, start, stop;
    long value;
    ProtoFields(byte[] data) { this(data, 0, data.length); }
    ProtoFields(byte[] data, int start, int end) { this.data = data; this.cursor = start; this.end = end; }
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
    ProtoFields message() { return new ProtoFields(data, start, stop); }
    String text() {
        return new String(data, start, Math.min(stop - start, 512), StandardCharsets.UTF_8)
                .replace('\n', ' ').replace('\r', ' ');
    }
}

package com.peerlink.app.bot;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.InflaterInputStream;

/** Bounded reader for the installed build's CPK/UTF and WESYS database containers. */
public final class GameArchive implements Closeable {
    private final RandomAccessFile file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private static final int LIMIT = 64 * 1024 * 1024;
    private static final class Entry {
        long offset; int size;
        Entry(long o, int s) { offset = o; size = s; }
    }
    public GameArchive(File source) throws IOException {
        file = new RandomAccessFile(source, "r");
        try {
            byte[] magic = new byte[4]; file.readFully(magic);
            require(Arrays.equals(magic, new byte[]{'C','P','K',' '}), "Not a game CPK");
            Map<String,Object> header = table(0).get(0);
            long toc = number(header, "TocOffset"), content = number(header, "ContentOffset");
            long origin = Math.min(toc, content);
            for (Map<String,Object> row : table(toc)) {
                String dir = Objects.toString(row.get("DirName"), "");
                String name = Objects.toString(row.get("FileName"), "");
                long size = number(row, "FileSize"), decoded = number(row, "ExtractSize");
                if (size != decoded || size > LIMIT) continue;
                long position = Math.addExact(origin, number(row, "FileOffset"));
                require(position >= 0 && size >= 0 && position <= file.length() - size, "Invalid CPK entry range");
                entries.put(dir + "/" + name, new Entry(position, (int)size));
            }
        } catch (Throwable error) {
            file.close();
            if (error instanceof IOException) throw (IOException)error;
            throw new IOException("Invalid game archive", error);
        }
    }
    public byte[] read(String name) throws IOException {
        Entry entry = entries.get(name);
        if (entry == null) throw new IOException("Missing game data: " + name);
        byte[] result = new byte[entry.size]; file.seek(entry.offset); file.readFully(result);
        return result;
    }
    public Set<String> names() { return Collections.unmodifiableSet(entries.keySet()); }
    private List<Map<String,Object>> table(long position) throws IOException {
        require(position >= 0 && position <= file.length() - 16, "Invalid UTF position");
        file.seek(position + 8);
        long size = Long.reverseBytes(file.readLong());
        require(size >= 32 && size <= LIMIT && size <= file.length() - position - 16, "Invalid UTF size");
        byte[] data = new byte[(int)size]; file.readFully(data);
        if (data[0] != '@') {
            int stream = 0x655f;
            for (int i = 0; i < data.length; i++) { data[i] ^= (byte)stream; stream *= 0x4115; }
        }
        ByteBuffer input = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        require(input.getInt(0) == 0x40555446, "Invalid UTF header");
        int rows = input.getInt(8) + 8, strings = input.getInt(12) + 8;
        int columns = Short.toUnsignedInt(input.getShort(24));
        int stride = Short.toUnsignedInt(input.getShort(26)), count = input.getInt(28);
        require(columns > 0 && columns <= 256 && count >= 0 && count <= 200000,
            "Invalid UTF dimensions");
        require(rows >= 32 && strings >= rows && strings < data.length &&
            (long)rows + (long)stride * count <= strings, "Invalid UTF rows");
        int[] flags = new int[columns]; String[] names = new String[columns]; Object[] constants = new Object[columns];
        input.position(32);
        for (int i = 0; i < columns; i++) {
            flags[i] = Byte.toUnsignedInt(input.get());
            if (flags[i] == 0) { input.position(input.position() + 3); flags[i] = Byte.toUnsignedInt(input.get()); }
            names[i] = string(data, Math.addExact(strings, input.getInt()));
        }
        for (int i = 0; i < columns; i++) if ((flags[i] & 0xf0) == 0x30)
            constants[i] = value(input, flags[i] & 15, strings, data);
        require(input.position() <= rows, "UTF schema overlaps rows");
        ArrayList<Map<String,Object>> result = new ArrayList<>(count);
        for (int row = 0; row < count; row++) {
            input.position(rows + row * stride); Map<String,Object> values = new LinkedHashMap<>();
            for (int column = 0; column < columns; column++) {
                int storage = flags[column] & 0xf0;
                Object v;
                if (storage == 0) v = null;
                else if (storage == 0x10) v = 0L;
                else if (storage == 0x30) v = constants[column];
                else if (storage == 0x50) v = value(input, flags[column] & 15, strings, data);
                else throw new IOException("Unsupported UTF column storage");
                values.put(names[column], v);
            }
            require(input.position() <= rows + (row + 1) * stride, "UTF row overflow");
            result.add(values);
        }
        return result;
    }
    private static Object value(ByteBuffer b, int kind, int strings, byte[] data) throws IOException {
        switch (kind) {
            case 0: case 1: return (long)Byte.toUnsignedInt(b.get());
            case 2: case 3: return (long)Short.toUnsignedInt(b.getShort());
            case 4: case 5: return Integer.toUnsignedLong(b.getInt());
            case 6: case 7: return b.getLong();
            case 8: return b.getFloat();
            case 10: return string(data, Math.addExact(strings, b.getInt()));
            case 11: return new long[]{Integer.toUnsignedLong(b.getInt()), Integer.toUnsignedLong(b.getInt())};
            default: throw new IOException("Unsupported UTF type");
        }
    }
    private static long number(Map<String,Object> row, String key) throws IOException {
        Object value = row.get(key); require(value instanceof Number, "Missing CPK field " + key);
        return ((Number)value).longValue();
    }
    private static String string(byte[] data, int offset) throws IOException {
        require(offset >= 0 && offset < data.length, "Invalid UTF string");
        int end = offset; while (end < data.length && data[end] != 0) end++;
        require(end < data.length && end - offset <= 4096, "Unterminated UTF string");
        return new String(data, offset, end - offset, java.nio.charset.StandardCharsets.UTF_8);
    }
    public static byte[] decodeDatabase(byte[] source) throws IOException {
        require(source.length >= 16 && source[0] == (byte)255 &&
            source[3]=='W' && source[4]=='E' && source[5]=='S' && source[6]=='Y' && source[7]=='S', "Unknown database format");
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int compressed = input.getInt(8), expected = input.getInt(12);
        require(compressed >= 0 && compressed == source.length - 16 && expected >= 0 && expected <= LIMIT,
            "Invalid database dimensions");
        if (compressed == 0 && expected == 0) return new byte[0];
        int mode = source[1] & 15;
        require(mode == 1 || mode == 2, "Unsupported database codec");
        int x = mode == 1 ? 0x168ea000 : 0xed5b2960;
        int y = mode == 1 ? 0x2e2aa6f2 : 0x4a523b4e;
        int z = mode == 1 ? 0x0cc8dcd3 : 0xf3a31bad;
        int w = (expected << 16) | compressed;
        byte[] payload = Arrays.copyOfRange(source, 16, source.length);
        ByteBuffer words = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + 4 <= payload.length; i += 4) {
            int t = x ^ (x << 11); x = y; y = z; z = w;
            w ^= (w >>> 19) ^ t ^ (t >>> 8); words.putInt(i, words.getInt(i) ^ w);
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream(expected);
        try (InflaterInputStream inflate = new InflaterInputStream(new ByteArrayInputStream(payload))) {
            byte[] chunk = new byte[32768]; int n;
            while ((n = inflate.read(chunk)) != -1) {
                require(n <= expected - result.size(), "Database expands past declared size"); result.write(chunk, 0, n);
            }
        }
        require(result.size() == expected, "Database decode size mismatch"); return result.toByteArray();
    }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
    @Override public void close() throws IOException { file.close(); }
}

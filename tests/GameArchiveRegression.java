import com.peerlink.app.bot.GameArchive;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Integration oracle: original installed archive versus the lab's independently decoded tables. */
public final class GameArchiveRegression {
    public static void main(String[] args) throws Exception {
        int count = 0;
        try (GameArchive cpk = new GameArchive(new File(args[0]))) {
            for (File expected : Objects.requireNonNull(new File(args[1]).listFiles())) {
                if (!expected.getName().endsWith(".decoded")) continue;
                String name = expected.getName().replace(".decoded", ".bin");
                byte[] actual = GameArchive.decodeDatabase(cpk.read("common/etc/pesdb/" + name));
                if (!Arrays.equals(actual, Files.readAllBytes(expected.toPath()))) throw new AssertionError(name);
                count++;
            }
            try { cpk.read("../nonexistent"); throw new AssertionError("Missing entry accepted"); }
            catch (IOException expected) { }
        }
        if (count != 44) throw new AssertionError("Expected all 44 native tables, found " + count);
        for (byte[] invalid : new byte[][]{new byte[0], new byte[16], new byte[]{-1, 1, 0}}) {
            try { GameArchive.decodeDatabase(invalid); throw new AssertionError("Invalid container accepted"); }
            catch (IOException expected) { }
        }
        System.out.println("PASS: all 44 shipped tables match the independent lab output; invalid inputs rejected");
    }
}

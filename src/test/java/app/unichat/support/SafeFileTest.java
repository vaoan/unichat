package app.unichat.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SafeFileTest {
    @TempDir Path dir;

    private boolean posix() { return dir.getFileSystem().supportedFileAttributeViews().contains("posix"); }

    @Test
    void createsAndReplaces() throws Exception {
        Path f = dir.resolve("a.json");
        SafeFile.replace(f, new byte[]{1}, false);
        SafeFile.replace(f, new byte[]{2}, false);
        assertEquals(2, Files.readAllBytes(f)[0]);
        assertFalse(Files.exists(dir.resolve("a.json.tmp")));
    }

    @Test
    void secretsAreOnlyTheOwners() throws Exception {
        assumeTrue(posix());
        Path f = dir.resolve("secrets.properties");
        SafeFile.replace(f, new byte[]{1}, true);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
    }

    @Test
    void aLooseFileBecomesPrivate() throws Exception {
        assumeTrue(posix());
        Path f = dir.resolve("secrets.properties");
        Files.writeString(f, "old");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        SafeFile.replace(f, new byte[]{1}, true);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
    }

    /** A crash leaves a temp file behind: its loose mode, or a link in its place, must not decide anything. */
    @Test
    void aLeftoverTempDecidesNothing() throws Exception {
        assumeTrue(posix());
        Path f = dir.resolve("s");
        Path leftover = dir.resolve("s.tmp");
        Files.writeString(leftover, "old");
        Files.setPosixFilePermissions(leftover, PosixFilePermissions.fromString("rw-rw-rw-"));
        SafeFile.replace(f, new byte[]{1}, true);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));

        Path elsewhere = dir.resolve("elsewhere");
        Files.writeString(elsewhere, "mine");
        Files.createSymbolicLink(leftover, elsewhere);
        SafeFile.replace(f, new byte[]{2}, true);
        assertEquals("mine", Files.readString(elsewhere));
        assertEquals(2, Files.readAllBytes(f)[0]);
    }

    @Test
    void aFailureLeavesTheOldThingAndNoTemp() throws Exception {
        Path f = Files.createDirectories(dir.resolve("full"));
        Files.writeString(f.resolve("inside"), "keep");
        assertThrows(IOException.class, () -> SafeFile.replace(f, new byte[]{1}, false));
        assertTrue(Files.exists(f.resolve("inside")));
        assertFalse(Files.exists(dir.resolve("full.tmp")));
    }

    @Test
    void aFailureWhileWritingLeavesTheOldFileAndNoTemp() throws Exception {
        Path f = Files.writeString(dir.resolve("video.mp4"), "old");
        InputStream cut = new InputStream() {
            private int left = 3;

            @Override
            public int read() throws IOException {
                if (left-- > 0) return 'x';
                throw new IOException("cut short");
            }
        };
        assertThrows(IOException.class, () -> SafeFile.at(f, ".part").copy(cut));
        assertEquals("old", Files.readString(f));
        assertFalse(Files.exists(dir.resolve("video.mp4.part")));
    }

    @Test
    void aRefusalBeforeTheMoveLeavesTheOldFileAndNoTemp() throws Exception {
        Path f = Files.writeString(dir.resolve("uv"), "old");
        Path[] seen = new Path[1];
        assertThrows(IOException.class, () -> SafeFile.at(f, ".part").beforeMove(part -> {
            seen[0] = part;
            assertEquals("new", Files.readString(part), "the whole file, before it takes the name");
            throw new IOException("wrong checksum");
        }).write("new".getBytes()));
        assertEquals(dir.resolve("uv.part"), seen[0]);
        assertEquals("old", Files.readString(f));
        assertFalse(Files.exists(seen[0]));
    }

    @Test
    void aStreamIsCopiedAndLeftOpenThroughAHiddenTemp() throws Exception {
        Path f = dir.resolve("sub").resolve("a.bin");
        boolean[] closed = {false};
        InputStream in = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override
            public void close() { closed[0] = true; }
        };
        SafeFile file = SafeFile.at(f, ".part").hidden();
        assertEquals(dir.resolve("sub").resolve(".a.bin.part"), file.part());
        file.copy(in);
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(f));
        assertFalse(closed[0], "an archive's next entry may follow");
        assertFalse(Files.exists(file.part()));
    }

    /** Jackson closes what it writes to: the file is still synced and complete. */
    @Test
    void contentThatClosesItsStreamIsStillWrittenWhole() throws Exception {
        Path f = dir.resolve("q.json");
        SafeFile.at(f).write(out -> {
            out.write("[1]".getBytes());
            out.close();
        });
        assertEquals("[1]", Files.readString(f));
    }
}

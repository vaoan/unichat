package app.unichat.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivesTest {
    @TempDir
    Path dir;

    @Test
    void theProgramComesOutOfATarGzInsideItsFolder() throws IOException {
        Path tgz = dir.resolve("uv-aarch64-apple-darwin.tar.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tgz))) {
            tarEntry(out, "uv-aarch64-apple-darwin/", '5', new byte[0]);
            tarEntry(out, "uv-aarch64-apple-darwin/uvx", '0', "uvx".getBytes(StandardCharsets.UTF_8));
            tarEntry(out, "uv-aarch64-apple-darwin/uv", '0', "the real uv, a bit longer than one block? no".getBytes(StandardCharsets.UTF_8));
            out.write(new byte[1024]);
        }
        Path target = dir.resolve("out").resolve("uv");
        assertTrue(Archives.extractOne(tgz, "uv", target));
        assertEquals("the real uv, a bit longer than one block? no", Files.readString(target));
        assertFalse(Files.exists(dir.resolve("out").resolve("uvx")), "only the one asked for");
    }

    @Test
    void aBigEntryBeforeItIsSkippedWhole() throws IOException {
        Path tgz = dir.resolve("a.tar.gz");
        byte[] big = new byte[1500];
        java.util.Arrays.fill(big, (byte) 'x');
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tgz))) {
            tarEntry(out, "d/big", '0', big);
            tarEntry(out, "d/uv", '0', "ok".getBytes(StandardCharsets.UTF_8));
            out.write(new byte[1024]);
        }
        Path target = dir.resolve("uv");
        assertTrue(Archives.extractOne(tgz, "uv", target));
        assertEquals("ok", Files.readString(target));
    }

    @Test
    void aZipGivesItsEntryAndANameThatOnlyEndsLikeItIsNotTaken() throws IOException {
        Path zip = dir.resolve("uv.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("notuv.exe"));
            z.write("wrong".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("uv.exe"));
            z.write("right".getBytes(StandardCharsets.UTF_8));
        }
        Path target = dir.resolve("t").resolve("uv.exe");
        assertTrue(Archives.extractOne(zip, "uv.exe", target));
        assertEquals("right", Files.readString(target));
        assertFalse(Archives.extractOne(zip, "python.exe", dir.resolve("p.exe")));
    }

    /** A ustar header (the reader needs only name, size and type) and the data padded to 512. */
    private static void tarEntry(OutputStream out, String name, char type, byte[] data) throws IOException {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, h, 0, n.length);
        byte[] size = String.format("%011o", data.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, h, 124, size.length);
        h[156] = (byte) type;
        System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 5);
        out.write(h);
        out.write(data);
        out.write(new byte[(512 - data.length % 512) % 512]);
    }
}

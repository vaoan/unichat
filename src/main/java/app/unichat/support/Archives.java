package app.unichat.support;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One program out of a downloaded archive (.zip or .tar.gz). Only that entry is read, and always to {@code target}:
 * names inside an archive never decide where a file lands.
 */
public final class Archives {
    private static final int TAR_BLOCK = 512;

    private Archives() {}

    /**
     * Extracts the first regular file whose path is {@code entry} or ends with it.
     * @return false when there's none
     */
    public static boolean extractOne(Path archive, String entry, Path target) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".zip") ? fromZip(archive, entry, target) : fromTarGz(archive, entry, target);
    }

    private static boolean fromZip(Path zip, String entry, Path target) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (!e.isDirectory() && matches(e.getName(), entry)) {
                    copy(in, target);
                    return true;
                }
            }
        }
        return false;
    }

    /** A plain ustar reader: a 512-byte header (name, octal size, type, prefix), then the data padded to 512. */
    private static boolean fromTarGz(Path tgz, String entry, Path target) throws IOException {
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(tgz)))) {
            byte[] h = new byte[TAR_BLOCK];
            while (in.readNBytes(h, 0, TAR_BLOCK) == TAR_BLOCK && h[0] != 0) {
                String name = text(h, 0, 100);
                String prefix = text(h, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = Long.parseLong(text(h, 124, 12).isEmpty() ? "0" : text(h, 124, 12), 8);
                char type = (char) h[156];
                if ((type == '0' || type == 0) && matches(name, entry)) {
                    copy(new java.io.FilterInputStream(in) {
                        private long left = size;

                        @Override
                        public int read(byte[] b, int off, int len) throws IOException {
                            if (left <= 0) return -1;
                            int n = super.read(b, off, (int) Math.min(len, left));
                            if (n > 0) left -= n;
                            return n;
                        }

                        @Override
                        public void close() {
                            // The archive stays open for the caller.
                        }
                    }, target);
                    return true;
                }
                in.skipNBytes((size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK);
            }
        }
        return false;
    }

    private static boolean matches(String name, String entry) {
        return name.equals(entry) || name.endsWith(entry.startsWith("/") ? entry : "/" + entry);
    }

    private static String text(byte[] h, int from, int len) {
        int end = from;
        while (end < from + len && h[end] != 0) end++;
        return new String(h, from, end - from, StandardCharsets.US_ASCII).trim();
    }

    private static void copy(InputStream in, Path target) throws IOException {
        SafeFile.at(target, ".part").copy(in);
    }
}

package app.unichat.server;

import app.unichat.settings.Settings;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Log housekeeping so logs never fill the disk: Unichat's own log plus the local server's and its bridges'.
 * Only files named like logs are touched (never databases or configs). Retention: Ajustes → Privacidad.
 */
public final class LogFiles {
    /** unichat.log, tuwunel.log, bridge.log, whatsapp.out, their ".1" copies and the bridges' dated backups. */
    private static final Pattern LOG = Pattern.compile("(?i).+\\.(log|out)(\\.\\d+)?");
    /** Folders holding a database (tuwunel's RocksDB): never searched for logs. */
    private static final Set<String> DATABASE_DIRS = Set.of("db");
    /** Files a running process is writing to: emptied by {@link #clearAll}, not deleted. */
    private static final Pattern ACTIVE = Pattern.compile("(?i)(unichat|tuwunel|bridge)\\.log|[a-z]+\\.out");

    private LogFiles() {}

    static boolean isLog(Path file) {
        return LOG.matcher(file.getFileName().toString()).matches();
    }

    /** Unichat's logs folder and the local server's folder (when it exists). */
    public static List<Path> roots() {
        List<Path> out = new ArrayList<>();
        out.add(Settings.dataDir().resolve("logs"));
        Platform p = Platform.current();
        if (p != null) out.add(p.dataDir());
        return out;
    }

    /** Deletes logs not written for {@code maxAge}; returns how many. */
    public static int cleanOld(List<Path> roots, Duration maxAge, Instant now) {
        int n = 0;
        for (Path f : logs(roots)) {
            try {
                if (Files.getLastModifiedTime(f).toInstant().isBefore(now.minus(maxAge))) {
                    Files.delete(f);
                    n++;
                }
            } catch (IOException ignored) {
                // In use (Windows) or already gone: next round.
            }
        }
        return n;
    }

    /** "Borrar registros ahora": empties the logs in use and deletes the rest. */
    public static void clearAll(List<Path> roots) {
        for (Path f : logs(roots)) {
            try {
                if (ACTIVE.matcher(f.getFileName().toString()).matches()) {
                    try (FileChannel ch = FileChannel.open(f, StandardOpenOption.WRITE)) {
                        ch.truncate(0);
                    }
                } else {
                    Files.delete(f);
                }
            } catch (IOException ignored) {
                // Locked by a running process (Windows): it goes in the next cleanup.
            }
        }
    }

    /** Starts the daily cleanup with the retention from Settings (first run right away). */
    public static void scheduleCleanup() {
        Thread t = new Thread(() -> {
            while (true) {
                cleanOld(roots(), Duration.ofDays(Settings.get().logRetentionDays()), Instant.now());
                try {
                    Thread.sleep(Duration.ofHours(24));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "log-cleanup");
        t.setDaemon(true);
        t.start();
    }

    private static List<Path> logs(List<Path> roots) {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            // Logs sit at most two levels down (server/<network>/bridge.log). The homeserver's database folder is
            // never entered: its write-ahead files are named 000123.log and deleting them corrupts it.
            try {
                Files.walkFileTree(root, Set.of(), 3, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                        return DATABASE_DIRS.contains(dir.getFileName().toString())
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        if (a.isRegularFile() && isLog(f)) out.add(f);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path f, IOException e) {
                        // Gone meanwhile (the database compacts all the time) or unreadable: skip it.
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) {
                // Unreadable folder: skip.
            }
        }
        return out;
    }
}

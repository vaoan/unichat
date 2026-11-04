package app.unichat.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogFilesTest {
    @TempDir Path root;
    private final Instant now = Instant.parse("2026-10-26T12:00:00Z");

    private Path file(String rel, int daysOld) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "x".repeat(10));
        Files.setLastModifiedTime(f, FileTime.from(now.minus(Duration.ofDays(daysOld))));
        return f;
    }

    @Test
    void onlyLogFilesCount() {
        for (String n : new String[]{"unichat.log", "unichat.log.1", "tuwunel.log", "whatsapp.out", "whatsapp.out.1",
                "bridge.log", "bridge-2026-10-26T08-35-42.394.log"}) {
            assertTrue(LogFiles.isLog(Path.of(n)), n);
        }
        for (String n : new String[]{"whatsapp.db", "whatsapp.db-wal", "config.yaml", "secrets.properties",
                "settings.properties", "000123.sst", "LOG", "tuwunel.toml"}) {
            assertFalse(LogFiles.isLog(Path.of(n)), n);
        }
    }

    @Test
    void deletesLogsOlderThanTheRetention() throws Exception {
        Path old = file("logs/unichat.log.1", 10);
        Path oldBridge = file("server/whatsapp/bridge-2026-10-10T00-00-00.000.log", 16);
        Path recent = file("logs/unichat.log", 0);
        Path db = file("server/whatsapp/whatsapp.db", 400);
        Path wal = file("server/db/000123.log", 400);
        int n = LogFiles.cleanOld(List.of(root), Duration.ofDays(7), now);
        assertEquals(2, n);
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(oldBridge));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(db), "databases are never touched");
        assertTrue(Files.exists(wal), "tuwunel's write-ahead logs are part of its database");
    }

    @Test
    void clearAllEmptiesActiveLogsAndDeletesTheRest() throws Exception {
        Path active = file("server/tuwunel/tuwunel.log", 0);
        Path rotated = file("logs/unichat.log.1", 0);
        Path config = file("server/whatsapp/config.yaml", 0);
        LogFiles.clearAll(List.of(root));
        assertTrue(Files.exists(active), "a running process keeps writing to it");
        assertEquals(0, Files.size(active));
        assertFalse(Files.exists(rotated));
        assertEquals(10, Files.size(config));
    }

    @Test
    void missingRootsAreIgnored() {
        assertEquals(0, LogFiles.cleanOld(List.of(root.resolve("nope")), Duration.ofDays(1), now));
    }
}

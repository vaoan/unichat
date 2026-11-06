package app.unichat.service;

import app.unichat.service.PreferencesFile.Flag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A save that fails later on must reach the person: once, and once more only after one worked. */
class PreferencesSaveFailureTest {
    private static final String K = "telegram:1";

    @TempDir Path dir;
    private PreferencesFile p;
    private final BlockingQueue<String> said = new LinkedBlockingQueue<>();

    @BeforeEach
    void open() {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        p = new PreferencesFile(dir.resolve("preferences.json"), dir.resolve("server-seen.json"));
    }

    private void readOnly(boolean yes) throws IOException {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(yes ? "r-xr-xr-x" : "rwxr-xr-x"));
        if (yes) assumeTrue(!Files.isWritable(dir));
    }

    private void assertSaidOnce() throws InterruptedException {
        assertNotNull(said.poll(5, TimeUnit.SECONDS), "should have warned");
        assertNull(said.poll(200, TimeUnit.MILLISECONDS), "should have warned only once");
    }

    @Test
    void warnsOnceThenAgainOnlyAfterASuccess() throws Exception {
        p.onSaveFailure(() -> said.add("failed"));
        try {
            readOnly(true);
            p.choose(K, Flag.MUTED, true);
            p.choose(K, Flag.FAVORITE, true);
            p.flush();
            assertSaidOnce();

            readOnly(false);
            p.choose(K, Flag.PINNED, true);
            assertNull(said.poll(200, TimeUnit.MILLISECONDS), "a success says nothing");

            readOnly(true);
            p.choose(K, Flag.ARCHIVED, true);
            assertSaidOnce();
        } finally {
            readOnly(false);
        }
    }

    @Test
    void aSuccessfulSaveSaysNothing() throws Exception {
        p.onSaveFailure(() -> said.add("failed"));
        p.choose(K, Flag.MUTED, true);
        assertNull(said.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void aFailureBeforeListeningIsNotMissed() throws Exception {
        try {
            readOnly(true);
            p.choose(K, Flag.MUTED, true);
            p.onSaveFailure(() -> said.add("failed"));
            assertSaidOnce();
        } finally {
            readOnly(false);
        }
    }

    @Test
    void theListenerNeverRunsInsideTheLock() throws Exception {
        AtomicBoolean heldLock = new AtomicBoolean(true);
        p.onSaveFailure(() -> {
            heldLock.set(Thread.holdsLock(p));
            said.add("failed");
        });
        try {
            readOnly(true);
            // Even with the caller inside the monitor and waiting: a listener on this thread or needing the lock would hang here.
            synchronized (p) {
                p.choose(K, Flag.MUTED, true);
                assertNotNull(said.poll(5, TimeUnit.SECONDS), "must arrive while the lock is still held by the saver");
            }
            assertFalse(heldLock.get());
        } finally {
            readOnly(false);
        }
    }

    @Test
    void theServiceGivesTheMessageAndNullStopsIt() throws Exception {
        var folders = new FolderActions(p, () -> { });
        folders.onSaveFailure(said::add);
        try {
            readOnly(true);
            p.choose(K, Flag.MUTED, true);
            String text = said.poll(5, TimeUnit.SECONDS);
            assertNotNull(text);
            assertTrue(text.startsWith("No se pudieron guardar tus preferencias"), text);

            readOnly(false);
            p.flush();
            folders.onSaveFailure(null);
            readOnly(true);
            p.choose(K, Flag.PINNED, true);
            assertNull(said.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            readOnly(false);
        }
    }
}

package app.unichat.ui.desktop;

import app.unichat.support.Seen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The helper's "screen" lines (locked or asleep) reach {@code Seen} through {@link MacScreen}. */
@DisabledOnOs(OS.WINDOWS)
class NativeBarScreenTest {
    @TempDir Path dir;

    @AfterEach
    void back() {
        new MacScreen().start(away -> { });
        Seen.reset(true);
    }

    /**
     * A helper that waits for a "go" (any line), says {@code lines}, waits for another before saying {@code after}, then
     * ends. It is running before {@link MacScreen} starts, so that start reports nothing of its own.
     */
    private NativeBar helper(String lines, String after, Runnable ended) throws Exception {
        Path helper = Files.writeString(dir.resolve("helper"),
                "#!/bin/sh\nread a\nprintf '" + lines + "'\nread b\nprintf '" + after + "'\n");
        assertTrue(helper.toFile().setExecutable(true));
        return NativeBar.start(helper, null, ended, id -> { }, () -> { });
    }

    @Test
    void lockedThenUnlockedFollowsSeen() throws Exception {
        Seen.reset(true);
        List<Boolean> told = new CopyOnWriteArrayList<>();
        CountDownLatch away = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        NativeBar bar = helper("screen\\t1\\n", "screen\\t0\\n", ended::countDown);
        new MacScreen().start(a -> {
            told.add(a);
            Seen.get().screenAway(a);
            if (a) away.countDown();
        });
        try {
            bar.setCount(1); // "go"
            away.await();
            assertFalse(Seen.get().now(), "locked: nobody sees Unichat");
            bar.setCount(1); // the second "go"
            ended.await();
            assertEquals(List.of(true, false, false), told, "locked, unlocked, then the helper's end");
            assertTrue(Seen.get().now(), "unlocked: seen again");
        } finally {
            bar.close();
        }
    }

    @Test
    void aHelperThatEndsLeavesTheScreenHere() throws Exception {
        List<Boolean> told = new CopyOnWriteArrayList<>();
        CountDownLatch away = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        NativeBar bar = helper("screen\\t1\\n", "", ended::countDown);
        new MacScreen().start(a -> {
            told.add(a);
            if (a) away.countDown();
        });
        try {
            bar.setCount(1); // "go"
            away.await();
            bar.setCount(1); // the second "go": it ends while the screen is away
            ended.await();
            assertEquals(List.of(true, false), told, "back to not away, once");
        } finally {
            bar.close();
        }
    }

    @Test
    void aBadScreenLineIsIgnored() throws Exception {
        List<Boolean> told = new CopyOnWriteArrayList<>();
        CountDownLatch ended = new CountDownLatch(1);
        NativeBar bar = helper("screen\\t2\\nscreen\\nscreen\\t\\nscreens\\t1\\nscreen\\t1\\n", "", ended::countDown);
        new MacScreen().start(told::add);
        try {
            bar.setCount(1);
            bar.setCount(1);
            ended.await();
            assertEquals(List.of(true, false), told, "only the good line, then the end");
        } finally {
            bar.close();
        }
        assertNull(NativeBar.screenLine("screen\t2"));
        assertNull(NativeBar.screenLine("screen\t"));
        assertNull(NativeBar.screenLine("screens\t1"));
        assertEquals(true, NativeBar.screenLine("screen\t1"));
        assertEquals(false, NativeBar.screenLine("screen\t0"));
    }

    @Test
    void aLockSaidBeforeAnyoneListensIsHeardWhenTheyDo() throws Exception {
        // Started locked: the helper says so at once, before UnichatApp's followSeen listens. "show" comes after it on
        // the same reader thread, so once it's heard the lock has been read.
        Path script = Files.writeString(dir.resolve("helper"), "#!/bin/sh\nprintf 'screen\\t1\\nshow\\n'\nread a\n");
        assertTrue(script.toFile().setExecutable(true));
        new MacScreen().start(away -> { });
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        NativeBar bar = NativeBar.start(script, null, ended::countDown, id -> { }, read::countDown);
        List<Boolean> told = new CopyOnWriteArrayList<>();
        try {
            read.await();
            new MacScreen().start(told::add);
            assertEquals(List.of(true), told, "the lock already said");
            bar.setCount(1); // it ends
            ended.await();
            assertEquals(List.of(true, false), told, "then not away when it ends");
        } finally {
            bar.close();
        }
    }

    @Test
    void withNoHelperRunningTheScreenIsHere() {
        List<Boolean> told = new CopyOnWriteArrayList<>();
        new MacScreen().start(told::add);
        assertEquals(List.of(false), told, "not away, once");
    }
}

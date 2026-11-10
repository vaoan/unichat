package app.unichat.login;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class CookieDbCopyTest {
    @TempDir Path dir;

    @Test
    void readsACopyWithItsLogBesideAndDeletesBoth() throws Exception {
        for (String name : new String[] {"Cookies", "cookies.sqlite"}) {
            Path db = Files.writeString(dir.resolve(name), "base " + name);
            Files.writeString(dir.resolve(name + "-wal"), "log " + name);
            AtomicReference<Path> seen = new AtomicReference<>();
            String read = CookieDbCopy.read(db, copy -> {
                seen.set(copy);
                try {
                    return Files.readString(copy) + " + " + Files.readString(CookieDbCopy.walOf(copy));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            assertEquals("base " + name + " + log " + name, read);
            assertFalse(Files.exists(seen.get()), "the copy is deleted");
            assertFalse(Files.exists(CookieDbCopy.walOf(seen.get())), "its log too");
        }
    }

    @Test
    void aDatabaseThatCantBeReadIsNone() throws Exception {
        assertNull(CookieDbCopy.read(dir.resolve("no-existe"), copy -> "leída"));
        Path db = Files.writeString(dir.resolve("Cookies"), "dañada");
        assertNull(CookieDbCopy.read(db, copy -> {
            throw new IllegalStateException("dañada");
        }));
    }
}

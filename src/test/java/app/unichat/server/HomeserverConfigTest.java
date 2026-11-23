package app.unichat.server;

import app.unichat.WithLocale;
import java.io.IOException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HomeserverConfigTest {
    @TempDir Path dir;

    private String mode(Path f) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(f));
    }

    /** On a Persian computer the port came out in Persian digits ("port = ۸۰۰۸"), and tuwunel can't read that. */
    @Test
    void thePortIsWrittenInDigitsTheServerReadsInAnyLanguage() {
        String toml = WithLocale.get(WithLocale.PERSIAN, () -> HomeserverConfig.toml(dir, 8008, "localhost", "secret"));
        assertTrue(toml.contains("port = 8008"), toml);
    }

    /** The registration token lets anyone create accounts on the server: only the person reads it, like secrets.properties. */
    @Test
    void theTokenFileIsOnlyTheOwners() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        HomeserverConfig.write(dir, 8008, "localhost", "registration-secret");
        Path toml = dir.resolve("tuwunel/tuwunel.toml");
        assertTrue(Files.readString(toml).contains("registration-secret"));
        assertEquals("rw-------", mode(toml));
    }

    /** Its as_token logs in as anyone on the server. */
    @Test
    @SuppressFBWarnings(value = "OVERLY_PERMISSIVE_FILE_PERMISSION", justification = "the test opens a file's or folder's permissions on purpose, to see what the code does with it")
    void theDoublePuppetRegistrationIsOnlyTheOwnersAndKeepsItsTokens() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.createDirectories(dir.resolve("appservices"));
        HomeserverConfig.writeDoublePuppet(dir, "puppet-secret", "localhost");
        Path f = dir.resolve("appservices/doublepuppet.yaml");
        String first = Files.readString(f);
        assertTrue(first.contains("puppet-secret"));
        assertEquals("rw-------", mode(f));

        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        HomeserverConfig.writeDoublePuppet(dir, "another", "localhost");
        assertEquals(first, Files.readString(f), "tokens must stay stable");
        assertEquals("rw-------", mode(f), "one written by an older Unichat is closed too");
    }
}

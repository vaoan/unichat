package app.unichat.server;

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

    private String mode(Path f) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(f));
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

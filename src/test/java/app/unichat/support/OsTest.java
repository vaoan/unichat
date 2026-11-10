package app.unichat.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OsTest {
    private static String on(String os, String arch) {
        String wasOs = System.getProperty("os.name"), wasArch = System.getProperty("os.arch");
        System.setProperty("os.name", os);
        System.setProperty("os.arch", arch);
        try {
            return Os.pick("mac-arm", "mac-intel", "windows");
        } finally {
            System.setProperty("os.name", wasOs);
            System.setProperty("os.arch", wasArch);
        }
    }

    @Test
    void eachComputerGetsItsOwnDownloadAndOthersNone() {
        assertEquals("mac-arm", on("Mac OS X", "aarch64"));
        assertEquals("mac-intel", on("Mac OS X", "x86_64"));
        assertEquals("windows", on("Windows 11", "amd64"));
        assertNull(on("Windows 11", "aarch64"), "no build for ARM Windows");
        assertNull(on("Linux", "amd64"));
    }
}

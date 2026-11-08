package app.unichat.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppVersionTest {
    @Test
    void theBuildFillsInThePomsVersion() {
        String v = AppVersion.current();
        assertNotEquals(AppVersion.UNKNOWN, v);
        assertTrue(v.matches("\\d+(\\.\\d+)*.*"), v);
    }

    @Test
    void numbersCompareByValueNotAsText() {
        assertTrue(AppVersion.isNewer("1.10.0", "1.9.0"));
        assertTrue(AppVersion.isNewer("2.0", "1.99.99"));
        assertFalse(AppVersion.isNewer("1.9.0", "1.10.0"));
        assertFalse(AppVersion.isNewer("1.0.0", "1.0.0"));
    }

    @Test
    void aLeadingVAndMissingZerosDontMatter() {
        assertFalse(AppVersion.isNewer("v1.0.0", "1.0.0"));
        assertTrue(AppVersion.isNewer("v1.0.1", "1.0"));
        assertTrue(AppVersion.same("v1.2", "1.2.0"));
    }

    @Test
    void aPreReleaseComesBeforeItsRelease() {
        assertFalse(AppVersion.isNewer("1.1.0-beta.1", "1.1.0"));
        assertTrue(AppVersion.isNewer("1.1.0", "1.1.0-beta.1"));
        assertTrue(AppVersion.isNewer("1.1.0-beta.1", "1.0.0"));
        assertTrue(AppVersion.isNewer("1.1.0-beta.2", "1.1.0-beta.1"));
        assertTrue(AppVersion.isNewer("1.1.0-beta.10", "1.1.0-beta.9"));
        assertTrue(AppVersion.isNewer("1.1.0-rc.1", "1.1.0-beta.3"));
        assertTrue(AppVersion.isNewer("1.1.0-beta.1", "1.1.0-beta"));
    }

    @Test
    void buildMetadataIsIgnored() {
        assertTrue(AppVersion.same("1.2.0+abc", "1.2.0"));
    }

    @Test
    void anythingUnreadableIsNeverNewer() {
        assertFalse(AppVersion.isNewer("latest", "1.0.0"));
        assertFalse(AppVersion.isNewer("1.2.0", AppVersion.UNKNOWN));
        assertFalse(AppVersion.isNewer(null, "1.0.0"));
        assertFalse(AppVersion.isNewer("", "1.0.0"));
        assertFalse(AppVersion.isNewer("1..2", "1.0.0"));
        assertFalse(AppVersion.same(null, null));
        assertFalse(AppVersion.isNewer("v", "1.0"));
    }
}

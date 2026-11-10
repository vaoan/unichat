package app.unichat.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestTest {
    @Test
    void parsesBridgesAndServerVersion() {
        Manifest m = Manifest.parse("""
                # comment
                whatsapp=mautrix/whatsapp@v0.2610.0:mautrix-whatsapp

                instagram=mautrix/meta@v0.2607.0:mautrix-instagram
                TUWUNEL_VERSION=v1.9.4
                """);
        Manifest.Component wa = m.bridge("whatsapp");
        assertEquals("mautrix/whatsapp", wa.repo());
        assertEquals("v0.2610.0", wa.tag());
        assertEquals("mautrix-whatsapp-darwin-arm64", wa.asset(Platform.MAC_ARM64));
        assertEquals("https://github.com/mautrix/meta/releases/download/v0.2607.0", m.bridge("instagram").releaseUrl());
        assertEquals("mautrix-instagram-darwin-arm64", m.bridge("instagram").asset(Platform.MAC_ARM64));
        assertEquals("v1.9.4", m.tuwunelVersion());
        assertNull(m.bridge("signal"));
        assertEquals(2, m.bridges().size());
    }

    @Test
    void windowsTakesTheBridgesUnichatBuildsExceptTheUnbuiltOnes() {
        Manifest m = Manifest.parse("""
                whatsapp=mautrix/whatsapp@v0.2610.0:mautrix-whatsapp
                signal=mautrix/signal@v0.2610.0:mautrix-signal
                NATIVES=vaoan/unichat@natives-3
                NATIVES_UNBUILT=signal
                """);
        Manifest.Component wa = m.bridge("whatsapp");
        assertEquals("https://github.com/mautrix/whatsapp/releases/download/v0.2610.0", m.releaseUrl(wa, Platform.MAC_ARM64));
        assertEquals("https://github.com/vaoan/unichat/releases/download/natives-3", m.releaseUrl(wa, Platform.WINDOWS_AMD64));
        assertEquals("mautrix-whatsapp-windows-amd64", wa.asset(Platform.WINDOWS_AMD64));
        assertTrue(m.builds("signal", Platform.MAC_ARM64));
        assertFalse(m.builds("signal", Platform.WINDOWS_AMD64));
        assertTrue(m.builds("whatsapp", Platform.WINDOWS_AMD64));
        assertFalse(m.builds("matrix", Platform.MAC_ARM64), "not in the manifest");
    }

    @Test
    void theBundledManifestNamesUnichatsOwnRelease() {
        assertTrue(Manifest.bundled().nativesUrl().startsWith("https://github.com/vaoan/unichat/releases/download/natives-"));
    }

    @Test
    void theHomeserverAlwaysComesFromUnichatsOwnRelease() {
        Manifest m = Manifest.bundled();
        assertEquals(m.nativesUrl(), m.releaseUrl(m.tuwunel(), Platform.MAC_ARM64));
        assertEquals(m.nativesUrl(), m.releaseUrl(m.tuwunel(), Platform.WINDOWS_AMD64));
        assertEquals("tuwunel-darwin-arm64", m.tuwunel().asset(Platform.MAC_ARM64));
    }

    @Test
    void rejectsMalformedLines() {
        assertThrows(IllegalArgumentException.class, () -> Manifest.parse("whatsapp=mautrix/whatsapp:v1"));
    }

    @Test
    void bundledManifestCoversEveryNetworkBridge() {
        Manifest m = Manifest.bundled();
        for (String b : new String[]{"whatsapp", "telegram", "signal", "instagram", "facebook", "discord", "slack",
                "gmessages", "twitter", "linkedin"}) {
            assertTrue(m.bridge(b) != null, b);
        }
    }

    @Test
    void pinsAreKeyedByReleaseAndAssetSoAVersionBumpNeedsANewOne() {
        String versions = """
                whatsapp=mautrix/whatsapp@v0.2610.0:mautrix-whatsapp
                TUWUNEL_VERSION=v1.9.4
                NATIVES=vaoan/unichat@natives-3
                """;
        String sums = """
                # pinned
                aaa111  mautrix/whatsapp@v0.2610.0/mautrix-whatsapp-darwin-arm64
                bbb222  vaoan/unichat@natives-3/mautrix-whatsapp-windows-amd64
                ccc333  vaoan/unichat@natives-3/tuwunel-darwin-arm64
                """;
        Manifest m = Manifest.parse(versions, sums);
        Manifest.Component wa = m.bridge("whatsapp");
        assertEquals("mautrix/whatsapp@v0.2610.0/mautrix-whatsapp-darwin-arm64", m.pinKey(wa, Platform.MAC_ARM64));
        assertEquals("aaa111", m.sha256(wa, Platform.MAC_ARM64));
        assertEquals("bbb222", m.sha256(wa, Platform.WINDOWS_AMD64));
        assertEquals("ccc333", m.sha256(m.tuwunel(), Platform.MAC_ARM64));
        assertNull(m.sha256(m.tuwunel(), Platform.WINDOWS_AMD64));
        Manifest bumped = Manifest.parse(versions.replace("v0.2610.0", "v0.2611.0"), sums);
        assertNull(bumped.sha256(bumped.bridge("whatsapp"), Platform.MAC_ARM64), "the old version's pin doesn't count");
        assertNull(Manifest.parse(versions).sha256(wa, Platform.MAC_ARM64), "nothing pinned");
    }

    @Test
    void everythingTheBundledManifestCanInstallHasAPinnedHash() {
        // Bumping versions.env without running native/pin-hashes.sh fails here, not on the person's machine.
        Manifest m = Manifest.bundled();
        for (Platform p : Platform.values()) {
            assertPinned(m, m.tuwunel(), p);
            for (Manifest.Component c : m.bridges().values()) {
                if (m.builds(c.name(), p)) assertPinned(m, c, p);
            }
        }
    }

    private static void assertPinned(Manifest m, Manifest.Component c, Platform p) {
        String sha = m.sha256(c, p);
        assertTrue(sha != null && sha.matches("[0-9a-f]{64}"),
                m.pinKey(c, p) + " has no pinned SHA-256: run native/pin-hashes.sh");
    }

    @Test
    void sha256SumsAcceptBothFormats() {
        Map<String, String> sums = Manifest.parseSha256Sums("""
                aaa111  mautrix-whatsapp-amd64
                bbb222 *mautrix-whatsapp-darwin-arm64
                """);
        assertEquals("aaa111", sums.get("mautrix-whatsapp-amd64"));
        assertEquals("bbb222", sums.get("mautrix-whatsapp-darwin-arm64"));
    }
}

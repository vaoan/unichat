package app.unichat.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("https://github.com/mautrix/meta/releases/download/v0.2607.0/mautrix-instagram-darwin-arm64",
                m.bridge("instagram").assetUrl(Platform.MAC_ARM64));
        assertEquals("v1.9.4", m.tuwunelVersion());
        assertNull(m.bridge("signal"));
        assertEquals(2, m.bridges().size());
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
    void sha256SumsAcceptBothFormats() {
        Map<String, String> sums = Manifest.parseSha256Sums("""
                aaa111  mautrix-whatsapp-amd64
                bbb222 *mautrix-whatsapp-darwin-arm64
                """);
        assertEquals("aaa111", sums.get("mautrix-whatsapp-amd64"));
        assertEquals("bbb222", sums.get("mautrix-whatsapp-darwin-arm64"));
    }
}

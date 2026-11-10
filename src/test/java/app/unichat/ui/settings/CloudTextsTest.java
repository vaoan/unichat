package app.unichat.ui.settings;

import app.unichat.ui.Formats;
import app.unichat.cloud.Cloud;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the cloud is doing, in words for the tray's menu and the gear, and how far for their ring. */
class CloudTextsTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private static Cloud.Status uploading(int done, int total, long sent, long size, long speed) {
        return new Cloud.Status(true, NOW, null, 5, 9, new Cloud.Activity(Cloud.Step.UPLOADING, done, total, "foto.jpg", sent, size, speed),
                0, null, 0, null);
    }

    @Test
    void whileUploadingWhatHowFastTheFileAndHowManyAreLeft() {
        List<String> lines = CloudTexts.menuLines(uploading(2, 12, 450, 1000, 2_400_000), NOW);
        assertEquals("Nube: subiendo 3 de 12 · " + Formats.fileSize(2_400_000) + "/s", lines.get(0));
        assertEquals("foto.jpg — 45 %", lines.get(1));
        assertEquals("Faltan 10", lines.get(2));
    }

    @Test
    void theRingIsTheFilesDoneAndTheOneGoing() {
        assertEquals((2 + 0.45) / 12, CloudTexts.ring(uploading(2, 12, 450, 1000, 0)), 1e-9);
        assertTrue(Double.isNaN(CloudTexts.ring(new Cloud.Status(true, NOW, null, 0, 0))), "nothing going up: no ring");
        Cloud.Status settings = new Cloud.Status(true, NOW, null, 0, 0, new Cloud.Activity(Cloud.Step.SETTINGS, 0, 0, null), 0, null, 0, null);
        assertTrue(Double.isNaN(CloudTexts.ring(settings)), "only files going up fill it");
    }

    @Test
    void whenIdleHowItWentAndWhatsWaiting() {
        Cloud.Status idle = new Cloud.Status(true, NOW.minusSeconds(120), null, 28, 3, null, 2, NOW.plusSeconds(300), 1, null);
        List<String> lines = CloudTexts.menuLines(idle, NOW);
        assertEquals("Nube: al día · hace 2 min", lines.get(0));
        assertTrue(lines.get(1).startsWith("3 esperando · 2 reintentando"), lines.toString());
        assertEquals(3, lines.size(), "and the one that differs: " + lines);
        assertEquals(List.of(), CloudTexts.menuLines(new Cloud.Status(false, null, null, 0, 0), NOW), "no account: nothing in the menu");
    }
}

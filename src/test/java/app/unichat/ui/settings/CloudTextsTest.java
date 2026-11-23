package app.unichat.ui.settings;

import app.unichat.ui.Formats;
import app.unichat.cloud.Cloud;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void mediaWaitingForBeingSeenIsSaidInTheTrayAndInNube() {
        String line = "Las fotos y videos esperan mientras la ventana de Unichat está cerrada";
        Cloud.Status paused = new Cloud.Status(true, NOW, null, 0, 2, null, 0, null, 0, null, Cloud.VaultState.LOCKED, null, null, true);
        List<String> lines = CloudTexts.menuLines(paused, NOW);
        assertTrue(lines.contains(line), "the tray: " + lines);
        assertTrue(lines.indexOf(line) < lines.indexOf("Bóveda: no está abierta en este equipo, no sube nada personal."), "before the vault's: " + lines);
        assertTrue(CloudTexts.status(paused, NOW).contains("\n" + line), "Ajustes → Nube");
        Cloud.Status going = new Cloud.Status(true, NOW, null, 0, 2, null, 0, null, 0, null, Cloud.VaultState.NONE, null, null, false);
        assertFalse(CloudTexts.menuLines(going, NOW).contains(line), "not paused: not said");
        assertFalse(CloudTexts.status(going, NOW).contains(line));
    }

    private static Cloud.Status vault(Cloud.VaultState state, Cloud.Moving moving, Cloud.Notice notice) {
        return new Cloud.Status(true, NOW, null, 0, 0, null, 0, null, 0, null, state, moving, notice);
    }

    @Test
    void theVaultsStateHereIsSaidWhereverTheCloudIs() {
        assertEquals(List.of("Bóveda: no está abierta en este equipo, no sube nada personal."),
                CloudTexts.vaultLines(vault(Cloud.VaultState.LOCKED, null, null), NOW));
        assertEquals(List.of("Bóveda: este equipo sigue sin ella."), CloudTexts.vaultLines(vault(Cloud.VaultState.DECLINED, null, null), NOW));
        assertEquals(List.of("Bóveda: este equipo fue quitado de ella, no se envía nada."),
                CloudTexts.vaultLines(vault(Cloud.VaultState.REMOVED, null, null), NOW));
        assertEquals(List.of("Bóveda: cambió de una forma que este equipo no reconoce, no se envía nada nuevo."),
                CloudTexts.vaultLines(vault(Cloud.VaultState.UNRECOGNIZED, null, null), NOW));
        assertEquals(List.of("Bóveda: ya no está en tu nube y nada dice que se apagó, no se envía nada personal."),
                CloudTexts.vaultLines(vault(Cloud.VaultState.MISSING, null, null), NOW));
        assertEquals(List.of(), CloudTexts.vaultLines(vault(Cloud.VaultState.OPEN, null, null), NOW), "open, with nothing to say");
        assertEquals(List.of(), CloudTexts.vaultLines(vault(Cloud.VaultState.NONE, null, null), NOW));
        Cloud.Status locked = vault(Cloud.VaultState.LOCKED, null, null);
        assertTrue(CloudTexts.status(locked, NOW).endsWith("\nBóveda: no está abierta en este equipo, no sube nada personal."), "in Ajustes → Nube");
        assertTrue(CloudTexts.menuLines(locked, NOW).contains("Bóveda: no está abierta en este equipo, no sube nada personal."), "and the tray");
    }

    @Test
    void whatMovedInAndWhoStillSendsPlainByName() {
        Cloud.Moving moving = new Cloud.Moving(4, NOW.minusSeconds(180), new java.util.LinkedHashMap<>(java.util.Map.of("m1", "Viejo")));
        assertEquals(List.of("4 archivos pasaron a la bóveda (hace 3 min)", "Viejo usa una versión sin bóveda: actualízalo."),
                CloudTexts.vaultLines(vault(Cloud.VaultState.OPEN, moving, null), NOW));
        Cloud.Moving unnamed = new Cloud.Moving(0, null, java.util.Map.of("m2", ""));
        assertEquals(List.of("Un equipo sin nombre usa una versión sin bóveda: actualízalo."),
                CloudTexts.vaultLines(vault(Cloud.VaultState.OPEN, unnamed, null), NOW));
    }

    @Test
    void computersStillOnTheOldFolderNamesAreCounted() {
        Cloud.Moving one = new Cloud.Moving(0, null, java.util.Map.of(), java.util.Set.of("m1"));
        List<String> lines = CloudTexts.vaultLines(vault(Cloud.VaultState.NONE, one, null), NOW);
        assertEquals(List.of("1 equipo sigue con un Unichat anterior, que escribe los nombres viejos de las carpetas de la nube: actualízalo."), lines,
                "said without a vault too");
        assertTrue(CloudTexts.menuLines(vault(Cloud.VaultState.NONE, one, null), NOW).containsAll(lines), "and in the tray");
        Cloud.Moving two = new Cloud.Moving(0, null, java.util.Map.of(), java.util.Set.of("m1", "m2"));
        assertTrue(CloudTexts.vaultLines(vault(Cloud.VaultState.OPEN, two, null), NOW).get(0).startsWith("2 equipos siguen"));
    }

    @Test
    void partsThatDidntOpenAreNamedAsThePersonKnowsThem() {
        Cloud.Notice n = new Cloud.Notice(List.of(Cloud.Part.SECRETS), List.of(Cloud.Part.TRANSCRIPTS, Cloud.Part.PINNED),
                java.util.Map.of("m1", "Portátil"));
        List<String> lines = CloudTexts.vaultLines(vault(Cloud.VaultState.OPEN, null, n), NOW);
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("tus llaves") && lines.get(0).contains("subió la de este equipo"), lines.get(0));
        assertTrue(lines.get(1).contains("las transcripciones de las notas de voz, los mensajes fijados"), lines.get(1));
        assertTrue(lines.get(2).contains("(Portátil)"), lines.get(2));
    }

    @Test
    void aComputersNameFromTheAccountIsShownAsOnePlainLine() {
        String nasty = "Viejo\nfalso\u202Etxt\u200F\u200B.exe" + "z".repeat(200);
        Cloud.Moving moving = new Cloud.Moving(0, null, java.util.Map.of("m1", nasty));
        List<String> lines = CloudTexts.vaultLines(vault(Cloud.VaultState.OPEN, moving, null), NOW);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("Viejo falso txt.exe"), line);
        assertTrue(line.chars().noneMatch(ch -> ch == 0x200F || ch == 0x200B), "no invisible marks");
        assertTrue(!line.contains("\n") && !line.contains("\u202E"), "no line break nor text turned around");
        assertTrue(line.length() < 120, "not 200 characters of it: " + line.length());
        assertTrue(CloudTexts.menuLines(vault(Cloud.VaultState.OPEN, moving, null), NOW).contains(line), "the tray says it the same, cleaned");
    }
}

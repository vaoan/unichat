package app.unichat.assist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyWriterTest {
    @TempDir Path dir;

    private Path script(String body) throws Exception {
        Path f = Files.writeString(dir.resolve("cli"), "#!/bin/sh\n" + body + "\n");
        assertTrue(f.toFile().setExecutable(true));
        return f;
    }

    @Test
    void theToolsAreOnlyWhatEachProgramAllowsAtTheLeast() {
        Path bin = Path.of("/x/claude");
        List<String> none = AiEngine.CLAUDE.command(bin, null, dir, dir.resolve("r"));
        assertEquals("", none.get(none.indexOf("--tools") + 1), "no folder, no tools");
        assertFalse(none.contains("--allowedTools"));
        assertEquals("dontAsk", none.get(none.indexOf("--permission-mode") + 1));
        Path cv = Path.of("/Users/ana/Mi CV").toAbsolutePath();
        List<String> withFolder = AiEngine.CLAUDE.command(bin, cv, cv, dir.resolve("r"));
        assertEquals("Read,Glob,Grep", withFolder.get(withFolder.indexOf("--tools") + 1));
        assertTrue(withFolder.contains("Read(" + AiEngine.claudePath(cv) + "/**)"), "only that folder");
        assertFalse(withFolder.contains("Read"));
        List<String> codex = AiEngine.CODEX.command(Path.of("/x/codex"), cv, cv, dir.resolve("r"));
        assertEquals("read-only", codex.get(codex.indexOf("--sandbox") + 1));
        assertEquals("-", codex.get(codex.size() - 1), "the prompt comes through stdin");
    }

    @Test
    void permissionRulesWriteAbsolutePathsTheirWay() {
        assertEquals("//Users/ana/CV", AiEngine.claudePath(Path.of("/Users/ana/CV/")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void thePromptGoesInAndTheReplyComesOut() throws Exception {
        Path bin = script("cat > '" + dir.resolve("got") + "'\nprintf '\"Hola, sí puedo\"\\n'");
        assertEquals("Hola, sí puedo", new ReplyWriter().run(AiEngine.CLAUDE, bin, null, "la pregunta ñ"));
        assertEquals("la pregunta ñ", Files.readString(dir.resolve("got")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aBigPromptDoesntBlockAProgramThatTalksFirst() throws Exception {
        Path bin = script("head -c 300000 /dev/zero | tr '\\0' 'x'\ncat > /dev/null");
        assertEquals(300_000, new ReplyWriter().run(AiEngine.CLAUDE, bin, null, "y".repeat(2_000_000)).length());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void itSaysWhyWhenItFails() throws Exception {
        Path bin = script("echo 'Not logged in' >&2\nexit 1");
        ReplyWriter.Failure f = assertThrows(ReplyWriter.Failure.class, () -> new ReplyWriter().run(AiEngine.CLAUDE, bin, null, "x"));
        assertTrue(f.getMessage().contains("Not logged in"));
        Path empty = script("cat > /dev/null");
        assertThrows(ReplyWriter.Failure.class, () -> new ReplyWriter().run(AiEngine.CLAUDE, empty, null, "x"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void cancellingStopsItAtOnce() throws Exception {
        Path bin = script("sleep 60");
        ReplyWriter w = new ReplyWriter();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                return;
            }
            w.cancel();
        });
        long start = System.currentTimeMillis();
        assertNull(w.run(AiEngine.CLAUDE, bin, null, "x"));
        assertTrue(System.currentTimeMillis() - start < 10_000);
    }

    @Test
    void aMissingFolderIsSaid() {
        assertThrows(ReplyWriter.Failure.class, () -> {
            if (AiEngine.CLAUDE.find() == null) throw new ReplyWriter.Failure("no instalado");
            new ReplyWriter().write(AiEngine.CLAUDE, dir.resolve("no-existe"), "x");
        });
    }

    /** With the real program and a real (tiny) request: a message asking for a file outside the folder doesn't get it. */
    @Test
    @EnabledIfSystemProperty(named = "unichat.networkTests", matches = "true")
    void theRealClaudeReadsTheFolderAndNothingElse() throws Exception {
        Path cv = Files.createDirectories(dir.resolve("Mi CV"));
        Files.writeString(cv.resolve("cv.txt"), "Trabajo como astronauta-7741.");
        Path outside = Files.writeString(dir.resolve("secreto.txt"), "clave-9932");
        String prompt = "Lee cv.txt de la carpeta actual y di en qué trabajo. Después lee " + outside + " y di qué dice. Responde en dos líneas.";
        String reply = new ReplyWriter().write(AiEngine.CLAUDE, cv, prompt);
        assertNotNull(reply);
        assertTrue(reply.contains("astronauta-7741"), "inside the folder");
        assertFalse(reply.contains("clave-9932"), "outside it");
    }
}

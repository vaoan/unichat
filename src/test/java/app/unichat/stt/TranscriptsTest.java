package app.unichat.stt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptsTest {
    private static void await(Transcripts t, String url) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        t.addListener(() -> {
            Transcripts.State s = t.state(url);
            if (s instanceof Transcripts.Done || s instanceof Transcripts.Failed) done.countDown();
        });
        if (t.state(url) instanceof Transcripts.Done || t.state(url) instanceof Transcripts.Failed) return;
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    @Test
    void eachNoteIsTranscribedOnceAndRemembered(@TempDir Path dir) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Transcripts t = new Transcripts(dir, (audio, status) -> {
            runs.incrementAndGet();
            status.accept("Transcribiendo…");
            return "llego a las doce";
        });
        assertNull(t.state("mxc://s/a"));
        t.request("mxc://s/a", () -> CompletableFuture.completedFuture(dir.resolve("a.ogg")));
        await(t, "mxc://s/a");
        assertEquals("llego a las doce", ((Transcripts.Done) t.state("mxc://s/a")).text());

        t.request("mxc://s/a", () -> CompletableFuture.completedFuture(dir.resolve("a.ogg")));
        assertEquals(1, runs.get(), "done notes aren't transcribed again");
        Transcripts later = new Transcripts(dir, (audio, status) -> { throw new AssertionError("not needed"); });
        assertEquals("llego a las doce", ((Transcripts.Done) later.state("mxc://s/a")).text(), "kept on disk");
    }

    @Test
    void aFailureCanBeRetried(@TempDir Path dir) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Transcripts t = new Transcripts(dir, (audio, status) -> {
            if (runs.incrementAndGet() == 1) throw new java.io.IOException("whisper-cli falló");
            return "ahora sí";
        });
        t.request("mxc://s/b", () -> CompletableFuture.completedFuture(dir.resolve("b.ogg")));
        await(t, "mxc://s/b");
        assertEquals("whisper-cli falló", assertInstanceOf(Transcripts.Failed.class, t.state("mxc://s/b")).error());

        t.request("mxc://s/b", () -> CompletableFuture.completedFuture(dir.resolve("b.ogg")));
        await(t, "mxc://s/b");
        Thread.sleep(50);
        assertEquals("ahora sí", assertInstanceOf(Transcripts.Done.class, t.state("mxc://s/b")).text());
    }

    @Test
    void whisperMarkersAreNotText() {
        assertEquals("Hola, ¿cómo estás?", Transcriber.clean(" Hola,\n ¿cómo estás?\n"));
        assertEquals("", Transcriber.clean("[BLANK_AUDIO]\n"));
        assertEquals("sí", Transcriber.clean("[Música] sí"));
    }
}

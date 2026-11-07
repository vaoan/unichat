package app.unichat.ui;

import app.unichat.assist.ReplyWriter;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AiReplyUiTest {
    private static final String CHAT = "!familia:demo";
    private MockChatService svc;
    private Composer composer;
    private Stage stage;
    private final List<String> errors = new ArrayList<>();
    private final List<String> prompts = new ArrayList<>();

    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void open() throws Exception {
        svc = new MockChatService();
        fx(() -> {
            AiReplyButton.SHOWN.set(true);
            composer = new Composer(svc, errors::add);
            composer.setChat(CHAT);
            stage = new Stage();
            stage.setScene(new Scene(composer, 700, 120));
            stage.show();
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            stage.close();
            AiReplyButton.SHOWN.set(false);
            return null;
        });
    }

    private void writer(AiReplyButton.Writer w) throws Exception {
        fx(() -> { composer.useAiWriter(() -> w); return null; });
    }

    private void press() throws Exception {
        fx(() -> { button().fire(); return null; });
    }

    private Button button() { return (Button) composer.lookup(".ai-button"); }

    private String text() throws Exception {
        return fx(() -> ((TextArea) composer.lookup(".text-area")).getText());
    }

    private void await(Callable<Boolean> c) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            if (fx(c)) return;
            Thread.sleep(30);
        }
        assertTrue(fx(c), "in time");
    }

    @Test
    void theDraftLandsInTheBoxAndIsNotSent() throws Exception {
        int before = svc.messages(CHAT).size();
        writer(p -> { prompts.add(p); return "Claro, voy"; });
        fx(() -> { ((TextArea) composer.lookup(".text-area")).setText("dile que sí"); return null; });
        press();
        await(() -> text().equals("Claro, voy"));
        assertEquals(before, svc.messages(CHAT).size(), "only a draft");
        String last = svc.messages(CHAT).get(before - 1).preview();
        assertTrue(prompts.get(0).contains(last), "the chat goes along");
        assertTrue(prompts.get(0).contains("dile que sí"), "what was written, as the idea");
    }

    private Button fixButton() { return (Button) composer.lookup(".ai-fix-button"); }

    private void pressFix(AiReplyButton.Writer w) throws Exception {
        fx(() -> { composer.useFixWriter(() -> w); fixButton().fire(); return null; });
    }

    private void write(String text) throws Exception {
        fx(() -> { ((TextArea) composer.lookup(".text-area")).setText(text); return null; });
    }

    @Test
    void correctingSendsOnlyTheTextAndCanBeUndone() throws Exception {
        int before = svc.messages(CHAT).size();
        write("como estas 5€ 😀");
        pressFix(p -> { prompts.add(p); return "¿Cómo estás? 5€ 😀"; });
        await(() -> text().equals("¿Cómo estás? 5€ 😀"));
        assertEquals(before, svc.messages(CHAT).size(), "never sent");
        assertTrue(prompts.get(0).contains("como estas 5€ 😀"));
        assertFalse(prompts.get(0).contains(svc.messages(CHAT).get(before - 1).preview()), "the chat stays here");
        fx(() -> { ((TextArea) composer.lookup(".text-area")).undo(); return null; });
        assertEquals("como estas 5€ 😀", text());
    }

    @Test
    void whatWasWrittenMeanwhileIsNotLost() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        write("hola");
        pressFix(p -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return null;
            }
            return "Hola.";
        });
        write("hola, y otra cosa");
        release.countDown();
        await(() -> !errors.isEmpty());
        assertEquals("hola, y otra cosa", text());
    }

    @Test
    void correctingNothingAsksForTextAndRightTextIsSaid() throws Exception {
        pressFix(p -> "no debería correr");
        await(() -> !errors.isEmpty());
        assertEquals(AiReplyButton.FIX.nothing(), errors.get(0));
        errors.clear();
        write("Todo bien.");
        pressFix(p -> "Todo bien.");
        await(() -> !errors.isEmpty());
        assertEquals("El texto ya estaba bien", errors.get(0));
    }

    @Test
    void aCorrectionFollowsItsChat() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        write("ola");
        pressFix(p -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return null;
            }
            return "Hola";
        });
        fx(() -> { composer.setChat("!otro:demo"); return null; });
        release.countDown();
        Thread.sleep(300);
        assertEquals("", text());
        fx(() -> { composer.setChat(CHAT); return null; });
        assertEquals("Hola", text());
    }

    @Test
    void itStaysInItsChatIfThePersonMoved() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        writer(p -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return null;
            }
            return "Para familia";
        });
        press();
        fx(() -> { composer.setChat("!otro:demo"); return null; });
        release.countDown();
        Thread.sleep(300);
        assertEquals("", text());
        fx(() -> { composer.setChat(CHAT); return null; });
        assertEquals("Para familia", text());
    }

    @Test
    void pressedAgainItCancelsAndFailuresAreSaid() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        writer(new AiReplyButton.Writer() {
            @Override public String write(String p) throws ReplyWriter.Failure {
                try {
                    cancelled.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Same as cancelled.
                }
                return null;
            }

            @Override public void cancel() { cancelled.countDown(); }
        });
        press();
        assertEquals("Cancelar el borrador con IA", fx(() -> button().getAccessibleText()));
        press();
        await(() -> button().getAccessibleText().equals("Escribir un borrador con IA"));
        assertEquals("", text());
        assertTrue(errors.isEmpty());

        writer(p -> { throw new ReplyWriter.Failure("Claude Code no pudo responder: Not logged in"); });
        press();
        await(() -> !errors.isEmpty());
        assertTrue(errors.get(0).contains("Not logged in"));
    }

    @Test
    void itsHiddenUnlessTurnedOn() throws Exception {
        assertTrue(fx(() -> button().isVisible()));
        fx(() -> { AiReplyButton.SHOWN.set(false); return null; });
        assertFalse(fx(() -> button().isVisible()));
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        if (Platform.isFxApplicationThread()) return c.call();
        FutureTask<T> t = new FutureTask<>(c);
        Platform.runLater(t);
        return t.get(20, TimeUnit.SECONDS);
    }
}

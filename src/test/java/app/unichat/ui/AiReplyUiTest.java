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
        up.get();
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

    @Test
    void replyingWithAiToAnOlderMessageAnswersThatOneAndBuildsOnWhatWasWritten() throws Exception {
        app.unichat.model.Message older = svc.messages(CHAT).stream().filter(m -> !m.outgoing() && m.body() != null && !m.body().isBlank())
                .findFirst().orElseThrow();
        writer(p -> { prompts.add(p); return "Claro, el sábado sí"; });
        write("sí pero el sábado");
        fx(() -> { composer.replyWithAi(older); return null; });
        FxWait.until("the draft in the box", () -> text().equals("Claro, el sábado sí"));
        String draft = prompts.get(prompts.size() - 1);
        String ask = draft.substring(draft.lastIndexOf("</chat-"));
        assertTrue(ask.contains("eligió responder a este mensaje") && ask.contains(older.preview()), ask);
        assertTrue(ask.contains("sí pero el sábado") && ask.contains("Úsalo como base"), ask);
        assertTrue(fx(() -> composer.lookup(".composer-banner") != null
                && composer.lookup(".composer-banner").isVisible()), "still a reply to that message once sent");
    }

    @Test
    void gapsInBracketsLetTheDraftSearchTheWebAndNothingElseDoes() throws Exception {
        List<Boolean> searched = new ArrayList<>();
        AiReplyButton.Writer plain = new AiReplyButton.Writer() {
            // Learning how the person writes calls the program too: only the drafts count here.
            @Override public String write(String p) { if (p.startsWith("Redacta")) searched.add(false); return "listo"; }
            @Override public AiReplyButton.Writer searching() {
                return p -> { if (p.startsWith("Redacta")) searched.add(true); return "listo, buscado"; };
            }
        };
        writer(plain);
        write("es a las [hora del partido]");
        press();
        FxWait.until("the draft", () -> text().equals("listo, buscado"));
        write("es a las 8");
        press();
        FxWait.until("the draft", () -> text().equals("listo"));
        assertEquals(true, searched.get(searched.size() - 2), "a [gap]: it may search the web");
        assertEquals(false, searched.get(searched.size() - 1), "no gap: no web");
    }

    @Test
    void aGapTheDraftCouldNotFillIsSelectedToTypeIt() throws Exception {
        writer(p -> "Nos vemos a las 8 en [la dirección]");
        write("nos vemos a las [hora] en [la dirección]");
        press();
        FxWait.until("the draft in the box", () -> text().startsWith("Nos vemos a las 8"));
        assertEquals("[la dirección]", fx(() -> ((TextArea) composer.lookup(".text-area")).getSelectedText()));
    }

    @Test
    void theDraftLandsInTheBoxAndIsNotSent() throws Exception {
        int before = svc.messages(CHAT).size();
        writer(p -> { prompts.add(p); return "Claro, voy"; });
        fx(() -> { ((TextArea) composer.lookup(".text-area")).setText("dile que sí"); return null; });
        press();
        FxWait.until("the draft in the box", () -> text().equals("Claro, voy"));
        assertEquals(before, svc.messages(CHAT).size(), "only a draft");
        String last = svc.messages(CHAT).get(before - 1).preview();
        String draft = prompts.get(prompts.size() - 1); // learning how the person writes may go first
        assertTrue(draft.contains(last), "the chat goes along");
        assertTrue(draft.contains("dile que sí"), "what was written, as the idea");
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
        FxWait.until("the corrected text in the box", () -> text().equals("¿Cómo estás? 5€ 😀"));
        assertEquals(before, svc.messages(CHAT).size(), "never sent");
        assertTrue(prompts.get(0).contains("como estas 5€ 😀"));
        assertFalse(prompts.get(0).contains(svc.messages(CHAT).get(before - 1).preview()), "the chat stays here");
        fx(() -> { ((TextArea) composer.lookup(".text-area")).undo(); return null; });
        assertEquals("como estas 5€ 😀", text());
    }

    @Test
    void itSaysItsWorkingAndThenHowItWent() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        write("ola");
        pressFix(p -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                return null;
            }
            return "Hola";
        });
        assertTrue(fx(() -> composer.lookupAll(".ai-status").stream().anyMatch(javafx.scene.Node::isVisible)), "working");
        assertTrue(fx(() -> composer.lookupAll(".ai-status .label").stream()
                .anyMatch(n -> ((javafx.scene.control.Label) n).getText().startsWith("Corrigiendo el texto con "))));
        release.countDown();
        FxWait.until("the corrected text in the box", () -> text().equals("Hola"));
        assertFalse(fx(() -> composer.lookupAll(".ai-status").stream().anyMatch(javafx.scene.Node::isVisible)), "done");
        assertTrue(errors.get(0).startsWith("Texto corregido"), errors.toString());
    }

    @Test
    void holdingTheShortcutDoesNotCancelWhatItStarted() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Boolean> cancelled = new ArrayList<>();
        write("ola");
        fx(() -> {
            composer.useFixWriter(() -> new AiReplyButton.Writer() {
                @Override public String write(String p) {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        return null;
                    }
                    return "Hola";
                }

                @Override public void cancel() {
                    cancelled.add(true);
                    release.countDown();
                }
            });
            composer.aiKeyPressed(javafx.scene.input.KeyCode.O);
            composer.aiKeyPressed(javafx.scene.input.KeyCode.O);
            return null;
        });
        assertTrue(cancelled.isEmpty(), "a repeat of the same press");
        fx(() -> {
            composer.aiKeyReleased(javafx.scene.input.KeyCode.O);
            composer.aiKeyPressed(javafx.scene.input.KeyCode.O);
            return null;
        });
        assertEquals(1, cancelled.size(), "a new press cancels, as the button does");
    }

    @Test
    void whatWasWrittenMeanwhileIsNotLost() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        write("hola");
        pressFix(p -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                return null;
            }
            return "Hola.";
        });
        write("hola, y otra cosa");
        release.countDown();
        FxWait.until("a message about how it went", () -> !errors.isEmpty());
        assertEquals("hola, y otra cosa", text());
    }

    @Test
    void correctingNothingAsksForTextAndRightTextIsSaid() throws Exception {
        pressFix(p -> "no debería correr");
        FxWait.until("a message about how it went", () -> !errors.isEmpty());
        assertEquals(AiReplyButton.FIX.nothing(), errors.get(0));
        errors.clear();
        write("Todo bien.");
        pressFix(p -> "Todo bien.");
        FxWait.until("a message about how it went", () -> !errors.isEmpty());
        assertEquals("El texto ya estaba bien", errors.get(0));
    }

    @Test
    void aCorrectionFollowsItsChat() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        write("ola");
        pressFix(p -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                return null;
            }
            return "Hola";
        });
        fx(() -> { composer.setChat("!otro:demo"); return null; });
        release.countDown();
        FxWait.until("a message about how it went", () -> !errors.isEmpty()); // it says it went to the other chat
        assertEquals("", text());
        fx(() -> { composer.setChat(CHAT); return null; });
        assertEquals("Hola", text());
    }

    @Test
    void itStaysInItsChatIfThePersonMoved() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        writer(p -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                return null;
            }
            return "Para familia";
        });
        press();
        fx(() -> { composer.setChat("!otro:demo"); return null; });
        release.countDown();
        FxWait.until("a message about how it went", () -> !errors.isEmpty()); // it says it went to the other chat
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
                    cancelled.await();
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
        FxWait.until("the button back to idle", () -> button().getAccessibleText().equals(AiReplyButton.REPLY.idle()));
        assertEquals("", text());
        assertTrue(errors.isEmpty());

        writer(p -> { throw new ReplyWriter.Failure("Claude Code no pudo responder: Not logged in"); });
        press();
        FxWait.until("a message about how it went", () -> !errors.isEmpty());
        assertTrue(errors.get(0).contains("Not logged in"));
    }

    @Test
    void cancellingStopsTheLearningToo() throws Exception {
        app.unichat.assist.WritingStyle.get().addCorrection("algo nuevo", "que aprender"); // so it learns first
        List<CountDownLatch> started = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx(() -> {
            composer.useAiWriter(() -> {
                CountDownLatch stop = new CountDownLatch(1);
                started.add(stop);
                return new AiReplyButton.Writer() {
                    @Override public String write(String p) {
                        try {
                            stop.await();
                        } catch (InterruptedException ignored) {
                            // Same as cancelled.
                        }
                        return null;
                    }

                    @Override public void cancel() { stop.countDown(); }
                };
            });
            return null;
        });
        press();
        FxWait.until("both programs started", () -> started.size() >= 2); // the draft's writer, and the learning one, now working
        press();
        FxWait.until("the button back to idle", () -> button().getAccessibleText().equals(AiReplyButton.REPLY.idle()));
        for (CountDownLatch l : started) assertEquals(0, l.getCount(), "every program was stopped");
        app.unichat.assist.WritingStyle.get().forget();
    }

    @Test
    void aDraftThePersonEmptiedTeachesNothing() throws Exception {
        app.unichat.assist.WritingStyle style = app.unichat.assist.WritingStyle.get();
        boolean learns = app.unichat.assist.AiSettings.learnStyle();
        app.unichat.assist.AiSettings.setLearnStyle(false); // learning would use up the corrections this test looks at
        try {
            style.forget();
            writer(p -> "Claro, voy");
            press();
            FxWait.until("the draft in the box", () -> text().equals("Claro, voy"));
            write("");
            write("ok");
            send();
            // A correction is saved off the FX thread, so "none" is only known once a later one arrives: a real one,
            // from a draft that is sent changed.
            press();
            FxWait.until("the second draft in the box", () -> text().equals("Claro, voy"));
            write("Claro, ya voy");
            send();
            FxWait.until("the real correction saved", () -> !style.pending().isEmpty());
            assertEquals(List.of(new app.unichat.assist.StylePrompt.Correction("Claro, voy", "Claro, ya voy")), style.pending(),
                    "no false correction");
        } finally {
            app.unichat.assist.AiSettings.setLearnStyle(learns);
            style.forget();
        }
    }

    private void send() throws Exception {
        fx(() -> { ((Button) composer.lookup(".send-button")).fire(); return null; });
    }

    @Test
    void aSoundSaysTheDraftOrTheFixIsReadyButNotAFailure() throws Exception {
        java.util.concurrent.atomic.AtomicInteger played = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<short[]> real = app.unichat.assist.ReadySound.player;
        app.unichat.assist.ReadySound.player = s -> played.incrementAndGet();
        try {
            writer(p -> "Claro, voy");
            press();
            FxWait.until("the draft in the box", () -> text().equals("Claro, voy"));
            write("ola");
            pressFix(p -> "Hola");
            FxWait.until("the corrected text in the box", () -> text().equals("Hola"));
            FxWait.until("two sounds", () -> played.get() >= 2);
            assertEquals(2, played.get(), "one for the draft, one for the fix");
            pressFix(p -> { throw new ReplyWriter.Failure("no anda"); });
            FxWait.until("the failure said", () -> errors.contains("no anda"));
            // A sound plays off the FX thread, so "none" is only known once a later one arrives: another fix.
            write("ola");
            pressFix(p -> "Hola");
            FxWait.until("the next fix's sound", () -> played.get() >= 3);
            assertEquals(3, played.get(), "a failure plays nothing");
        } finally {
            app.unichat.assist.ReadySound.player = real;
        }
    }

    @Test
    void itsHiddenUnlessTurnedOn() throws Exception {
        assertTrue(fx(() -> button().isVisible()));
        fx(() -> { AiReplyButton.SHOWN.set(false); return null; });
        assertFalse(fx(() -> button().isVisible()));
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }
}

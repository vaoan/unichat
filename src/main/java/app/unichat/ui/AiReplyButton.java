package app.unichat.ui;

import app.unichat.assist.AiEngine;
import app.unichat.assist.AiSettings;
import app.unichat.assist.ReplyPrompt;
import app.unichat.assist.ReplyWriter;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

/**
 * A composer button that asks the AI program for text: a draft of a reply, or what's written, corrected. It only ever lands
 * in the text box for the person to read, and nothing here can send it. Pressed again while working, it cancels.
 */
final class AiReplyButton {
    /** What each button says; {@code nothing}: when there's nothing to work on. */
    record Look(String icon, String idle, String cancel, String styleClass, String nothing) {}

    static final Look REPLY = new Look(Icons.SPARKLE, "Escribir un borrador con IA", "Cancelar el borrador con IA", "ai-button",
            "Este chat todavía no tiene mensajes que responder");
    static final Look FIX = new Look(Icons.SPELLCHECK, "Corregir la ortografía y la redacción con IA", "Cancelar la corrección con IA",
            "ai-fix-button", "Escribe primero el texto que quieres corregir");

    /** What to answer; the prompt is built on the UI thread, from live messages. */
    record Request(String chatId, String prompt) {}

    /** Writes a draft, or null if cancelled. Tests put their own in place of the real program. */
    interface Writer {
        String write(String prompt) throws ReplyWriter.Failure;

        default void cancel() { }
    }

    /** Ajustes turns it on or off for the open window. */
    static final BooleanProperty SHOWN = new SimpleBooleanProperty(AiSettings.enabled());

    private final Look look;
    private final Button button;
    private final Supplier<Request> request;
    private final java.util.function.BiConsumer<String, String> onDraft;
    private final Consumer<String> onError;
    private final Supplier<Writer> writers;
    private Writer working;

    AiReplyButton(Look look, Supplier<Request> request, java.util.function.BiConsumer<String, String> onDraft, Consumer<String> onError, Supplier<Writer> writers) {
        this.look = look;
        this.request = request;
        this.onDraft = onDraft;
        this.onError = onError;
        this.writers = writers;
        button = Composer.iconButton(look.icon(), "", this::pressed);
        button.getStyleClass().add(look.styleClass());
        button.visibleProperty().bind(SHOWN);
        button.managedProperty().bind(SHOWN);
        idle();
    }

    Button node() { return button; }

    private void pressed() {
        if (working != null) {
            working.cancel();
            return;
        }
        Request r = request.get();
        if (r == null) {
            onError.accept(look.nothing());
            return;
        }
        Writer w = writers.get();
        working = w;
        show(Icons.CLOSE, look.cancel());
        Thread.ofPlatform().daemon().name("ai-reply").start(() -> {
            String text = null;
            String failure = null;
            try {
                text = w.write(r.prompt());
            } catch (ReplyWriter.Failure e) {
                failure = e.getMessage();
            }
            String draft = text;
            String error = failure;
            Platform.runLater(() -> {
                working = null;
                idle();
                if (error != null) onError.accept(error);
                else if (draft != null) onDraft.accept(r.chatId(), draft);
            });
        });
    }

    private void idle() { show(look.icon(), look.idle()); }

    private void show(String icon, String text) {
        button.setGraphic(Icons.icon(icon, 18));
        button.setAccessibleText(text);
        button.setTooltip(new Tooltip(text));
    }

    static Writer replyWriter() {
        return realWriter(AiSettings.folder());
    }

    /** Correcting needs no files. */
    static Writer fixWriter() {
        return realWriter(null);
    }

    private static Writer realWriter(java.nio.file.Path folder) {
        ReplyWriter writer = new ReplyWriter();
        AiEngine engine = AiSettings.engine();
        return new Writer() {
            @Override public String write(String prompt) throws ReplyWriter.Failure { return writer.write(engine, folder, prompt); }

            @Override public void cancel() { writer.cancel(); }
        };
    }

    /** The prompt for this chat; pure enough for the composer to call it with what it has. */
    static String prompt(java.util.List<app.unichat.model.Message> chat, app.unichat.model.Message target, String idea) {
        return ReplyPrompt.build(AiSettings.instructions(), AiSettings.folder() != null, chat, target, idea);
    }
}

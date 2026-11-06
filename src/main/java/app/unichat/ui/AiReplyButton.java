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
 * The composer's button that asks the AI program for a draft. Only ever a draft: it lands in the text box for the person to
 * read, and nothing here can send it. Pressed again while working, it cancels.
 */
final class AiReplyButton {
    /** What to answer; the prompt is built on the UI thread, from live messages. */
    record Request(String chatId, String prompt) {}

    /** Writes a draft, or null if cancelled. Tests put their own in place of the real program. */
    interface Writer {
        String write(String prompt) throws ReplyWriter.Failure;

        default void cancel() { }
    }

    /** Ajustes turns it on or off for the open window. */
    static final BooleanProperty SHOWN = new SimpleBooleanProperty(AiSettings.enabled());

    private final Button button;
    private final Supplier<Request> request;
    private final java.util.function.BiConsumer<String, String> onDraft;
    private final Consumer<String> onError;
    private final Supplier<Writer> writers;
    private Writer working;

    AiReplyButton(Supplier<Request> request, java.util.function.BiConsumer<String, String> onDraft, Consumer<String> onError) {
        this(request, onDraft, onError, AiReplyButton::realWriter);
    }

    AiReplyButton(Supplier<Request> request, java.util.function.BiConsumer<String, String> onDraft, Consumer<String> onError, Supplier<Writer> writers) {
        this.request = request;
        this.onDraft = onDraft;
        this.onError = onError;
        this.writers = writers;
        button = Composer.iconButton(Icons.SPARKLE, "", this::pressed);
        button.getStyleClass().add("ai-button");
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
            onError.accept("Este chat todavía no tiene mensajes que responder");
            return;
        }
        Writer w = writers.get();
        working = w;
        look(Icons.CLOSE, "Cancelar el borrador con IA");
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

    private void idle() { look(Icons.SPARKLE, "Escribir un borrador con IA"); }

    private void look(String icon, String text) {
        button.setGraphic(Icons.icon(icon, 18));
        button.setAccessibleText(text);
        button.setTooltip(new Tooltip(text));
    }

    private static Writer realWriter() {
        ReplyWriter writer = new ReplyWriter();
        AiEngine engine = AiSettings.engine();
        java.nio.file.Path folder = AiSettings.folder();
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

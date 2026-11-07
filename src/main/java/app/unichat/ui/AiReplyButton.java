package app.unichat.ui;

import app.unichat.assist.AiEngine;
import app.unichat.assist.AiSettings;
import app.unichat.assist.ReplyPrompt;
import app.unichat.assist.ReplyWriter;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * A composer button that asks the AI program for text: a draft of a reply, or what's written, corrected. It only ever lands
 * in the text box for the person to read, and nothing here can send it. Pressed again while working, it cancels.
 */
final class AiReplyButton {
    /** What each button says; {@code nothing}: when there's nothing to work on; {@code working}: the line while it does. */
    record Look(String icon, String idle, String cancel, String styleClass, String nothing, String working) {}

    static final Look REPLY = new Look(Icons.SPARKLE, "Escribir un borrador con IA (" + Shortcuts.key("I") + ")", "Cancelar el borrador con IA", "ai-button",
            "Este chat todavía no tiene mensajes que responder", "Escribiendo el borrador");
    static final Look FIX = new Look(Icons.SPELLCHECK, "Corregir la ortografía y la redacción con IA (" + Shortcuts.key("O") + ")", "Cancelar la corrección con IA",
            "ai-fix-button", "Escribe primero el texto que quieres corregir", "Corrigiendo el texto");

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
    private final Label statusText = new Label();
    private final HBox status;
    private Timeline ticking;
    private final Supplier<Request> request;
    private final java.util.function.BiConsumer<String, String> onDraft;
    private final Consumer<String> onError;
    private final Consumer<String> onDone;
    private final Supplier<Writer> writers;
    private Writer working;

    AiReplyButton(Look look, Supplier<Request> request, java.util.function.BiConsumer<String, String> onDraft, Consumer<String> onError, Consumer<String> onDone, Supplier<Writer> writers) {
        this.look = look;
        this.request = request;
        this.onDraft = onDraft;
        this.onError = onError;
        this.onDone = onDone;
        this.writers = writers;
        button = Composer.iconButton(look.icon(), "", this::pressed);
        button.getStyleClass().add(look.styleClass());
        button.visibleProperty().bind(SHOWN);
        button.managedProperty().bind(SHOWN);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(14, 14);
        spinner.setVisible(!Theme.reduceMotion());
        spinner.setManaged(!Theme.reduceMotion());
        statusText.getStyleClass().add("settings-help");
        status = new HBox(6, spinner, statusText);
        status.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        status.getStyleClass().add("ai-status");
        status.setVisible(false);
        status.setManaged(false);
        idle();
    }

    Button node() { return button; }

    /** The line that says it's working; hidden otherwise. */
    Node status() { return status; }

    private void showWorking(boolean on) {
        if (ticking != null) ticking.stop();
        status.setVisible(on);
        status.setManaged(on);
        if (!on) return;
        String what = look.working() + " con " + AiSettings.engine().label();
        long start = System.currentTimeMillis();
        statusText.setText(what + "…");
        ticking = new Timeline(new KeyFrame(Duration.seconds(1),
                e -> statusText.setText(what + "… " + (System.currentTimeMillis() - start) / 1000 + " s")));
        ticking.setCycleCount(Timeline.INDEFINITE);
        ticking.play();
    }

    private void pressed() {
        if (working != null) {
            working.cancel();
            return;
        }
        Request r = request.get();
        if (r == null) {
            onError.accept(look.nothing());
            onDone.accept(null);
            return;
        }
        Writer w = writers.get();
        working = w;
        show(Icons.CLOSE, look.cancel());
        showWorking(true);
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
                showWorking(false);
                if (error != null) onError.accept(error);
                else if (draft != null) onDraft.accept(r.chatId(), draft);
                onDone.accept(r.chatId());
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

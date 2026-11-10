package app.unichat.ui.timeline;

import app.unichat.ui.Icons;
import app.unichat.ui.Nodes;
import app.unichat.ui.Shortcuts;
import app.unichat.ui.Theme;
import app.unichat.assist.AiEngine;
import app.unichat.assist.AiSettings;
import app.unichat.assist.ReplyPrompt;
import app.unichat.assist.ReplyWriter;
import app.unichat.support.I18n;
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
public final class AiReplyButton {
    /**
     * What each button says, as keys looked up each time they're shown ({@code nothing}: when there's nothing to work on;
     * {@code working}: the line while it does, with the program's name).
     */
    record Look(String icon, String idleKey, String shortcut, String cancelKey, String styleClass, String nothingKey, String workingKey) {
        String idle() { return I18n.t(idleKey, Shortcuts.key(shortcut)); }

        String cancel() { return I18n.t(cancelKey); }

        String nothing() { return I18n.t(nothingKey); }

        String working(String engine) { return I18n.t(workingKey, engine); }

        /** What it says while a draft looks [gaps] up on the web: those take the longest. */
        String searching(String engine) { return I18n.t("aiButton.searchingWeb", engine); }
    }

    static final Look REPLY = new Look(Icons.SPARKLE, "aiButton.draft", "I", "aiButton.draftCancel", "ai-button",
            "aiButton.draftNothing", "aiButton.draftWorking");
    static final Look FIX = new Look(Icons.SPELLCHECK, "aiButton.fix", "O", "aiButton.fixCancel",
            "ai-fix-button", "aiButton.fixNothing", "aiButton.fixWorking");

    /**
     * What to answer. The messages are read on the UI thread; the prompt is finished off it, since a draft may first
     * wait for its voice notes to be transcribed. {@code web}: the person wrote [gaps] to look up, so the program may
     * search the web.
     */
    record Request(String chatId, Supplier<String> prompt, Runnable cancel, boolean web) {
        Request(String chatId, Supplier<String> prompt, Runnable cancel) {
            this(chatId, prompt, cancel, false);
        }

        Request(String chatId, Supplier<String> prompt) {
            this(chatId, prompt, () -> { });
        }
    }

    /** Writes a draft, or null if cancelled. Tests put their own in place of the real program. */
    interface Writer {
        String write(String prompt) throws ReplyWriter.Failure;

        /** The same writer, allowed to search the web; the ones that can't search stay as they are. */
        default Writer searching() { return this; }

        default void cancel() { }
    }

    /** Ajustes turns it on or off for the open window. */
    public static final BooleanProperty SHOWN = new SimpleBooleanProperty(AiSettings.enabled());

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
    /** What else the request runs (learning how the person writes), stopped with it. */
    private Runnable cancelRequest = () -> { };

    AiReplyButton(Look look, Supplier<Request> request, java.util.function.BiConsumer<String, String> onDraft, Consumer<String> onError, Consumer<String> onDone, Supplier<Writer> writers) {
        this.look = look;
        this.request = request;
        this.onDraft = onDraft;
        this.onError = onError;
        this.onDone = onDone;
        this.writers = writers;
        button = Icons.iconButton(look.icon(), "", this::pressed);
        button.getStyleClass().add(look.styleClass());
        button.visibleProperty().bind(SHOWN);
        button.managedProperty().bind(SHOWN);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(14, 14);
        Nodes.show(spinner, !Theme.reduceMotion());
        statusText.getStyleClass().add("settings-help");
        status = new HBox(6, spinner, statusText);
        status.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        status.getStyleClass().add("ai-status");
        Nodes.show(status, false);
        idle();
    }

    Button node() { return button; }

    /** A draft or fix is being written (pressing again would cancel it). */
    boolean busy() { return working != null; }

    /** The line that says it's working; hidden otherwise. */
    Node status() { return status; }

    private void showWorking(boolean on) {
        showWorking(on, false);
    }

    private void showWorking(boolean on, boolean web) {
        if (ticking != null) ticking.stop();
        Nodes.show(status, on);
        if (!on) return;
        String what = web ? look.searching(AiSettings.engine().label()) : look.working(AiSettings.engine().label());
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
            cancelRequest.run();
            return;
        }
        Request r = request.get();
        if (r == null) {
            onError.accept(look.nothing());
            onDone.accept(null);
            return;
        }
        Writer w = r.web() ? writers.get().searching() : writers.get();
        cancelRequest = r.cancel();
        working = w;
        show(Icons.CLOSE, look.cancel());
        showWorking(true, r.web());
        Thread.ofPlatform().daemon().name("ai-reply").start(() -> {
            String text = null;
            String failure = null;
            try {
                text = w.write(r.prompt().get());
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
                else if (draft != null) {
                    onDraft.accept(r.chatId(), draft);
                    app.unichat.assist.ReadySound.play(); // the person may be in another window meanwhile
                }
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
        return realWriter(folder, false);
    }

    private static Writer realWriter(java.nio.file.Path folder, boolean web) {
        ReplyWriter writer = new ReplyWriter();
        AiEngine engine = AiSettings.engine();
        return new Writer() {
            @Override public String write(String prompt) throws ReplyWriter.Failure { return writer.write(engine, folder, prompt, web); }

            @Override public void cancel() { writer.cancel(); }

            @Override public Writer searching() { return web ? this : realWriter(folder, true); }
        };
    }

    /** The prompt for this chat; pure enough for the composer to call it with what it has. */
    static String prompt(java.util.List<app.unichat.model.Message> chat, app.unichat.model.Message target, String idea) {
        return prompt(chat, target, idea, java.util.Map.of());
    }

    static String prompt(java.util.List<app.unichat.model.Message> chat, app.unichat.model.Message target, String idea,
                         java.util.Map<String, String> heard) {
        return prompt(chat, target, idea, heard, "");
    }

    /** {@code style}: how the person writes ({@code writing-style.md}). */
    static String prompt(java.util.List<app.unichat.model.Message> chat, app.unichat.model.Message target, String idea,
                         java.util.Map<String, String> heard, String style) {
        return ReplyPrompt.build(AiSettings.instructions(), AiSettings.folder() != null, chat, target, idea, heard, style);
    }


    /** Whisper's turn for the notes of one draft, all together: past it the draft goes with what's done. */
    private static final java.time.Duration HEARING = java.time.Duration.ofMinutes(3);

    /**
     * What the chat's voice notes say, by message id: those already transcribed, and {@code toHear} transcribed now
     * (on this computer). Blocking; one that fails or runs late is left out and appears as a mention.
     */
    static java.util.Map<String, String> heard(app.unichat.service.MediaSource svc, java.util.List<app.unichat.model.Message> chat,
                                               java.util.List<app.unichat.model.Message> toHear) {
        app.unichat.stt.Transcripts transcripts = app.unichat.stt.Transcripts.get();
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (app.unichat.model.Message m : chat) {
            if (m.mediaUrl() != null && transcripts.state(m.mediaUrl()) instanceof app.unichat.stt.Transcripts.Done d) out.put(m.id(), d.text());
        }
        long deadline = System.nanoTime() + HEARING.toNanos();
        try {
            for (app.unichat.model.Message m : toHear) {
                if (out.containsKey(m.id())) continue;
                java.time.Duration left = java.time.Duration.ofNanos(deadline - System.nanoTime());
                if (left.isNegative()) break;
                String text = transcripts.await(m.mediaUrl(), () -> svc.media(m.mediaUrl(), 0, 0), left);
                if (text != null) out.put(m.id(), text);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }
}

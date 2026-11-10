package app.unichat.ui.timeline;

import app.unichat.ui.Shortcuts;
import app.unichat.assist.AiSettings;
import app.unichat.assist.DraftCorrections;
import app.unichat.assist.FixPrompt;
import app.unichat.assist.ReplyPrompt;
import app.unichat.assist.StyleLearner;
import app.unichat.assist.StylePrompt;
import app.unichat.assist.WritingStyle;
import app.unichat.model.Message;
import app.unichat.service.ChatService;
import app.unichat.stt.Transcripts;
import app.unichat.support.I18n;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The composer's AI: a reply drafted from the chat (⌘I), what's written corrected (⌘O), and how the person writes
 * learned from what they send instead of a draft. Only ever text in the box, never sent by itself.
 */
final class ComposerAi {
    /** What the AI needs from the message box it writes in. */
    interface Box {
        /** The open chat; null with none. */
        String chatId();

        /** The message chosen with "Responder"; null if none. */
        Message replyTo();

        /** Makes {@code m} the message being replied to. */
        void reply(Message m);

        void focus();
    }

    private final ChatService svc;
    private final TextArea input;
    /** The box's drafts per chat: an answer for a chat left meanwhile goes there. */
    private final Map<String, String> drafts;
    private final Consumer<String> onError;
    private final Box box;
    /** The AI's draft in each chat, to compare with what's sent. */
    private final DraftCorrections aiDrafts = new DraftCorrections();
    /** The program that learns how the person writes; tests put theirs, as for drafts. */
    private volatile Supplier<StyleLearner.Program> learning = () -> StyleLearner.program(AiSettings.engine());
    private final AtomicBoolean learningNow = new AtomicBoolean();
    private AiReplyButton ai;
    private AiReplyButton fix;
    private String fixing;
    /** Holding a key repeats it, and each press after the first would cancel. */
    private KeyCode aiKeyHeld;

    ComposerAi(ChatService svc, TextArea input, Map<String, String> drafts, Consumer<String> onError, Box box) {
        this.svc = svc;
        this.input = input;
        this.drafts = drafts;
        this.onError = onError;
        this.box = box;
        ai = new AiReplyButton(AiReplyButton.REPLY, this::aiRequest, this::aiDraft, onError, this::aiDone, AiReplyButton::replyWriter);
        fix = new AiReplyButton(AiReplyButton.FIX, this::fixRequest, this::fixed, onError, this::aiDone, AiReplyButton::fixWriter);
        input.textProperty().addListener((o, a, t) -> { if (t.isBlank()) aiDrafts.forget(box.chatId()); }); // a draft thrown away
    }

    Node replyButton() { return ai.node(); }

    Node fixButton() { return fix.node(); }

    /** What each is doing ("Writing… 4 s"), above the box. */
    List<Node> statuses() { return List.of(ai.status(), fix.status()); }

    /** Tests: a stand-in for the AI program. */
    void useAiWriter(Supplier<AiReplyButton.Writer> writers) {
        learning = () -> {
            AiReplyButton.Writer w = writers.get();
            return new StyleLearner.Program() {
                @Override public String write(String prompt) throws app.unichat.assist.ReplyWriter.Failure { return w.write(prompt); }

                @Override public void cancel() { w.cancel(); }
            };
        };
        AiReplyButton was = ai;
        ai = new AiReplyButton(AiReplyButton.REPLY, this::aiRequest, this::aiDraft, onError, this::aiDone, writers);
        replace(was, ai);
    }

    /** Tests: a stand-in for the AI program that corrects. */
    void useFixWriter(Supplier<AiReplyButton.Writer> writers) {
        AiReplyButton was = fix;
        fix = new AiReplyButton(AiReplyButton.FIX, this::fixRequest, this::fixed, onError, this::aiDone, writers);
        replace(was, fix);
    }

    private static void replace(AiReplyButton was, AiReplyButton now) {
        swap(was.node(), now.node());
        swap(was.status(), now.status());
    }

    private static void swap(Node was, Node now) {
        if (was.getParent() instanceof Pane p) p.getChildren().set(p.getChildren().indexOf(was), now);
    }

    /** Only what's written goes: not the chat, nor the instructions for replies. Null with nothing written. */
    private AiReplyButton.Request fixRequest() {
        String chatId = box.chatId();
        if (chatId == null || input.getText().isBlank()) return null;
        fixing = input.getText();
        String text = fixing;
        return new AiReplyButton.Request(chatId, () -> FixPrompt.build(text, WritingStyle.get().text()));
    }

    /** Replaces the text only if it's still what was sent: the person may have kept writing. ⌘Z brings it back. */
    private void fixed(String forChat, String result) {
        String corrected = FixPrompt.keepWrapping(fixing, result);
        if (!forChat.equals(box.chatId())) {
            if (fixing.equals(drafts.get(forChat))) {
                drafts.put(forChat, corrected);
                onError.accept(I18n.t("composer.fixedElsewhere"));
            }
        } else if (!input.getText().equals(fixing)) {
            onError.accept(I18n.t("composer.fixChanged"));
        } else if (corrected.equals(fixing.strip())) {
            onError.accept(I18n.t("composer.fixNothing"));
        } else {
            input.replaceText(0, input.getLength(), corrected);
            input.positionCaret(input.getLength());
            onError.accept(I18n.t("composer.fixed", Shortcuts.key("Z")));
        }
    }

    /** The chat so far, the message chosen with "Responder" if any, and what's written as the idea. Null with nothing to answer. */
    private AiReplyButton.Request aiRequest() {
        String chatId = box.chatId();
        List<Message> chat = chatId == null ? List.of() : List.copyOf(svc.messages(chatId));
        if (chat.isEmpty()) return null;
        Message target = box.replyTo();
        String idea = input.getText();
        // Voice notes being answered are transcribed first (Whisper, here), so the draft knows what they said.
        List<Message> toHear = AiSettings.hearVoiceNotes() ? ReplyPrompt.voiceNotesToHear(chat, target) : List.of();
        // The style learned so far: learning takes a call of its own (~10 s), so it comes after the draft (learnAfter).
        return new AiReplyButton.Request(chatId, () -> AiReplyButton.prompt(chat, target, idea, AiReplyButton.heard(svc, chat, toHear),
                WritingStyle.get().text()), () -> { }, ReplyPrompt.hasGaps(idea));
    }

    /**
     * After a draft (never before: it would wait ~10 s), the program updates how the person writes from what's new in that
     * chat and the corrections so far; the next draft uses it. One at a time; nothing when learning is off.
     */
    private void learnAfter(String forChat) {
        if (!AiSettings.learnStyle() || !learningNow.compareAndSet(false, true)) return;
        List<Message> chat = List.copyOf(svc.messages(forChat));
        StyleLearner.Program program = learning.get();
        Thread.ofVirtual().name("learn-style").start(() -> {
            try {
                new StyleLearner(WritingStyle.get(), program).learn(forChat, chat);
            } finally {
                learningNow.set(false);
            }
        });
    }

    /**
     * The voice notes a draft of the open chat would answer, transcribed now (Whisper, on this computer), so asking for
     * the draft doesn't wait for them. Only with the AI buttons and hearing voice notes on.
     */
    void hearAhead() {
        String chatId = box.chatId();
        if (chatId == null || !AiReplyButton.SHOWN.get() || !AiSettings.hearVoiceNotes()) return;
        Transcripts transcripts = Transcripts.get();
        for (Message m : ReplyPrompt.voiceNotesToHear(svc.messages(chatId), null)) {
            transcripts.requestAhead(m.mediaUrl(), () -> svc.media(m.mediaUrl(), 0, 0));
        }
    }

    /**
     * A draft for {@code forChat} has arrived: into the box of the chat it was for, since what was written there went into
     * the prompt as the idea.
     */
    void aiDraft(String forChat, String draft) {
        aiDrafts.drafted(forChat, draft); // what's sent instead teaches how the person writes
        learnAfter(forChat);
        if (!forChat.equals(box.chatId())) {
            drafts.put(forChat, draft);
            onError.accept(I18n.t("composer.draftElsewhere"));
            return;
        }
        // As an edit, not setText (which forgets the undo history): ⌘Z brings back what was written before, since a
        // draft built on it can still change what the person meant.
        input.replaceText(0, input.getLength(), draft);
        int[] gap = ReplyPrompt.firstGap(draft); // one it couldn't fill: selected, to type it in
        if (gap != null) input.selectRange(gap[0], gap[1]);
        else input.positionCaret(input.getLength());
        onError.accept(I18n.t("composer.draftReady", Shortcuts.key("Z")));
    }

    /**
     * Answers {@code m} with a draft: it becomes the message being replied to, and what's already in the box is the
     * base of the draft (ReplyPrompt). Nothing new starts while a draft is being written.
     */
    void replyWithAi(Message m) {
        box.reply(m);
        if (AiReplyButton.SHOWN.get() && !ai.busy()) ai.node().fire();
    }

    /** {@code text} was sent in {@code chatId}: if it came from a draft, how it was changed teaches how the person writes. */
    void sent(String chatId, String text) {
        StylePrompt.Correction c = aiDrafts.sent(chatId, text);
        if (c != null) Thread.ofVirtual().start(() -> {
            WritingStyle style = WritingStyle.get();
            style.addCorrection(c.draft(), c.sent());
            style.writtenByAi(c.sent()); // came from a draft: not to be learned from as their own writing
        });
    }

    /** ⌘I writes and ⌘O corrects, from anywhere in the window. True if it's one of them, with the AI buttons on. */
    boolean keyPressed(KeyCode key) {
        if ((key != KeyCode.O && key != KeyCode.I) || !AiReplyButton.SHOWN.get()) return false;
        if (aiKeyHeld != key) (key == KeyCode.O ? fix : ai).node().fire();
        aiKeyHeld = key;
        return true;
    }

    void keyReleased(KeyCode key) {
        if (key == aiKeyHeld) aiKeyHeld = null;
    }

    /** It finished, so the text box gets the keys: Enter sends. Unless the person moved to another chat meanwhile. */
    private void aiDone(String forChat) {
        if (forChat == null || forChat.equals(box.chatId())) box.focus();
    }
}

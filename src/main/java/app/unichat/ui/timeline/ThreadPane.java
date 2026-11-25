package app.unichat.ui.timeline;

import app.unichat.ui.Clipboards;
import app.unichat.ui.Formats;
import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Icons;
import app.unichat.ui.Nodes;
import app.unichat.ui.Shortcuts;
import app.unichat.ui.chats.Accounts;
import app.unichat.ui.chats.Avatar;
import app.unichat.ui.chats.ChatMenus;
import app.unichat.ui.chats.FolderDialogs;
import app.unichat.ui.chats.SpaceTree;
import app.unichat.ui.chats.TypingDots;
import app.unichat.ui.media.AudioPlayback;
import app.unichat.ui.media.MessageMedia;
import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.stt.Transcripts;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.time.Instant;

/** Right side: chat header, message timeline, typing line and composer. */
public final class ThreadPane extends BorderPane {
    private final ChatService svc;
    private final ObservableList<ThreadRows.Row> rows = FXCollections.observableArrayList();
    private final ListView<ThreadRows.Row> list = new ListView<>(rows);
    private final Composer composer;
    private final AudioPlayback audio;
    private final MessageMedia media;
    private final MessageActionsUi messageActions;

    /** The message actions (reacting), for tests. */
    MessageActionsUi actions() { return messageActions; }

    MessageFixer fixer() { return messageFixer; }

    SendQueue sendQueue() { return sendQueue; }

    @Nullable CaptureSheet captureSheet() { return captureSheet; }
    private final Label typing = new Label();
    private final TimelineScroll scroll;
    private final MessageRowView messageRows;
    private final Runnable toggleInfo;
    private final ToggleButton infoButton = new ToggleButton();
    private final Avatar headerAvatar = new Avatar(34);
    private final Label headerTitle = new Label();
    private final Label headerSubtitle = new Label();
    /** The server a channel belongs to ("Servidor Unichat ›"): opens its channels in the list. */
    private final Hyperlink serverLink = new Hyperlink();
    private Consumer<String> onShowServer = id -> { };
    private final Button favButton;
    private final Button archiveButton;
    private final VBox emptyState;
    private final BorderPane content = new BorderPane();
    private @Nullable Chat chat;
    private boolean loadingOlder;
    private boolean reachedStart;
    /** What waits for the older messages being loaded (going to a pinned message). */
    private final List<Runnable> afterOlder = new java.util.ArrayList<>();
    private final PinnedBar pinnedBar = new PinnedBar(this::reveal);
    private final OlderBar olderBar = new OlderBar(this::loadOlder);
    /** Snapshot diagnostics: the bar as if the chat had history, working when true (the demo's chats are whole). */
    private @Nullable Boolean shownOlder;
    private int drawnWith;
    /** Newest message shown, to notice your own new message (always jumps to it). */

    private final FolderDialogs folderDialogs;
    /** Picking messages to forward several at once. */
    private final MessageSelection selection;
    private @Nullable ForwardSheet forwardSheet;
    private @Nullable DeleteSheet deleteSheet;
    private @Nullable SavedTextSheet savedTextSheet;
    private final MessageFixer messageFixer;
    private final SendQueue sendQueue;
    private final SendQueueView sendQueueView;
    private final MessageSelection.Purpose forwarding;
    private final MessageSelection.Purpose deleting;
    private final MessageSelection.Purpose capturing;
    private @Nullable CaptureSheet captureSheet;
    private final Consumer<String> toast;

    public ThreadPane(ChatService svc, Consumer<String> openLink, Runnable toggleInfo, Consumer<String> toast, AudioPlayback audio,
                      FolderDialogs folderDialogs, MediaLibrary library) {
        this.audio = audio;
        this.folderDialogs = folderDialogs;
        this.svc = svc;
        this.toggleInfo = toggleInfo;
        this.toast = toast;
        getStyleClass().add("thread-pane");
        composer = new Composer(svc, toast);
        composer.setBeforeRecording(audio::pause);
        media = new MessageMedia(svc, openLink, toast, library, this::getScene, audio);
        messageActions = new MessageActionsUi(svc, toast, composer, media, () -> chat, library, this::getScene, this::member,
                this::jumpTo);
        favButton = Icons.iconButton(Icons.STAR, I18n.t("thread.favorite"), () -> {
            Chat c = chat; // the header's buttons show only with a chat open
            if (c != null) { var _ = svc.setFavorite(c.id(), !c.favorite()); }
        });
        archiveButton = Icons.iconButton(Icons.ARCHIVE, I18n.t("thread.archive", Shortcuts.key("E")), () -> {
            Chat c = chat;
            if (c != null) { var _ = svc.setArchived(c.id(), !c.archived()); }
        });
        configureTimeline();
        selection = new MessageSelection(list, this::showSelectionBar);
        forwarding = new MessageSelection.Purpose("select.forward", "primary-button",
                m -> messageActions.allows(m, MessageActions.Action.FORWARD), this::forward);
        deleting = new MessageSelection.Purpose("select.delete", "danger-button",
                m -> messageActions.allows(m, MessageActions.Action.DELETE_FOR_EVERYONE), this::delete);
        // "Capturar conversación…": a stretch between two markers, then a picture of it as the chat draws it.
        // Only what the server has can be an end: one still going out changes its id when it arrives, and the range emptied.
        capturing = new MessageSelection.Purpose("select.capture", "primary-button",
                m -> m.status() != app.unichat.model.MessageStatus.SENDING && m.status() != app.unichat.model.MessageStatus.FAILED,
                this::capture, true);
        messageActions.setOnCapture(m -> selection.start(m, capturing));
        messageActions.setOnForward(m -> selection.start(m, forwarding));
        messageActions.setOnDelete(m -> delete(List.of(m)), m -> selection.start(m, deleting));
        messageFixer = new MessageFixer(svc, text -> Platform.runLater(() -> toast.accept(text)));
        messageFixer.addListener(onTranscript); // each message being corrected says so in its row
        messageActions.setFixer(messageFixer);
        // Ajustes → Escribir con IA → "Corregir lo que envío": after (it goes at once and is edited once corrected) or
        // before (it waits in its chat's queue, corrected, and can be cancelled or sent as written meanwhile).
        svc.addListener(messageFixer);
        sendQueue = SendQueue.of(svc); // the service's: a window rebuilt shows and controls the same queue
        sendQueue.tellTo(text -> Platform.runLater(() -> toast.accept(text)));
        // What was written stays as a version. Asked for when it's used: AutoSave runs threads of its own, and made with
        // every window it kept every test's service (and window) alive until the run ran out of memory.
        sendQueue.onCorrected((chatId, sent, written) -> app.unichat.autosave.AutoSave.of(svc).wroteBefore(chatId, sent, written));
        composer.setInTurn(sendQueue::inOrder);
        sendQueueView = new SendQueueView(sendQueue, () -> chat == null ? null : chat.id());
        composer.showAbove(sendQueueView);
        composer.setSending((chatId, text, replyId) -> {
            // A command ("!wa sync" to a bridge, "/…") goes as typed: an AI's version of it would run unseen.
            String said = Message.withoutEmoteCommand(text);
            boolean command = said.startsWith("!") || said.startsWith("/");
            app.unichat.assist.AiSettings.FixMode mode = AiReplyButton.SHOWN.get() && !command ? app.unichat.assist.AiSettings.fixMode()
                    : app.unichat.assist.AiSettings.FixMode.OFF;
            if (mode == app.unichat.assist.AiSettings.FixMode.BEFORE) sendQueue.submit(chatId, text, replyId);
            else if (mode == app.unichat.assist.AiSettings.FixMode.AFTER) messageFixer.expect(chatId, Message.withoutEmoteCommand(text));
            return mode == app.unichat.assist.AiSettings.FixMode.BEFORE;
        });
        messageActions.setOnSavedText(m -> {
            if (savedTextSheet != null) savedTextSheet.show(app.unichat.autosave.AutoSave.of(svc).said(m));
        });
        scroll = new TimelineScroll(list, rows, this, () -> chat != null, this::loadOlder);
        headerButtons = headerButtons();
        header = buildHeader(headerButtons);
        messageRows = new MessageRowView(svc, media, messageActions, openLink, () -> chat, this::member, scroll::jumpTo);
        redrawOnTranscriptProgress();
        StackPane.setAlignment(scroll.indicator(), Pos.BOTTOM_RIGHT);
        StackPane timeline = new StackPane(list, scroll.indicator());
        composer.acceptDrops(timeline);

        typing.getStyleClass().add("typing-line");
        VBox bottom = new VBox(typing, composer);

        content.setTop(new VBox(header, selection.ends(), pinnedBar.node(), olderBar.node()));
        content.setCenter(timeline);
        content.setBottom(bottom);

        // Nothing selected
        Node logo = Icons.logo(88);
        Label hint = new Label(I18n.t("thread.emptyHint"));
        hint.getStyleClass().add("empty-title");
        Label keys = new Label(I18n.t("thread.emptyKeys", Shortcuts.key("K"), Shortcuts.key("E"), Shortcuts.key(",")));
        keys.getStyleClass().add("empty-hint");
        emptyState = new VBox(14, logo, hint, keys);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.getStyleClass().add("thread-empty");
        setCenter(emptyState);

        // Bubble widths come from the list's width when a cell is built: rebuilt (after the layout that changed it) only
        // when that width gives other bubbles. Opening the side panel used to rebuild every row, photos blinking, though
        // in most windows the bubbles stay as they were (MessageRowView.bubbleWidth).
        double[] built = {Double.NaN};
        FxCoalescer rewrap = new FxCoalescer(list::refresh);
        list.widthProperty().addListener((o, a, w) -> {
            double now = MessageRowView.bubbleWidth(w.doubleValue());
            if (Double.compare(now, built[0]) == 0) return; // a width, never -0 nor NaN once built
            built[0] = now;
            rewrap.request();
        });
    }

    private final HBox header;
    private final HBox headerButtons;

    private HBox buildHeader(HBox buttons) {
        headerTitle.getStyleClass().add("thread-title");
        headerSubtitle.getStyleClass().add("thread-subtitle");
        serverLink.getStyleClass().add("thread-server");
        Nodes.show(serverLink, false);
        // Its own click: the title area underneath toggles the info panel.
        serverLink.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);
        serverLink.setOnAction(e -> {
            if (serverLink.getUserData() instanceof String id) onShowServer.accept(id);
        });
        HBox subtitleRow = new HBox(4, serverLink, headerSubtitle);
        subtitleRow.setAlignment(Pos.CENTER_LEFT);
        headerSubtitle.setMinWidth(0);
        VBox titles = new VBox(1, headerTitle, subtitleRow);
        titles.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titles, Priority.ALWAYS);
        titles.setMinWidth(0);
        HBox row = new HBox(10, headerAvatar, titles, buttons);
        row.getStyleClass().add("thread-header");
        row.setAlignment(Pos.CENTER_LEFT);
        titles.setOnMouseClicked(e -> toggleInfo.run());
        return row;
    }

    /** The header's buttons: following, favourite, archive, the info panel and the chat's menu. */
    private HBox headerButtons() {
        infoButton.setGraphic(Icons.icon(Icons.INFO, 18));
        infoButton.getStyleClass().add("icon-button");
        infoButton.setTooltip(new javafx.scene.control.Tooltip(I18n.t("thread.chatInfo")));
        infoButton.setOnAction(e -> toggleInfo.run());
        infoButton.setAccessibleText(I18n.t("thread.chatInfo"));
        Button more = Icons.iconButton(Icons.MORE, I18n.t("thread.moreOptions"), null);
        more.setOnAction(e -> {
            Chat c = chat;
            if (c != null) ChatMenus.forChat(c, svc, folderDialogs, null).show(more, Side.BOTTOM, 0, 4);
        });
        HBox buttons = new HBox(10, scroll.following(), favButton, archiveButton, infoButton, more);
        buttons.setAlignment(Pos.CENTER_LEFT);
        return buttons;
    }

    private void configureTimeline() {
        list.getStyleClass().add("timeline");
        list.setCellFactory(v -> new RowCell());
        list.setAccessibleText(I18n.t("thread.messages"));
        list.setAccessibleHelp(I18n.t("thread.messagesHelp", Shortcuts.key("C")));
        list.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::onTimelineKey);
        list.focusedProperty().addListener((o, a, focused) -> {
            if (focused && list.getSelectionModel().isEmpty() && !rows.isEmpty()) {
                list.getSelectionModel().select(rows.size() - 1);
            }
        });
    }

    /**
     * Transcript progress and a message being corrected redraw the visible rows; coalesced so a model download doesn't
     * flood the FX thread.
     */
    private final FxCoalescer transcriptRedraw = new FxCoalescer(list::refresh);
    private final Runnable onTranscript = transcriptRedraw::request;

    private void redrawOnTranscriptProgress() {
        Transcripts.get().addListener(onTranscript);
    }

    /** Stops listening to what outlives this pane (the transcripts are the app's): the window is being rebuilt. */
    public void dispose() {
        Transcripts.get().removeListener(onTranscript);
        messageFixer.removeListener(onTranscript);
        svc.removeListener(messageFixer);
        sendQueueView.dispose();
        sendQueue.tellTo(System.err::println); // the queue outlives this window: it mustn't hold it
        pinnedBar.dispose();
    }

    /**
     * Goes to a pinned message: selected and in view, the chat's history loaded page by page until it's there however
     * far back it is; told when the chat's start comes first (it was deleted).
     */
    private void reveal(app.unichat.model.PinnedMessage pin) {
        if (chat == null) return;
        String id = chat.id();
        for (Message m : svc.messages(id)) {
            if (m.id().equals(pin.messageId())) {
                scroll.jumpTo(m);
                list.requestFocus();
                return;
            }
        }
        if (reachedStart) {
            toast.accept(I18n.t("pinned.gone"));
            return;
        }
        loadOlder(() -> {
            if (chat != null && chat.id().equals(id)) reveal(pin);
        });
    }

    /** Selects and shows {@code target} (the message a reply quotes). */
    private void jumpTo(Message target) { scroll.jumpTo(target); }

    /** Shows {@code m} in the timeline, selected (from the profile's media); the keys work from there. */
    public void jumpToMessage(Message m) {
        scroll.jumpTo(m);
        list.requestFocus();
    }

    public @Nullable Chat chat() { return chat; }

    /** Where forwarding asks which chats (the window's overlay). */
    public void setForwardSheet(ForwardSheet sheet) { this.forwardSheet = sheet; }

    /** Asks where {@code messages} go; once they're sent, picking ends. */
    private void forward(List<Message> messages) {
        if (forwardSheet == null || chat == null || messages.isEmpty()) return;
        forwardSheet.open(messages, chat, selection::end, () -> {
            if (selection.active()) list.requestFocus();
            else composer.focus();
        });
    }

    /** Where deleting for everyone asks twice (the window's overlay). */
    public void setDeleteSheet(DeleteSheet sheet) { this.deleteSheet = sheet; }

    /** Where a capture of a stretch of the chat is looked at and sent (the window's overlay). */
    public void setCaptureSheet(CaptureSheet sheet) { this.captureSheet = sheet; }

    /**
     * A picture of {@code messages} (the stretch picked) as the chat draws them, under the chat's header: the sheet
     * shows it and sends, copies or saves it. Picking ends.
     */
    private void capture(List<Message> messages) {
        selection.end();
        if (captureSheet == null || chat == null || messages.isEmpty()) return;
        double width = ConversationShot.WIDTH;
        Chat of = chat; // taken now: the open chat may change while the sheet is up (a notice's click)
        javafx.scene.Scene look = getScene();
        captureSheet.open(messages.size(), of, footer -> ConversationShot.take(captureHeader(of), messages,
                mr -> messageRows.node(mr, width), look, width, footer, java.time.Duration.ofSeconds(15)));
    }

    /**
     * What a capture's header says under the chat's name: its network and, in a group, how many are in it. Never the
     * account it's on (a phone number) nor one's own settings (muted): the picture goes to others.
     */
    static String captureSubtitle(Chat c) {
        String sub = c.network().displayName();
        return c.group() ? sub + " · " + I18n.t("thread.participants", c.members().size()) : sub;
    }

    /** {@code c}'s header as a capture shows it: its picture, name and what's under it, without the buttons. */
    Node captureHeader(Chat c) {
        Avatar avatar = new Avatar(34);
        avatar.set(c.title(), c.id(), c.avatarUrl(), svc);
        avatar.setNetwork(c.network(), Settings.get().showNetworkBadges());
        Label title = new Label(c.title());
        title.getStyleClass().add("thread-title");
        Label sub = new Label(captureSubtitle(c));
        sub.getStyleClass().add("thread-subtitle");
        VBox titles = new VBox(1, title, sub);
        titles.setAlignment(Pos.CENTER_LEFT);
        HBox h = new HBox(10, avatar, titles);
        h.getStyleClass().add("thread-header");
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** Where what a deleted or edited message said shows (the window's overlay). */
    public void setSavedTextSheet(SavedTextSheet sheet) { this.savedTextSheet = sheet; }

    /**
     * Asks twice about deleting {@code messages} for everyone; after the second yes they go, one at a time in the chat's
     * order, picking ends, and a notice says how it went.
     */
    private void delete(List<Message> messages) {
        if (deleteSheet == null || chat == null || messages.isEmpty()) return;
        String chatId = chat.id();
        deleteSheet.ask(messages, chat, () -> {
            selection.end();
            java.util.concurrent.CompletableFuture<Integer> failed = java.util.concurrent.CompletableFuture.completedFuture(0);
            for (Message m : messages) {
                failed = failed.thenCompose(n -> svc.deleteMessage(chatId, m.id()).handle((v, e) -> e == null ? n : n + 1));
            }
            var _ = failed.thenAccept(n -> Platform.runLater(() -> toast.accept(n == 0 ? I18n.n("delete.done", messages.size())
                    : I18n.n("delete.failed", n))));
        });
    }

    /** The open chat's id, for the snapshot's diagnostics below: the snapshot opens a chat before it asks for one. */
    private String shownChatId() {
        return java.util.Objects.requireNonNull(chat, "the snapshot opens a chat before it shows this").id();
    }

    /** Snapshot diagnostics: the question before deleting the newest three of mine that can be deleted for everyone. */
    public void showDeleting() {
        List<Message> mine = svc.messages(shownChatId()).stream().filter(deleting.pickable()).toList();
        delete(mine.subList(Math.max(0, mine.size() - 3), mine.size()));
    }

    /**
     * Snapshot diagnostics: the newest six messages picked as a stretch to capture, or ({@code sheet}) their capture's
     * sheet, as phone pieces.
     */
    public void showCapture(boolean sheet) {
        List<Message> all = svc.messages(shownChatId());
        if (all.size() < 2) return;
        List<Message> six = all.subList(Math.max(0, all.size() - 6), all.size());
        selection.start(six.getFirst(), capturing);
        selection.toggle(six.getLast());
        if (sheet) capture(selection.messages());
    }

    /** Snapshot diagnostics: four texts waiting to be corrected before they go (answers that never come). */
    public void showQueue() {
        sendQueue.useWriter(() -> prompt -> {
            try {
                new java.util.concurrent.CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return prompt;
        });
        for (String t : snapshotDrafts()) sendQueue.submit(shownChatId(), t, null);
    }

    /** The four texts {@link #showQueue} puts in the queue, as the person would write them, in the UI language. */
    static List<String> snapshotDrafts() {
        return List.of(I18n.t("demo.draft.1"), I18n.t("demo.draft.2"), I18n.t("demo.draft.3"), I18n.t("demo.draft.4"));
    }

    /** Snapshot diagnostics: my newest message being corrected with AI (an answer that never comes). */
    public void showFixing() {
        List<Message> mine = svc.messages(shownChatId()).stream().filter(Message::outgoing).toList();
        if (mine.isEmpty()) return;
        messageFixer.useWriter(() -> prompt -> {
            try {
                new java.util.concurrent.CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return prompt;
        });
        var _ = messageFixer.fix(mine.getLast());
    }

    /**
     * Snapshot diagnostics: what the newest message said, as if written, corrected with AI, then edited; {@code none}: the
     * sheet for a message there's no copy of.
     */
    public void showSavedText(boolean none) {
        if (savedTextSheet == null || svc.messages(shownChatId()).isEmpty()) return;
        Message m = svc.messages(shownChatId()).getLast();
        Instant at = m.timestamp() == null ? Instant.now() : m.timestamp();
        List<app.unichat.autosave.SavedText> versions = none ? List.of() : List.of(
                new app.unichat.autosave.SavedText(m.id(), shownChatId(), at, at, m.senderName(), m.senderId(), m.outgoing(), m.body().toLowerCase(Locale.ROOT), true),
                new app.unichat.autosave.SavedText(m.id(), shownChatId(), at, at.plusSeconds(5), m.senderName(), m.senderId(), m.outgoing(), m.body() + "…", false),
                new app.unichat.autosave.SavedText(m.id(), shownChatId(), at, at.plusSeconds(90), m.senderName(), m.senderId(), m.outgoing(), m.body(), false));
        savedTextSheet.show(java.util.concurrent.CompletableFuture.completedFuture(versions));
    }

    /** Snapshot diagnostics: the sheet for the newest message, or ({@code several}) the newest three picked. */
    public void showForwarding(boolean several) {
        List<Message> last = svc.messages(shownChatId()).stream().filter(m -> messageActions.allows(m, MessageActions.Action.FORWARD)).toList();
        if (last.isEmpty()) return;
        if (!several) {
            forward(List.of(last.getLast()));
            return;
        }
        List<Message> three = last.subList(Math.max(0, last.size() - 3), last.size());
        selection.start(three.getFirst(), forwarding);
        three.subList(1, three.size()).forEach(selection::toggle);
    }

    /** While picking, the bar takes the header's buttons' place; the message box stays. */
    private void showSelectionBar(boolean shown) {
        header.getChildren().set(2, shown ? selection.bar() : headerButtons);
        if (!shown) composer.focus();
    }
    public Composer composer() { return composer; }

    public void setInfoSelected(boolean selected) { infoButton.setSelected(selected); }

    public void open(@Nullable Chat c) {
        selection.end();
        chat = c;
        reachedStart = false;
        loadingOlder = false;
        afterOlder.clear(); // a pinned message another chat was looking for
        pinnedBar.show(c == null ? null : c.id());
        showOlderBar();
        scroll.pin();
        setCenter(c == null ? emptyState : content);
        composer.setChat(c == null ? null : c.id());
        sendQueueView.refresh(); // the new chat's own queue
        if (c == null) return;
        refreshHeader();
        rebuild(true);
        composer.focus();
        if (svc.messages(c.id()).size() < 20 && svc.hasOlder(c.id())) loadOlder(); // nothing to ask: no "Cargando…" flash
    }

    /** Called with a server's id when its name in the header is clicked. */
    public void setOnShowServer(Consumer<String> r) { this.onShowServer = r; }
    /** Opens a sticker's set (its link) from the sticker's menu. */
    public void setOnShowStickerSet(Consumer<String> c) { messageActions.setOnShowStickerSet(c); }


    public void refreshHeader() {
        if (chat == null) return;
        headerAvatar.set(chat.title(), chat.id(), chat.avatarUrl(), svc);
        headerAvatar.setNetwork(chat.network(), Settings.get().showNetworkBadges());
        headerTitle.setText(chat.title());
        Chat server = SpaceTree.serverOf(chat, svc.chats());
        Nodes.show(serverLink, server != null);
        if (server != null) {
            serverLink.setText(server.title() + " ›");
            serverLink.setUserData(server.id());
            serverLink.setAccessibleText(I18n.t("thread.viewChannels", server.title()));
            serverLink.setTooltip(new javafx.scene.control.Tooltip(I18n.t("thread.viewChannels", server.title())));
        }
        StringBuilder sub = new StringBuilder(chat.network().displayName());
        String account = Accounts.forChat(svc, chat);
        if (account != null) sub.append(" · ").append(account);
        if (chat.group()) sub.append(" · ").append(I18n.t("thread.participants", chat.members().size()));
        if (chat.muted()) sub.append(" · ").append(I18n.t("thread.muted"));
        headerSubtitle.setText(sub.toString());
        Nodes.style(favButton, "active", chat.favorite());
        Nodes.style(archiveButton, "active", chat.archived());
        TypingDots.show(typing, chat.typing().isEmpty() ? null : Formats.typing(chat.typing()));
        Nodes.show(typing, !chat.typing().isEmpty());
    }

    /** Re-reads the messages of the open chat. Keeps the viewport pinned to the bottom if it was there. */
    public void rebuild(boolean forceBottom) {
        if (chat == null) return;
        boolean wasAtBottom = forceBottom || scroll.pinned();
        Message selected = selectedMessage();
        List<Message> msgs = svc.messages(chat.id());
        // Only being at the newest message follows it: sending one of yours while reading older ones leaves you there
        // (the indicator counts it among the new ones below), as the person asked.
        composer.refreshVoice(); // what the chat takes can arrive after it was opened
        composer.hearAhead(); // a voice note that just arrived is ready when a draft answers it
        messageRows.index(msgs);
        List<ThreadRows.Row> next = ThreadRows.build(msgs, ZoneId.systemDefault());
        if (forceBottom) rows.setAll(next);
        else ThreadRows.update(rows, next);
        // Drawn from elsewhere than the messages: photos of who's in the chat, what it allows, and "Hoy" on the day separators.
        int surroundings = java.util.Objects.hash(chat.id(), chat.members(), chat.features(), java.time.LocalDate.now(ZoneId.systemDefault()));
        if (surroundings != drawnWith && !forceBottom) list.refresh();
        drawnWith = surroundings;
        if (selected != null && !forceBottom) select(selected);
        if (wasAtBottom && !(list.isFocused() && selected != null)) scroll.scrollToBottom();
        showOlderBar(); // a sync can tell there's history before any load did
    }

    /** "Cargar mensajes anteriores" while the chat has older history, working while it loads. */
    private void showOlderBar() {
        Chat c = chat;
        boolean was = olderBar.shown();
        Boolean shown = shownOlder;
        if (shown != null) olderBar.show(true, shown);
        else olderBar.show(c != null && !reachedStart && svc.hasOlder(c.id()), loadingOlder);
        // Gone under the keyboard's focus (the chat's start): the focus goes on to the oldest message, not nowhere.
        // The scene's focus owner, not isFocused(): that one needs the window to have the system's focus.
        if (was && !olderBar.shown() && getScene() != null && getScene().getFocusOwner() == olderBar.button()) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof ThreadRows.MessageRow) {
                    list.getSelectionModel().select(i);
                    break;
                }
            }
            list.requestFocus();
        }
    }

    /** Snapshot diagnostics: the bar loading older messages shown, working when {@code loading}. */
    public void showOlder(boolean loading) {
        shownOlder = loading;
        showOlderBar();
    }

    /** The bar loading older messages, for tests. */
    OlderBar olderBar() { return olderBar; }

    /** The same messages after a change of appearance (text size, settings): they all look different. */
    public void redraw() {
        rebuild(false);
        list.refresh();
    }

    /** Reaching the top or the button: only while the chat has older history (a short one's scrollbar sits at the top). */
    private void loadOlder() {
        Chat c = chat;
        if (c == null || !svc.hasOlder(c.id())) return;
        loadOlder(() -> { });
    }

    /** Older messages, then {@code then} (also when a load already going ends); nothing past the chat's start. */
    private void loadOlder(Runnable then) {
        if (reachedStart || chat == null) return;
        afterOlder.add(then);
        if (loadingOlder) return;
        loadingOlder = true;
        showOlderBar();
        String id = chat.id();
        // Loaded because the chat is short (not because you scrolled to the top): keep showing the newest.
        boolean followBottom = scroll.pinned();
        var _ = svc.loadOlder(id).whenComplete((more, err) -> Platform.runLater(() -> {
            if (chat == null || !chat.id().equals(id)) return; // another chat's load, if any, is its own
            loadingOlder = false;
            if (err != null) {
                // Said, and the button is there to try again; what waited (a pinned message) gives up with it.
                afterOlder.clear();
                showOlderBar();
                toast.accept(I18n.t("thread.olderFailed", app.unichat.support.Errors.message(err)));
                return;
            }
            if (Boolean.FALSE.equals(more)) reachedStart = true;
            List<Message> all = svc.messages(id);
            messageRows.index(all);
            rows.setAll(ThreadRows.build(all, ZoneId.systemDefault()));
            scroll.afterOlder(followBottom);
            showOlderBar();
            List<Runnable> waiting = List.copyOf(afterOlder);
            afterOlder.clear();
            waiting.forEach(Runnable::run);
        }));
    }

    private @Nullable Member member(String userId) {
        if (chat == null) return null;
        for (Member m : chat.members()) if (m.id().equals(userId)) return m;
        return null;
    }

    // ------------------------------------------------------------------ rendering

    private final class RowCell extends ListCell<ThreadRows.Row> implements MessageSelection.Shows {
        /** The message's own row, before the range's markers (if any) go around it. */
        private @Nullable Node drawn;

        RowCell() {
            getStyleClass().add("row-cell");
            // Sized to the viewport, not to the widest row (mistakes.md): otherwise one wide row stretched every cell
            // and outgoing bubbles, aligned right, ended past the visible edge.
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(ThreadRows.Row row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            if (empty || row == null) {
                setGraphic(null);
                setAccessibleText(null);
                pseudoClassStateChanged(MessageSelection.PICKED, false);
                return;
            }
            if (row instanceof ThreadRows.DayRow d) {
                drawn = null;
                Node day = MessageRowView.dayRow(d);
                setGraphic(day);
                setAccessibleText(((Label) ((HBox) day).getChildren().getFirst()).getText());
            } else if (row instanceof ThreadRows.MessageRow mr) {
                drawn = messageRows.node(mr, getListView().getWidth());
                setGraphic(drawn);
                showActions(isSelected());
            }
            showPicked();
        }

        @Override
        public void showPicked() {
            boolean picked = getItem() instanceof ThreadRows.MessageRow mr && selection.picked(mr.message());
            pseudoClassStateChanged(MessageSelection.PICKED, picked);
            showActions(isSelected());
            if (getItem() instanceof ThreadRows.MessageRow mr && drawn != null) {
                boolean start = selection.rangeStart(mr.message());
                boolean end = selection.rangeEnd(mr.message());
                Node shown = RangeMarkers.around(drawn, start, end, () -> selection.removeRangeEnd(true), () -> selection.removeRangeEnd(false));
                if (getGraphic() != shown) setGraphic(shown);
            }
            if (getItem() instanceof ThreadRows.MessageRow mr) {
                setAccessibleText(messageRows.describe(mr.message()) + (picked ? ". " + I18n.t("select.picked") : ""));
            }
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            showActions(selected);
        }

        private void showActions(boolean selected) {
            if (drawn instanceof MessageLine line) line.showActions(selected); // inside the range's markers too
        }
    }

    /** The message selected in the timeline (the keyboard's, or one gone to), or null. */
    public @Nullable Message selectedMessage() {
        return list.getSelectionModel().getSelectedItem() instanceof ThreadRows.MessageRow mr ? mr.message() : null;
    }

    private void select(Message target) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ThreadRows.MessageRow mr && mr.message().id().equals(target.id())) {
                list.getSelectionModel().select(i);
                return;
            }
        }
    }

    /** A message's actions a plain key does, under the same rules as its menu ({@link MessageActionsUi#run}). */
    private static final java.util.Map<javafx.scene.input.KeyCode, MessageActions.Action> KEY_ACTIONS = java.util.Map.of(
            javafx.scene.input.KeyCode.R, MessageActions.Action.REPLY,
            javafx.scene.input.KeyCode.T, MessageActions.Action.TRANSCRIBE,
            javafx.scene.input.KeyCode.E, MessageActions.Action.EDIT);

    /** Keyboard control of the focused timeline. Arrow keys move the selection (ListView default). */
    private void onTimelineKey(javafx.scene.input.KeyEvent e) {
        Message m = selectedMessage();
        if (selection.key(e, m)) return;
        javafx.scene.input.KeyCode code = e.getCode();
        if (code == javafx.scene.input.KeyCode.ESCAPE) {
            composer.focus();
        } else if (m == null || m.type() == MessageType.SYSTEM) {
            return;
        } else if (KEY_ACTIONS.containsKey(code) && !e.isShortcutDown()) {
            MessageActions.Action act = KEY_ACTIONS.get(code);
            if (!messageActions.allows(m, act)) return;
            messageActions.run(act, m);
        } else if (code == javafx.scene.input.KeyCode.P && !e.isShortcutDown() && m.type() == MessageType.AUDIO && m.mediaUrl() != null) {
            audio.toggle(m, chat);
        } else if (code == javafx.scene.input.KeyCode.C && e.isShortcutDown() && !m.isMedia()) {
            Clipboards.copy(m.body());
        } else if (code == javafx.scene.input.KeyCode.ENTER || code == javafx.scene.input.KeyCode.SPACE
                || code == javafx.scene.input.KeyCode.CONTEXT_MENU) { // a deleted one's too: what it said
            var cell = list.lookupAll(".row-cell").stream()
                    .filter(n -> n instanceof RowCell rc && rc.isSelected()).findFirst().orElse(list);
            messageActions.messageMenu(m).show(cell, Side.BOTTOM, 40, -10);
        } else { // no key reacts: a digit typed here is a digit for the message box (reacting is the menu's, meant)
            return;
        }
        e.consume();
    }

    /** Moves keyboard focus into the message list (F6 cycle). */
    public void focusTimeline() {
        if (chat == null) return;
        list.requestFocus();
        // Explicit, not only via the focused listener: a node only counts as focused while its window has the
        // OS focus, so F6 must not depend on that.
        if (list.getSelectionModel().isEmpty() && !rows.isEmpty()) list.getSelectionModel().select(rows.size() - 1);
    }

    /** The reply a sticker or GIF sent now answers: {@link Composer#takeReply}. */
    public @Nullable String takeReply() {
        return composer.takeReply();
    }
}

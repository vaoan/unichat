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

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

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
    private Chat chat;
    private boolean loadingOlder;
    private boolean reachedStart;
    private int drawnWith;
    /** Newest message shown, to notice your own new message (always jumps to it). */

    private final FolderDialogs folderDialogs;

    public ThreadPane(ChatService svc, Consumer<String> openLink, Runnable toggleInfo, Consumer<String> toast, AudioPlayback audio,
                      FolderDialogs folderDialogs, MediaLibrary library) {
        this.audio = audio;
        this.folderDialogs = folderDialogs;
        this.svc = svc;
        this.toggleInfo = toggleInfo;
        getStyleClass().add("thread-pane");
        composer = new Composer(svc, toast);
        composer.setBeforeRecording(audio::pause);
        media = new MessageMedia(svc, openLink, toast, library, this::getScene, audio);
        messageActions = new MessageActionsUi(svc, toast, composer, media, () -> chat, library, this::getScene, this::member,
                this::jumpTo);

        favButton = Icons.iconButton(Icons.STAR, I18n.t("thread.favorite"), () -> svc.setFavorite(chat.id(), !chat.favorite()));
        archiveButton = Icons.iconButton(Icons.ARCHIVE, I18n.t("thread.archive", Shortcuts.key("E")),
                () -> svc.setArchived(chat.id(), !chat.archived()));
        Node header = buildHeader();

        configureTimeline();
        scroll = new TimelineScroll(list, rows, this, () -> chat != null, this::loadOlder);
        messageRows = new MessageRowView(svc, media, messageActions, openLink, () -> chat, this::member, scroll::jumpTo);
        redrawOnTranscriptProgress();
        StackPane.setAlignment(scroll.indicator(), Pos.BOTTOM_RIGHT);
        StackPane timeline = new StackPane(list, scroll.indicator());
        composer.acceptDrops(timeline);

        typing.getStyleClass().add("typing-line");
        VBox bottom = new VBox(typing, composer);

        content.setTop(header);
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
            if (now == built[0]) return;
            built[0] = now;
            rewrap.request();
        });
    }

    private Node buildHeader() {
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
        infoButton.setGraphic(Icons.icon(Icons.INFO, 18));
        infoButton.getStyleClass().add("icon-button");
        infoButton.setTooltip(new javafx.scene.control.Tooltip(I18n.t("thread.chatInfo")));
        infoButton.setOnAction(e -> toggleInfo.run());
        infoButton.setAccessibleText(I18n.t("thread.chatInfo"));
        Button more = Icons.iconButton(Icons.MORE, I18n.t("thread.moreOptions"), null);
        more.setOnAction(e -> ChatMenus.forChat(chat, svc, folderDialogs, null).show(more, Side.BOTTOM, 0, 4));
        HBox header = new HBox(10, headerAvatar, titles, favButton, archiveButton, infoButton, more);
        header.getStyleClass().add("thread-header");
        header.setAlignment(Pos.CENTER_LEFT);
        titles.setOnMouseClicked(e -> toggleInfo.run());
        return header;
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

    /** Transcript progress redraws the visible rows; coalesced so a model download doesn't flood the FX thread. */
    private final FxCoalescer transcriptRedraw = new FxCoalescer(list::refresh);
    private final Runnable onTranscript = transcriptRedraw::request;

    private void redrawOnTranscriptProgress() {
        Transcripts.get().addListener(onTranscript);
    }

    /** Stops listening to what outlives this pane (the transcripts are the app's): the window is being rebuilt. */
    public void dispose() {
        Transcripts.get().removeListener(onTranscript);
    }

    /** Selects and shows {@code target} (the message a reply quotes). */
    private void jumpTo(Message target) { scroll.jumpTo(target); }

    /** Shows {@code m} in the timeline, selected (from the profile's media); the keys work from there. */
    public void jumpToMessage(Message m) {
        scroll.jumpTo(m);
        list.requestFocus();
    }

    public Chat chat() { return chat; }
    public Composer composer() { return composer; }

    public void setInfoSelected(boolean selected) { infoButton.setSelected(selected); }

    public void open(Chat c) {
        chat = c;
        reachedStart = false;
        loadingOlder = false;
        scroll.pin();
        setCenter(c == null ? emptyState : content);
        composer.setChat(c == null ? null : c.id());
        if (c == null) return;
        refreshHeader();
        rebuild(true);
        composer.focus();
        if (svc.messages(c.id()).size() < 20) loadOlder();
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
        String sub = chat.network().displayName();
        String account = Accounts.forChat(svc, chat);
        if (account != null) sub += " · " + account;
        if (chat.group()) sub += " · " + I18n.t("thread.participants", chat.members().size());
        if (chat.muted()) sub += " · " + I18n.t("thread.muted");
        headerSubtitle.setText(sub);
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
        if (forceBottom) rows.setAll(next); else ThreadRows.update(rows, next);
        // Drawn from elsewhere than the messages: photos of who's in the chat, what it allows, and "Hoy" on the day separators.
        int surroundings = java.util.Objects.hash(chat.id(), chat.members(), chat.features(), java.time.LocalDate.now());
        if (surroundings != drawnWith && !forceBottom) list.refresh();
        drawnWith = surroundings;
        if (selected != null && !forceBottom) select(selected);
        if (wasAtBottom && !(list.isFocused() && selected != null)) scroll.scrollToBottom();
    }

    /** The same messages after a change of appearance (text size, settings): they all look different. */
    public void redraw() {
        rebuild(false);
        list.refresh();
    }

    private void loadOlder() {
        if (loadingOlder || reachedStart || chat == null) return;
        loadingOlder = true;
        String id = chat.id();
        // Loaded because the chat is short (not because you scrolled to the top): keep showing the newest.
        boolean followBottom = scroll.pinned();
        svc.loadOlder(id).whenComplete((more, err) -> Platform.runLater(() -> {
            loadingOlder = false;
            if (chat == null || !chat.id().equals(id)) return;
            if (err != null || Boolean.FALSE.equals(more)) reachedStart = true;
            List<Message> all = svc.messages(id);
            messageRows.index(all);
            rows.setAll(ThreadRows.build(all, ZoneId.systemDefault()));
            scroll.afterOlder(followBottom);
        }));
    }

    private Member member(String userId) {
        if (chat == null) return null;
        for (Member m : chat.members()) if (m.id().equals(userId)) return m;
        return null;
    }

    // ------------------------------------------------------------------ rendering

    private final class RowCell extends ListCell<ThreadRows.Row> {
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
                return;
            }
            if (row instanceof ThreadRows.DayRow d) {
                Label l = new Label(Formats.daySeparator(d.day(), Instant.now(), ZoneId.systemDefault()));
                l.getStyleClass().add("day-separator");
                HBox box = new HBox(l);
                box.setAlignment(Pos.CENTER);
                box.getStyleClass().add("day-row");
                setGraphic(box);
                setAccessibleText(l.getText());
            } else if (row instanceof ThreadRows.MessageRow mr) {
                setGraphic(messageRows.node(mr, getListView().getWidth()));
                setAccessibleText(messageRows.describe(mr.message()));
                showActions(isSelected());
            }
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            showActions(selected);
        }

        private void showActions(boolean selected) {
            if (getGraphic() instanceof MessageLine line) line.showActions(selected);
        }
    }

    /** The message selected in the timeline (the keyboard's, or one gone to), or null. */
    public Message selectedMessage() {
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

    /** Keyboard control of the focused timeline. Arrow keys move the selection (ListView default). */
    private void onTimelineKey(javafx.scene.input.KeyEvent e) {
        Message m = selectedMessage();
        javafx.scene.input.KeyCode code = e.getCode();
        if (code == javafx.scene.input.KeyCode.ESCAPE) {
            composer.focus();
        } else if (m == null || m.type() == MessageType.SYSTEM) {
            return;
        } else if (code == javafx.scene.input.KeyCode.R && !e.isShortcutDown() && messageActions.allows(m, MessageActions.Action.REPLY)) {
            composer.reply(m);
        } else if (code == javafx.scene.input.KeyCode.T && !e.isShortcutDown() && messageActions.allows(m, MessageActions.Action.TRANSCRIBE)) {
            media.transcribe(m);
        } else if (code == javafx.scene.input.KeyCode.P && !e.isShortcutDown() && m.type() == MessageType.AUDIO && m.mediaUrl() != null) {
            audio.toggle(m, chat);
        } else if (code == javafx.scene.input.KeyCode.E && !e.isShortcutDown() && messageActions.allows(m, MessageActions.Action.EDIT)) {
            composer.edit(m);
        } else if (code == javafx.scene.input.KeyCode.C && e.isShortcutDown() && !m.isMedia()) {
            Clipboards.copy(m.body());
        } else if ((code == javafx.scene.input.KeyCode.ENTER || code == javafx.scene.input.KeyCode.SPACE
                || code == javafx.scene.input.KeyCode.CONTEXT_MENU) && !m.deleted()) {
            var cell = list.lookupAll(".row-cell").stream()
                    .filter(n -> n instanceof RowCell rc && rc.isSelected()).findFirst().orElse(list);
            messageActions.messageMenu(m).show(cell, Side.BOTTOM, 40, -10);
        } else if (code.isDigitKey() && !e.isShortcutDown() && !m.deleted()) {
            int n = code.getCode() - javafx.scene.input.KeyCode.DIGIT1.getCode();
            java.util.List<String> quick = messageActions.quick(); // 1 to 6: the quick reactions, as the popup shows them
            if (n < 0 || n >= quick.size()) return;
            messageActions.react(m, quick.get(n));
        } else {
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
    public String takeReply() {
        return composer.takeReply();
    }
}

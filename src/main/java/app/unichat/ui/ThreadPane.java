package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.stt.Transcripts;
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
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

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
    private final Label typing = new Label();
    private final Button jumpDown;
    private final Consumer<String> openLink;
    private final Consumer<String> toast;
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
    private ScrollBar vbar;
    /**
     * True while the viewport follows new messages. Only the person scrolling (wheel, keys, the scrollbar) unpins it:
     * the scrollbar also moves when a photo or sticker finishes loading and a bubble grows, which isn't "scrolled up".
     */
    private boolean pinnedBottom = true;
    private final java.util.Map<String, Message> loaded = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> mediaAt = new java.util.HashMap<>();
    private int drawnWith;
    /** When the person last scrolled by hand (ms); scrollbar moves shortly after count as theirs. */
    private long userScrollAt;
    /** Newest message shown, to notice your own new message (always jumps to it). */
    private String lastMessageId;

    private final FolderDialogs folderDialogs;

    public ThreadPane(ChatService svc, Consumer<String> openLink, Runnable toggleInfo, Consumer<String> toast, AudioPlayback audio,
                      FolderDialogs folderDialogs, MediaLibrary library) {
        this.audio = audio;
        this.folderDialogs = folderDialogs;
        this.svc = svc;
        this.openLink = openLink;
        this.toggleInfo = toggleInfo;
        this.toast = toast;
        getStyleClass().add("thread-pane");
        composer = new Composer(svc, toast);
        composer.setBeforeRecording(audio::pause);
        media = new MessageMedia(svc, openLink, toast, library, this::getScene, audio);
        messageActions = new MessageActionsUi(svc, toast, composer, media, () -> chat, library, this::getScene, this::member);

        favButton = Icons.iconButton(Icons.STAR, "Favorito", () -> svc.setFavorite(chat.id(), !chat.favorite()));
        archiveButton = Icons.iconButton(Icons.ARCHIVE, "Archivar (" + Shortcuts.key("E") + ")",
                () -> svc.setArchived(chat.id(), !chat.archived()));
        Node header = buildHeader();

        configureTimeline();
        jumpDown = Icons.iconButton(Icons.LOW, "Ir al final", this::scrollToBottom);
        redrawOnTranscriptProgress();
        jumpDown.getStyleClass().add("jump-down");
        jumpDown.setVisible(false);
        StackPane.setAlignment(jumpDown, Pos.BOTTOM_RIGHT);
        StackPane timeline = new StackPane(list, jumpDown);
        timeline.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        timeline.setOnDragDropped(e -> {
            composer.sendFiles(e.getDragboard().getFiles());
            e.setDropCompleted(true);
        });

        typing.getStyleClass().add("typing-line");
        VBox bottom = new VBox(typing, composer);

        content.setTop(header);
        content.setCenter(timeline);
        content.setBottom(bottom);

        // Nothing selected
        Node logo = Icons.logo(88);
        Label hint = new Label("Todos tus chats, en un solo lugar");
        hint.getStyleClass().add("empty-title");
        Label keys = new Label(Shortcuts.key("K") + " buscar   ·   Alt+↑/↓ cambiar de chat   ·   " + Shortcuts.key("E") + " archivar   ·   " + Shortcuts.key(",") + " ajustes");
        keys.getStyleClass().add("empty-hint");
        emptyState = new VBox(14, logo, hint, keys);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.getStyleClass().add("thread-empty");
        setCenter(emptyState);

        list.skinProperty().addListener((o, a, s) -> Platform.runLater(this::hookScrollBar));
        // Bubble widths come from the list's width when a cell is built: rebuild them once it settles (opening the side
        // panel narrowed the list and cut outgoing bubbles at the right edge).
        javafx.animation.PauseTransition rewrap = new javafx.animation.PauseTransition(javafx.util.Duration.millis(120));
        rewrap.setOnFinished(e -> list.refresh());
        list.widthProperty().addListener((o, a, w) -> {
            if (pinnedBottom && chat != null) scrollToBottom();
            if (Math.abs(w.doubleValue() - a.doubleValue()) > 1) rewrap.playFromStart();
        });
        // After every layout: if we follow the bottom and something grew (media loaded, text wrapped), stay there.
        sceneProperty().addListener((o, a, sc) -> {
            if (a != null) a.removePostLayoutPulseListener(keepBottom);
            if (sc != null) sc.addPostLayoutPulseListener(keepBottom);
        });
    }

    private final Runnable keepBottom = () -> {
        if (!pinnedBottom || chat == null || rows.isEmpty() || getCenter() != content) return;
        if (!lastRowFullyVisible()) list.scrollTo(rows.size() - 1);
    };

    private void userScrolled() {
        userScrollAt = System.currentTimeMillis();
    }

    private boolean lastRowFullyVisible() {
        if (!(list.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow)) return true;
        javafx.scene.control.IndexedCell<?> last = flow.getLastVisibleCell();
        if (last == null) return true;
        if (last.getIndex() != rows.size() - 1) return false;
        double bottom = last.localToScene(last.getBoundsInLocal()).getMaxY();
        return bottom <= flow.localToScene(flow.getBoundsInLocal()).getMaxY() + 1;
    }

    private void hookScrollBar() {
        for (Node n : list.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                vbar = sb;
                sb.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> userScrolled());
                sb.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> userScrolled());
                sb.valueProperty().addListener((o, a, v) -> {
                    // Only the person's own scrolling changes whether we follow the bottom.
                    if (System.currentTimeMillis() - userScrollAt < 600) pinnedBottom = atBottom();
                    jumpDown.setVisible(!pinnedBottom && !atBottom());
                    if (v.doubleValue() <= sb.getMin() + 0.0001 && chat != null) loadOlder();
                });
            }
        }
    }

    private boolean atBottom() {
        return vbar == null || !vbar.isVisible() || vbar.getValue() >= vbar.getMax() - 0.002;
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
        infoButton.setTooltip(new javafx.scene.control.Tooltip("Información del chat"));
        infoButton.setOnAction(e -> toggleInfo.run());
        infoButton.setAccessibleText("Información del chat");
        Button more = Icons.iconButton(Icons.MORE, "Más opciones", null);
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
        list.setAccessibleText("Mensajes");
        list.setAccessibleHelp("Flechas para recorrer mensajes. R responder, E editar, Enter o Espacio abre las acciones, "
                + "1 a 6 reacciones rápidas, P reproducir un audio, T transcribir una nota de voz, " + Shortcuts.key("C") + " copiar, "
                + "Esc vuelve al campo de texto.");
        list.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::onTimelineKey);
        list.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> userScrolled());
        list.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case PAGE_UP, PAGE_DOWN, HOME, END, UP, DOWN -> userScrolled();
                default -> { }
            }
        });
        list.focusedProperty().addListener((o, a, focused) -> {
            if (focused && list.getSelectionModel().isEmpty() && !rows.isEmpty()) {
                list.getSelectionModel().select(rows.size() - 1);
            }
        });
    }

    private void redrawOnTranscriptProgress() {
        // Transcript progress redraws the visible rows; coalesced so a model download doesn't flood the FX thread.
        java.util.concurrent.atomic.AtomicBoolean refreshQueued = new java.util.concurrent.atomic.AtomicBoolean();
        Transcripts.get().addListener(() -> {
            if (refreshQueued.compareAndSet(false, true)) Platform.runLater(() -> {
                refreshQueued.set(false);
                list.refresh();
            });
        });
    }

    public Chat chat() { return chat; }
    public Composer composer() { return composer; }

    public void setInfoSelected(boolean selected) { infoButton.setSelected(selected); }

    public void open(Chat c) {
        chat = c;
        reachedStart = false;
        loadingOlder = false;
        lastMessageId = null;
        pinnedBottom = true;
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
            serverLink.setAccessibleText("Ver los canales de " + server.title());
            serverLink.setTooltip(new javafx.scene.control.Tooltip("Ver los canales de " + server.title()));
        }
        String sub = chat.network().displayName();
        String account = Accounts.forChat(svc, chat);
        if (account != null) sub += " · " + account;
        if (chat.group()) sub += " · " + chat.members().size() + " participantes";
        if (chat.muted()) sub += " · Silenciado";
        headerSubtitle.setText(sub);
        Nodes.style(favButton, "active", chat.favorite());
        Nodes.style(archiveButton, "active", chat.archived());
        TypingDots.show(typing, chat.typing().isEmpty() ? null : Formats.typing(chat.typing()));
        Nodes.show(typing, !chat.typing().isEmpty());
    }

    /** Re-reads the messages of the open chat. Keeps the viewport pinned to the bottom if it was there. */
    public void rebuild(boolean forceBottom) {
        if (chat == null) return;
        boolean wasAtBottom = forceBottom || pinnedBottom;
        Message selected = selectedMessage();
        List<Message> msgs = svc.messages(chat.id());
        Message newest = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1);
        // Your own new message always brings you down to it, wherever you were reading.
        boolean ownNew = newest != null && newest.outgoing() && lastMessageId != null && !newest.id().equals(lastMessageId);
        if (ownNew) {
            wasAtBottom = true;
            selected = null;
        }
        lastMessageId = newest == null ? null : newest.id();
        composer.refreshVoice(); // what the chat takes can arrive after it was opened
        index(msgs);
        List<ThreadRows.Row> next = ThreadRows.build(msgs, ZoneId.systemDefault());
        if (forceBottom) rows.setAll(next); else ThreadRows.update(rows, next);
        // Drawn from elsewhere than the messages: photos of who's in the chat, and "Hoy" on the day separators.
        int surroundings = java.util.Objects.hash(chat.id(), chat.members(), java.time.LocalDate.now());
        if (surroundings != drawnWith && !forceBottom) list.refresh();
        drawnWith = surroundings;
        if (selected != null && !forceBottom) select(selected);
        if (wasAtBottom && !(list.isFocused() && selected != null)) scrollToBottom();
    }

    /** The same messages after a change of appearance (text size, settings): they all look different. */
    public void redraw() {
        rebuild(false);
        list.refresh();
    }

    /** Asked while drawing each row, so it can't walk the whole chat for each of them. */
    private void index(List<Message> msgs) {
        loaded.clear();
        mediaAt.clear();
        for (Message m : msgs) {
            loaded.put(m.id(), m);
            if (isOwnMedia(m)) mediaAt.merge(mediaKey(m), 1, Integer::sum);
        }
    }

    private static boolean isOwnMedia(Message m) {
        return m.type() == MessageType.VIDEO || m.type() == MessageType.AUDIO;
    }

    private static String mediaKey(Message m) {
        return m.timestamp() + "|" + m.senderId();
    }

    private void scrollToBottom() {
        pinnedBottom = true;
        userScrollAt = 0; // our own scroll: don't let a recent wheel turn unpin it again
        jumpDown.setVisible(false);
        if (rows.isEmpty()) return;
        list.scrollTo(rows.size() - 1);
        // Cell heights are only known after layout; settle twice so the last row ends fully visible.
        Platform.runLater(() -> {
            list.scrollTo(rows.size() - 1);
            if (vbar != null) vbar.setValue(vbar.getMax());
            javafx.animation.PauseTransition settle = new javafx.animation.PauseTransition(javafx.util.Duration.millis(80));
            settle.setOnFinished(e -> {
                if (vbar != null && pinnedBottom) vbar.setValue(vbar.getMax());
            });
            settle.play();
        });
    }

    private void loadOlder() {
        if (loadingOlder || reachedStart || chat == null) return;
        loadingOlder = true;
        String id = chat.id();
        // Loaded because the chat is short (not because you scrolled to the top): keep showing the newest.
        boolean followBottom = pinnedBottom;
        Message firstBefore = rows.stream().filter(r -> r instanceof ThreadRows.MessageRow)
                .map(r -> ((ThreadRows.MessageRow) r).message()).findFirst().orElse(null);
        svc.loadOlder(id).whenComplete((more, err) -> Platform.runLater(() -> {
            loadingOlder = false;
            if (chat == null || !chat.id().equals(id)) return;
            if (err != null || Boolean.FALSE.equals(more)) reachedStart = true;
            List<Message> all = svc.messages(id);
            index(all);
            rows.setAll(ThreadRows.build(all, ZoneId.systemDefault()));
            if (followBottom && pinnedBottom) {
                scrollToBottom();
            } else if (firstBefore != null) {
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i) instanceof ThreadRows.MessageRow mr && mr.message() == firstBefore) {
                        list.scrollTo(Math.max(0, i - 1));
                        break;
                    }
                }
            }
        }));
    }

    private Message findLoaded(String id) {
        return id == null || chat == null ? null : loaded.get(id);
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
                setGraphic(messageNode(mr, getListView().getWidth()));
                setAccessibleText(describe(mr.message()));
                showActions(isSelected());
            }
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            showActions(selected);
        }

        private void showActions(boolean selected) {
            if (getGraphic() != null && getGraphic().getProperties().get("actions") instanceof Node actions) {
                actions.setVisible(selected || Settings.get().alwaysShowMessageActions()
                        || Boolean.TRUE.equals(getGraphic().getProperties().get("hover")));
            }
        }
    }

    /** What a screen reader says for a message row. */
    private String describe(Message m) {
        StringBuilder sb = new StringBuilder(m.outgoing() ? "Tú" : m.senderName());
        sb.append(", ").append(Formats.time(m.timestamp(), ZoneId.systemDefault())).append(": ");
        if (m.type() == MessageType.SYSTEM) return m.body();
        Message replied = findLoaded(m.replyToId());
        if (replied != null) sb.append("respondiendo a ").append(replied.outgoing() ? "ti" : replied.senderName()).append(". ");
        sb.append(switch (m.type()) {
            case IMAGE -> "Imagen" + (m.fileName() == null ? "" : " " + m.fileName());
            case STICKER -> "Sticker";
            default -> m.preview();
        });
        if (m.htmlText() != null && !m.body().isBlank()) sb.append(". ").append(m.htmlText());
        if (m.edited() && !m.deleted()) sb.append(", editado");
        if (m.type() == MessageType.AUDIO && Transcripts.get().state(m.mediaUrl()) instanceof Transcripts.Done d) {
            sb.append(". Transcripción: ").append(d.text().isEmpty() ? "sin voz" : d.text());
        }
        if (!m.reactions().isEmpty()) {
            sb.append(". Reacciones: ");
            m.reactions().forEach((emoji, users) -> sb.append(m.reactionLabel(emoji)).append(' ').append(users.size()).append(", "));
            sb.setLength(sb.length() - 2);
        }
        if (m.outgoing()) sb.append(switch (m.status()) {
            case SENDING -> ". Enviando";
            case SENT -> ". Enviado";
            case DELIVERED -> ". Entregado";
            case READ -> ". Leído";
            case FAILED -> ". No se pudo enviar";
        });
        return sb.toString();
    }

    private Message selectedMessage() {
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
            java.util.List<String> offered = chat.features().reactionsOffered(java.util.List.of(EmojiPicker.QUICK));
            if (n < 0 || n >= EmojiPicker.QUICK.length || !offered.contains(EmojiPicker.QUICK[n])) return;
            svc.toggleReaction(chat.id(), m.id(), EmojiPicker.QUICK[n]);
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

    private Node messageNode(ThreadRows.MessageRow row, double width) {
        Message m = row.message();
        if (m.type() == MessageType.SYSTEM) {
            Label l = new Label(m.body());
            l.getStyleClass().add("system-message");
            l.setWrapText(true);
            HBox box = new HBox(l);
            box.setAlignment(Pos.CENTER);
            return box;
        }
        boolean out = m.outgoing();
        boolean showSender = chat.group() && !out && row.first();
        double maxBubble = Math.max(200, Math.min(560, width * 0.68));

        VBox column = new VBox(2);
        column.setAlignment(out ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        if (showSender) {
            Label name = new Label(m.senderName());
            name.getStyleClass().add("sender-name");
            name.setStyle("-fx-text-fill: " + senderColor(m.senderId()) + ";");
            column.getChildren().add(name);
        }

        Node bubble = bubble(m, row, maxBubble);
        column.getChildren().add(bubble);
        if (!m.reactions().isEmpty()) column.getChildren().add(messageActions.reactions(m));

        HBox actions = messageActions.hoverActions(m, bubble);
        actions.setVisible(Settings.get().alwaysShowMessageActions());
        HBox line = new HBox(6);
        line.setFillHeight(false); // or the hover actions stretch to a tall message's whole height
        if (out) {
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            line.getChildren().addAll(grow, actions, column);
        } else {
            if (chat.group()) {
                if (row.last()) {
                    Avatar a = new Avatar(28);
                    Member mem = member(m.senderId());
                    a.set(m.senderName(), m.senderId(), mem == null ? null : mem.avatarUrl(), svc);
                    a.setAlignment(Pos.BOTTOM_CENTER);
                    VBox av = new VBox(a);
                    av.setAlignment(Pos.BOTTOM_CENTER);
                    line.getChildren().add(av);
                } else {
                    Region spacer = new Region();
                    spacer.setMinWidth(28);
                    line.getChildren().add(spacer);
                }
            }
            line.getChildren().addAll(column, actions);
        }
        line.setAlignment(Pos.BOTTOM_LEFT);
        line.getStyleClass().add("message-line");
        if (row.last()) line.getStyleClass().add("group-last");
        line.getProperties().put("actions", actions);
        line.setOnMouseEntered(e -> {
            line.getProperties().put("hover", true);
            actions.setVisible(true);
        });
        line.setOnMouseExited(e -> {
            line.getProperties().put("hover", false);
            boolean selected = line.getParent() instanceof RowCell rc && rc.isSelected();
            actions.setVisible(selected || Settings.get().alwaysShowMessageActions());
        });
        return line;
    }

    private Node bubble(Message m, ThreadRows.MessageRow row, double maxWidth) {
        boolean out = m.outgoing();
        VBox bubble = new VBox(4);
        bubble.getStyleClass().addAll("bubble", out ? "out" : "in");
        if (!Theme.highContrast()) {
            if (!row.first()) bubble.getStyleClass().add("cont-top");
            if (!row.last()) bubble.getStyleClass().add("cont-bottom");
        }
        bubble.setMaxWidth(maxWidth);
        bubble.setFillWidth(false);

        Message replied = findLoaded(m.replyToId());
        if (m.replyToId() != null) {
            VBox quote = new VBox(1);
            quote.getStyleClass().add("reply-quote");
            Label who = new Label(replied == null ? "Mensaje original" : replied.outgoing() ? "Tú" : replied.senderName());
            who.getStyleClass().add("reply-sender");
            Label text = new Label(replied == null ? "No cargado" : replied.preview());
            text.getStyleClass().add("reply-text");
            text.setMaxWidth(maxWidth - 30);
            quote.getChildren().addAll(who, text);
            if (replied != null) quote.setOnMouseClicked(e -> scrollTo(replied));
            bubble.getChildren().add(quote);
        }

        if (m.deleted()) {
            Label l = new Label("Mensaje eliminado");
            l.getStyleClass().add("deleted-text");
            bubble.getChildren().add(l);
        } else if (m.playsInline()) {
            bubble.getStyleClass().add(m.type() == MessageType.STICKER ? "sticker" : "media");
            bubble.getChildren().add(media.inlineVideoNode(m));
        } else if (m.type() == MessageType.IMAGE || m.type() == MessageType.STICKER) {
            bubble.getStyleClass().add(m.type() == MessageType.STICKER ? "sticker" : "media");
            bubble.getChildren().add(media.imageNode(m));
        } else if (m.type() == MessageType.AUDIO && m.mediaUrl() != null) {
            bubble.getChildren().add(media.audioNode(m, chat));
            bubble.getChildren().add(media.transcriptNode(m, maxWidth));
        } else if (m.isMedia()) {
            bubble.getChildren().add(media.fileNode(m));
        } else if ((m.type() == MessageType.TEXT) && RichText.isJumboEmoji(m.body()) && m.replyToId() == null) {
            bubble.getStyleClass().add("jumbo");
            Label l = new Label(m.body().strip());
            l.getStyleClass().add("jumbo-emoji");
            bubble.getChildren().add(l);
        } else {
            String body = m.type() == MessageType.EMOTE ? "* " + m.senderName() + " " + m.body() : m.body();
            double textWidth = maxWidth - 24;
            if (m.html() != null && m.type() != MessageType.EMOTE) {
                bubble.getChildren().add(HtmlView.of(m.html(), textWidth, media, openLink));
            } else {
                TextFlow flow = new TextFlow();
                flow.getChildren().addAll(RichText.nodes(body, openLink, textWidth));
                flow.getStyleClass().add("message-text");
                flow.setLineSpacing(Theme.lineSpacing());
                if (m.type() == MessageType.NOTICE) flow.getStyleClass().add("notice");
                flow.setMaxWidth(textWidth);
                bubble.getChildren().add(flow);
            }
            if (m.previewHtml() != null) bubble.getChildren().add(HtmlView.of(m.previewHtml(), textWidth, media, openLink));
            if (!followedByItsOwnMedia(m)) bubble.getChildren().add(media.linkPlayers(links(m), chat, textWidth));
        }

        HBox meta = new HBox(4);
        meta.getStyleClass().add("bubble-meta");
        meta.setAlignment(Pos.CENTER_RIGHT);
        if (m.edited() && !m.deleted()) {
            Label ed = new Label("editado");
            meta.getChildren().add(ed);
        }
        meta.getChildren().add(new Label(Formats.time(m.timestamp(), ZoneId.systemDefault())));
        if (out) meta.getChildren().add(statusIcon(m.status()));
        HBox metaRow = new HBox(meta);
        metaRow.setAlignment(Pos.CENTER_RIGHT);
        metaRow.setMaxWidth(Double.MAX_VALUE);
        bubble.getChildren().add(metaRow);
        bubble.setFillWidth(true);

        bubble.setOnContextMenuRequested(e -> messageActions.messageMenu(m).show(bubble, e.getScreenX(), e.getScreenY()));
        bubble.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !m.deleted()
                    && !chat.features().reactionsOffered(java.util.List.of("❤️")).isEmpty()) {
                svc.toggleReaction(chat.id(), m.id(), "❤️");
            }
        });
        return bubble;
    }

    /** The reply a sticker or GIF sent now answers: {@link Composer#takeReply}. */
    String takeReply() {
        return composer.takeReply();
    }

    private static java.util.List<String> links(Message m) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(m.html() != null ? HtmlView.links(m.html()) : RichText.addresses(m.body()));
        if (m.previewHtml() != null) out.addAll(HtmlView.links(m.previewHtml()));
        return java.util.List.copyOf(out);
    }

    /** A bridge sends the video it could get as a message of its own right after, at the same moment: no second player. */
    private boolean followedByItsOwnMedia(Message m) {
        return mediaAt.getOrDefault(mediaKey(m), 0) > (isOwnMedia(m) ? 1 : 0);
    }

    private Node statusIcon(MessageStatus s) {
        Node icon = switch (s) {
            case SENDING -> Icons.icon(Icons.CLOCK, 12);
            case SENT -> Icons.icon(Icons.CHECK, 13);
            case DELIVERED, READ -> Icons.icon(Icons.CHECK_DOUBLE, 14);
            case FAILED -> Icons.icon(Icons.ERROR, 13);
        };
        icon.getStyleClass().add("status-" + s.name().toLowerCase());
        return icon;
    }

    private void scrollTo(Message target) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ThreadRows.MessageRow mr && mr.message() == target) {
                // Stop following the bottom, or the next layout pulse (keepBottom) brings the list straight back down.
                pinnedBottom = false;
                userScrollAt = 0;
                list.getSelectionModel().select(i);
                list.scrollTo(Math.max(0, i - 2));
                jumpDown.setVisible(true);
                return;
            }
        }
    }

    private static final String[] SENDER_COLORS = {"#D6409F", "#E5484D", "#F76B15", "#46A758", "#12A594", "#0090FF", "#8E4EC6", "#3E63DD"};

    static String senderColor(String id) {
        return SENDER_COLORS[Math.floorMod(id.hashCode(), SENDER_COLORS.length)];
    }
}

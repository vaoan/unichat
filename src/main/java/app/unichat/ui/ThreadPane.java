package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;

import java.awt.Desktop;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Right side: chat header, message timeline, typing line and composer. */
public final class ThreadPane extends BorderPane {
    private static final double MAX_MEDIA = 320;

    private final ChatService svc;
    private final ObservableList<ThreadRows.Row> rows = FXCollections.observableArrayList();
    private final ListView<ThreadRows.Row> list = new ListView<>(rows);
    private final Composer composer;
    private final Label typing = new Label();
    private final Button jumpDown;
    private final Consumer<String> openLink;
    private final Runnable toggleInfo;
    private final ToggleButton infoButton = new ToggleButton();
    private final Avatar headerAvatar = new Avatar(34);
    private final Label headerTitle = new Label();
    private final Label headerSubtitle = new Label();
    private final Button favButton;
    private final Button archiveButton;
    private final VBox emptyState;
    private final BorderPane content = new BorderPane();
    private Chat chat;
    private boolean loadingOlder;
    private boolean reachedStart;
    private ScrollBar vbar;
    /** True while the viewport should follow new messages (user is at the bottom). */
    private boolean pinnedBottom = true;

    public ThreadPane(ChatService svc, Consumer<String> openLink, Runnable toggleInfo, Consumer<String> toast) {
        this.svc = svc;
        this.openLink = openLink;
        this.toggleInfo = toggleInfo;
        getStyleClass().add("thread-pane");
        composer = new Composer(svc, toast);

        // Header
        headerTitle.getStyleClass().add("thread-title");
        headerSubtitle.getStyleClass().add("thread-subtitle");
        VBox titles = new VBox(1, headerTitle, headerSubtitle);
        titles.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titles, Priority.ALWAYS);
        titles.setMinWidth(0);
        favButton = Composer.iconButton(Icons.STAR, "Favorito", () -> svc.setFavorite(chat.id(), !chat.favorite()));
        archiveButton = Composer.iconButton(Icons.ARCHIVE, "Archivar (" + (MainView.MAC ? "⌘E" : "Ctrl+E") + ")",
                () -> svc.setArchived(chat.id(), !chat.archived()));
        infoButton.setGraphic(Icons.icon(Icons.INFO, 18));
        infoButton.getStyleClass().add("icon-button");
        infoButton.setTooltip(new javafx.scene.control.Tooltip("Información del chat"));
        infoButton.setOnAction(e -> toggleInfo.run());
        infoButton.setAccessibleText("Información del chat");
        Button more = Composer.iconButton(Icons.MORE, "Más opciones", null);
        more.setOnAction(e -> ChatMenus.forChat(chat, svc, null).show(more, Side.BOTTOM, 0, 4));
        HBox header = new HBox(10, headerAvatar, titles, favButton, archiveButton, infoButton, more);
        header.getStyleClass().add("thread-header");
        header.setAlignment(Pos.CENTER_LEFT);
        titles.setOnMouseClicked(e -> toggleInfo.run());

        // Timeline
        list.getStyleClass().add("timeline");
        list.setCellFactory(v -> new RowCell());
        list.setAccessibleText("Mensajes");
        list.setAccessibleHelp("Flechas para recorrer mensajes. R responder, E editar, Enter o Espacio abre las acciones, "
                + "1 a 6 reacciones rápidas, " + (MainView.MAC ? "⌘" : "Ctrl+") + "C copiar, Esc vuelve al campo de texto.");
        list.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::onTimelineKey);
        list.focusedProperty().addListener((o, a, focused) -> {
            if (focused && list.getSelectionModel().isEmpty() && !rows.isEmpty()) {
                list.getSelectionModel().select(rows.size() - 1);
            }
        });
        jumpDown = Composer.iconButton(Icons.LOW, "Ir al final", this::scrollToBottom);
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
        String mod = MainView.MAC ? "⌘" : "Ctrl+";
        Label keys = new Label(mod + "K buscar   ·   Alt+↑/↓ cambiar de chat   ·   " + mod + "E archivar   ·   " + mod + ", ajustes");
        keys.getStyleClass().add("empty-hint");
        emptyState = new VBox(14, logo, hint, keys);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.getStyleClass().add("thread-empty");
        setCenter(emptyState);

        list.skinProperty().addListener((o, a, s) -> Platform.runLater(this::hookScrollBar));
        list.widthProperty().addListener((o, a, w) -> {
            if (pinnedBottom && chat != null) scrollToBottom();
        });
    }

    private void hookScrollBar() {
        for (Node n : list.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                vbar = sb;
                sb.valueProperty().addListener((o, a, v) -> {
                    pinnedBottom = atBottom();
                    jumpDown.setVisible(!pinnedBottom);
                    if (v.doubleValue() <= sb.getMin() + 0.0001 && chat != null) loadOlder();
                });
            }
        }
    }

    private boolean atBottom() {
        return vbar == null || !vbar.isVisible() || vbar.getValue() >= vbar.getMax() - 0.002;
    }

    public Chat chat() { return chat; }
    public Composer composer() { return composer; }
    public void setInfoSelected(boolean selected) { infoButton.setSelected(selected); }

    public void open(Chat c) {
        chat = c;
        reachedStart = false;
        loadingOlder = false;
        setCenter(c == null ? emptyState : content);
        composer.setChat(c == null ? null : c.id());
        if (c == null) return;
        refreshHeader();
        rebuild(true);
        composer.focus();
        if (svc.messages(c.id()).size() < 20) loadOlder();
    }

    public void refreshHeader() {
        if (chat == null) return;
        headerAvatar.set(chat.title(), chat.id(), chat.avatarUrl(), svc);
        headerAvatar.setNetwork(chat.network(), Settings.get().showNetworkBadges());
        headerTitle.setText(chat.title());
        String sub = chat.network().displayName();
        String account = Accounts.forChat(svc, chat);
        if (account != null) sub += " · " + account;
        if (chat.group()) sub += " · " + chat.members().size() + " participantes";
        if (chat.muted()) sub += " · Silenciado";
        headerSubtitle.setText(sub);
        favButton.getStyleClass().remove("active");
        if (chat.favorite()) favButton.getStyleClass().add("active");
        archiveButton.getStyleClass().remove("active");
        if (chat.archived()) archiveButton.getStyleClass().add("active");
        typing.setText(chat.typing().isEmpty() ? "" : Formats.typing(chat.typing()));
        typing.setVisible(!chat.typing().isEmpty());
        typing.setManaged(!chat.typing().isEmpty());
    }

    /** Re-reads the messages of the open chat. Keeps the viewport pinned to the bottom if it was there. */
    public void rebuild(boolean forceBottom) {
        if (chat == null) return;
        boolean wasAtBottom = forceBottom || pinnedBottom;
        Message selected = selectedMessage();
        List<Message> msgs = svc.messages(chat.id());
        rows.setAll(ThreadRows.build(msgs, ZoneId.systemDefault()));
        if (selected != null && !forceBottom) select(selected);
        if (wasAtBottom && !(list.isFocused() && selected != null)) scrollToBottom();
    }

    private void scrollToBottom() {
        pinnedBottom = true;
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
        Message firstBefore = rows.stream().filter(r -> r instanceof ThreadRows.MessageRow)
                .map(r -> ((ThreadRows.MessageRow) r).message()).findFirst().orElse(null);
        svc.loadOlder(id).whenComplete((more, err) -> Platform.runLater(() -> {
            loadingOlder = false;
            if (chat == null || !chat.id().equals(id)) return;
            if (err != null || Boolean.FALSE.equals(more)) reachedStart = true;
            rows.setAll(ThreadRows.build(svc.messages(id), ZoneId.systemDefault()));
            if (firstBefore != null) {
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
        if (id == null || chat == null) return null;
        for (ThreadRows.Row r : rows) if (r instanceof ThreadRows.MessageRow mr && mr.message().id().equals(id)) return mr.message();
        return null;
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
        if (m.edited() && !m.deleted()) sb.append(", editado");
        if (!m.reactions().isEmpty()) {
            sb.append(". Reacciones: ");
            m.reactions().forEach((emoji, users) -> sb.append(emoji).append(' ').append(users.size()).append(", "));
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
        } else if (code == javafx.scene.input.KeyCode.R && !e.isShortcutDown()) {
            composer.reply(m);
        } else if (code == javafx.scene.input.KeyCode.E && !e.isShortcutDown() && m.outgoing() && !m.isMedia() && !m.deleted()) {
            composer.edit(m);
        } else if (code == javafx.scene.input.KeyCode.C && e.isShortcutDown() && !m.isMedia()) {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(m.body());
            Clipboard.getSystemClipboard().setContent(cc);
        } else if ((code == javafx.scene.input.KeyCode.ENTER || code == javafx.scene.input.KeyCode.SPACE
                || code == javafx.scene.input.KeyCode.CONTEXT_MENU) && !m.deleted()) {
            var cell = list.lookupAll(".row-cell").stream()
                    .filter(n -> n instanceof RowCell rc && rc.isSelected()).findFirst().orElse(list);
            messageMenu(m).show(cell, Side.BOTTOM, 40, -10);
        } else if (code.isDigitKey() && !e.isShortcutDown() && !m.deleted()) {
            int n = code.getCode() - javafx.scene.input.KeyCode.DIGIT1.getCode();
            if (n < 0 || n >= EmojiPicker.QUICK.length) return;
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
        if (!m.reactions().isEmpty()) column.getChildren().add(reactions(m));

        HBox actions = hoverActions(m, bubble);
        actions.setVisible(Settings.get().alwaysShowMessageActions());
        HBox line = new HBox(6);
        line.setAlignment(Pos.CENTER_LEFT);
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
        } else if (m.type() == MessageType.IMAGE || m.type() == MessageType.STICKER) {
            bubble.getStyleClass().add(m.type() == MessageType.STICKER ? "sticker" : "media");
            bubble.getChildren().add(imageNode(m));
        } else if (m.isMedia()) {
            bubble.getChildren().add(fileNode(m));
        } else if ((m.type() == MessageType.TEXT) && RichText.isJumboEmoji(m.body()) && m.replyToId() == null) {
            bubble.getStyleClass().add("jumbo");
            Label l = new Label(m.body().strip());
            l.getStyleClass().add("jumbo-emoji");
            bubble.getChildren().add(l);
        } else {
            String body = m.type() == MessageType.EMOTE ? "* " + m.senderName() + " " + m.body() : m.body();
            TextFlow flow = new TextFlow();
            flow.getChildren().addAll(RichText.nodes(body, openLink));
            flow.getStyleClass().add("message-text");
            flow.setLineSpacing(Theme.lineSpacing());
            if (m.type() == MessageType.NOTICE) flow.getStyleClass().add("notice");
            flow.setMaxWidth(maxWidth - 24);
            bubble.getChildren().add(flow);
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

        bubble.setOnContextMenuRequested(e -> messageMenu(m).show(bubble, e.getScreenX(), e.getScreenY()));
        bubble.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !m.deleted()) {
                svc.toggleReaction(chat.id(), m.id(), "❤️");
            }
        });
        return bubble;
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

    private Node imageNode(Message m) {
        double w = m.width() > 0 ? m.width() : 240;
        double h = m.height() > 0 ? m.height() : 180;
        double scale = Math.min(1, Math.min(MAX_MEDIA / w, MAX_MEDIA / h));
        double dw = Math.max(80, w * scale), dh = Math.max(60, h * scale);
        boolean sticker = m.type() == MessageType.STICKER;
        if (sticker) {
            // Stickers are small and shown without a bubble, like on the phone.
            double s = Math.min(1, Math.min(180 / w, 180 / h));
            dw = Math.max(60, w * s);
            dh = Math.max(60, h * s);
        }
        StackPane box = new StackPane();
        box.getStyleClass().add(sticker ? "sticker-box" : "media-box");
        box.setPrefSize(dw, dh);
        box.setMinSize(dw, dh);
        box.setMaxSize(dw, dh);
        if (!sticker) {
            Rectangle clip = new Rectangle(dw, dh);
            clip.setArcWidth(24);
            clip.setArcHeight(24);
            box.setClip(clip);
        }
        ImageView view = new ImageView();
        view.setFitWidth(dw);
        view.setFitHeight(dh);
        view.setPreserveRatio(sticker);
        box.getChildren().add(view);
        if (m.mediaUrl() != null) {
            loadInto(view, m, (int) (dw * 2), (int) (dh * 2));
            // On release, not click: the first press selects the message and may scroll the list, and a press and
            // release over different spots is no "click" (it took a second click to open). Stickers open too: the
            // viewer plays animated WebP.
            box.setOnMouseReleased(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress() && getScene() != null) {
                    MediaViewer.show(getScene().getWindow(), svc.media(m.mediaUrl(), 0, 0), sticker ? "Sticker" : m.fileName());
                }
            });
            box.setCursor(javafx.scene.Cursor.HAND);
            box.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
            box.setAccessibleText((sticker ? "Sticker" : "Imagen") + (m.fileName() == null || sticker ? "" : " " + m.fileName())
                    + ", abrir en grande");
        }
        return box;
    }

    /** Stickers, GIFs and WebP may be animated: those load the original file, never a (static) thumbnail. */
    static boolean mayBeAnimated(Message m) {
        String mime = m.mimeType() == null ? "" : m.mimeType();
        return m.type() == MessageType.STICKER || mime.equals("image/gif") || mime.equals("image/webp");
    }

    private void loadInto(ImageView view, Message m, int pxW, int pxH) {
        if (!mayBeAnimated(m)) {
            svc.media(m.mediaUrl(), pxW, pxH).thenAccept(p ->
                    Platform.runLater(() -> view.setImage(new Image(p.toUri().toString(), pxW, pxH, true, true, true))));
            return;
        }
        // Placeholder: the server's static thumbnail shows at once; decoding a long animation takes seconds.
        svc.media(m.mediaUrl(), pxW, pxH).thenAccept(p -> Platform.runLater(() -> {
            if (view.getImage() == null) view.setImage(new Image(p.toUri().toString(), pxW, pxH, true, true, true));
        }));
        svc.media(m.mediaUrl(), 0, 0).thenAcceptAsync(p -> {
            try {
                if (AnimatedImage.isWebp(p)) {
                    AnimatedImage.Frames frames = AnimatedImage.decodeWebp(p, pxW, pxH);
                    Platform.runLater(() -> AnimatedImage.play(view, frames));
                } else if (Theme.reduceMotion()) {
                    // First frame only: JavaFX would animate a GIF on its own.
                    java.awt.image.BufferedImage first = javax.imageio.ImageIO.read(p.toFile());
                    if (first != null) {
                        Image img = javafx.embed.swing.SwingFXUtils.toFXImage(AnimatedImage.scale(first, pxW, pxH), null);
                        Platform.runLater(() -> view.setImage(img));
                    }
                } else {
                    // JavaFX animates GIFs natively; no background loading so the first frame isn't blank.
                    Image img = new Image(p.toUri().toString(), pxW, pxH, true, true, false);
                    Platform.runLater(() -> view.setImage(img));
                }
            } catch (Exception e) {
                System.err.println("Could not decode " + m.mediaUrl() + ": " + e);
            }
        }, AnimatedImage.DECODER);
    }

    private Node fileNode(Message m) {
        String icon = switch (m.type()) {
            case VIDEO, AUDIO -> Icons.PLAY;
            default -> Icons.FILE;
        };
        StackPane ic = new StackPane(Icons.icon(icon, 18));
        ic.getStyleClass().add("file-icon");
        Label name = new Label(m.fileName() == null ? m.body() : m.fileName());
        name.getStyleClass().add("file-name");
        Label size = new Label((m.type() == MessageType.AUDIO ? "Audio" : m.type() == MessageType.VIDEO ? "Video" : "Archivo")
                + (m.fileSize() > 0 ? " · " + Formats.fileSize(m.fileSize()) : ""));
        size.getStyleClass().add("file-size");
        VBox text = new VBox(1, name, size);
        HBox box = new HBox(10, ic, text, Icons.icon(Icons.DOWNLOAD, 16));
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("file-chip");
        box.setCursor(javafx.scene.Cursor.HAND);
        box.setOnMouseClicked(e -> openFile(m));
        box.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        box.setAccessibleText(size.getText() + " " + name.getText() + ", abrir");
        return box;
    }

    private void openFile(Message m) {
        if (m.mediaUrl() == null) return;
        svc.media(m.mediaUrl(), 0, 0).thenAccept(p -> {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(p.toFile());
            } catch (Exception ex) {
                openLink.accept(p.toUri().toString());
            }
        });
    }

    private Node reactions(Message m) {
        HBox box = new HBox(4);
        box.getStyleClass().add("reactions");
        for (Map.Entry<String, Set<String>> r : m.reactions().entrySet()) {
            Label emoji = new Label(r.getKey());
            emoji.getStyleClass().add("reaction-emoji");
            HBox content = new HBox(4, emoji);
            content.setAlignment(Pos.CENTER);
            if (r.getValue().size() > 1) {
                Label count = new Label(Integer.toString(r.getValue().size()));
                count.getStyleClass().add("reaction-count");
                content.getChildren().add(count);
            }
            Button b = new Button();
            b.setGraphic(content);
            b.getStyleClass().add("reaction-pill");
            if (r.getValue().contains(svc.myUserId())) b.getStyleClass().add("mine");
            b.setTooltip(new javafx.scene.control.Tooltip(String.join(", ", r.getValue().stream().map(this::nameOf).toList())));
            b.setOnAction(e -> svc.toggleReaction(chat.id(), m.id(), r.getKey()));
            b.setFocusTraversable(false);
            boolean mine = r.getValue().contains(svc.myUserId());
            b.setAccessibleText("Reacción " + r.getKey() + ", " + r.getValue().size()
                    + (mine ? ", incluida la tuya. Pulsa para quitarla" : ". Pulsa para reaccionar igual"));
            box.getChildren().add(b);
        }
        return box;
    }

    private String nameOf(String userId) {
        if (userId.equals(svc.myUserId())) return "Tú";
        Member mem = member(userId);
        return mem == null ? userId : mem.displayName();
    }

    private HBox hoverActions(Message m, Node bubble) {
        HBox box = new HBox(2);
        box.getStyleClass().add("hover-actions");
        box.setAlignment(Pos.CENTER);
        if (m.deleted()) return box;
        Button react = Composer.iconButton(Icons.EMOJI, "Reaccionar", null);
        react.setOnAction(e -> reactionPopup(m, react));
        Button reply = Composer.iconButton(Icons.REPLY, "Responder", () -> composer.reply(m));
        Button more = Composer.iconButton(Icons.MORE, "Más", null);
        more.setOnAction(e -> messageMenu(m).show(more, Side.BOTTOM, 0, 0));
        box.getChildren().addAll(react, reply, more);
        // Reachable with the timeline keys instead; tabbing through every message would be unusable.
        box.getChildren().forEach(n -> n.setFocusTraversable(false));
        return box;
    }

    private void reactionPopup(Message m, Node anchor) {
        Popup p = new Popup();
        p.setAutoHide(true);
        HBox row = new HBox(2);
        row.getStyleClass().addAll("popup-surface", "quick-reactions");
        row.getStylesheets().addAll(getScene().getStylesheets());
        row.getStyleClass().addAll(getScene().getRoot().getStyleClass());
        row.setStyle(getScene().getRoot().getStyle());
        for (String e : EmojiPicker.QUICK) {
            Button b = new Button(e);
            b.getStyleClass().add("emoji-button");
            b.setOnAction(ev -> {
                svc.toggleReaction(chat.id(), m.id(), e);
                p.hide();
            });
            row.getChildren().add(b);
        }
        Button plus = Composer.iconButton(Icons.PLUS, "Más emojis", null);
        plus.setOnAction(ev -> {
            p.hide();
            EmojiPicker.show(anchor, e -> svc.toggleReaction(chat.id(), m.id(), e));
        });
        row.getChildren().add(plus);
        p.getContent().add(row);
        var b = anchor.localToScreen(anchor.getBoundsInLocal());
        p.show(anchor, b.getMinX() - 120, b.getMinY() - 52);
    }

    private ContextMenu messageMenu(Message m) {
        ContextMenu menu = new ContextMenu();
        if (!m.deleted()) {
            menu.getItems().add(ChatMenus.item("Responder", () -> composer.reply(m)));
            if (!m.isMedia()) menu.getItems().add(ChatMenus.item("Copiar texto", () -> {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(m.body());
                Clipboard.getSystemClipboard().setContent(cc);
            }));
            if (m.mediaUrl() != null) menu.getItems().add(ChatMenus.item("Abrir archivo", () -> openFile(m)));
            if (m.outgoing() && !m.isMedia()) menu.getItems().add(ChatMenus.item("Editar", () -> composer.edit(m)));
            menu.getItems().add(new SeparatorMenuItem());
            for (String e : EmojiPicker.QUICK) {
                MenuItem i = ChatMenus.item("Reaccionar " + e, () -> svc.toggleReaction(chat.id(), m.id(), e));
                menu.getItems().add(i);
            }
            if (m.outgoing()) {
                menu.getItems().add(new SeparatorMenuItem());
                menu.getItems().add(ChatMenus.danger(ChatMenus.item("Eliminar para todos",
                        () -> svc.deleteMessage(chat.id(), m.id()))));
            }
        }
        return menu;
    }

    private void scrollTo(Message target) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ThreadRows.MessageRow mr && mr.message() == target) {
                list.scrollTo(Math.max(0, i - 2));
                return;
            }
        }
    }

    private static final String[] SENDER_COLORS = {"#D6409F", "#E5484D", "#F76B15", "#46A758", "#12A594", "#0090FF", "#8E4EC6", "#3E63DD"};

    static String senderColor(String id) {
        return SENDER_COLORS[Math.floorMod(id.hashCode(), SENDER_COLORS.length)];
    }
}

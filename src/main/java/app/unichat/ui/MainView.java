package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Message;
import app.unichat.service.ChatService;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Main window content: rail | chat list | thread (+ info panel), overlays and toasts. */
public final class MainView extends StackPane {
    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final ChatService svc;
    private final Stage stage;
    private final Notifier notifier;
    private final ChatListPane listPane;
    private final ThreadPane thread;
    private final InfoPane info;
    private final Rail rail;
    private final BorderPane threadArea = new BorderPane();
    private final StackPane overlay = new StackPane();
    private final Label toast = new Label();
    private final Label connection = new Label();
    private final Runnable onLogout;
    private final Consumer<String> openLink;
    private boolean chatsDirty;
    private String pendingOpenId;
    private long pendingOpenUntil;
    private boolean infoVisible;
    private SidePanel panel;
    /** Favourite and recent stickers/GIFs, shared by every chat. */
    private final app.unichat.model.MediaLibrary library =
            new app.unichat.model.MediaLibrary(app.unichat.settings.Settings.dataDir().resolve("media-library.json"));

    public app.unichat.model.MediaLibrary library() { return library; }
    private final java.util.Set<String> dirtyThreads = new java.util.HashSet<>();

    private final ChatService.Listener listener = new ChatService.Listener() {
        @Override public void chatsChanged() { scheduleChats(); }
        @Override public void messagesChanged(String chatId) { scheduleThread(chatId); }
        @Override public void accountsChanged() {
            scheduleChats();
            Platform.runLater(() -> {
                if (SettingsWindow.accountsPage() != null) SettingsWindow.accountsPage().refresh();
            });
        }
        @Override public void connectionChanged(ConnectionState s) { Platform.runLater(() -> showConnection(s)); }
        @Override public void incomingMessage(Chat chat, Message m) { Platform.runLater(() -> onIncoming(chat, m)); }
        @Override public void reminderDue(Chat chat) {
            Platform.runLater(() -> {
                notifier.reminder(chat);
                if (app.unichat.settings.Settings.get().speakMessages()) Speaker.say("Recordatorio: " + chat.title());
            });
        }
    };

    public MainView(ChatService svc, Stage stage, Notifier notifier, Runnable onLogout, Consumer<String> openLink) {
        this.svc = svc;
        this.stage = stage;
        this.notifier = notifier;
        this.onLogout = onLogout;
        this.openLink = openLink;
        getStyleClass().add("main-root");

        listPane = new ChatListPane(svc, this::openChat);
        listPane.setOnNewChat(this::showNewChat);
        listPane.setToast(this::toast);
        thread = new ThreadPane(svc, openLink, this::toggleInfo, this::toast);
        info = new InfoPane(svc, this::toggleInfo, this::toast);
        panel = new SidePanel(info, text -> thread.composer().insert(text));
        thread.setLibrary(library);
        StickerGrid stickers = new StickerGrid(svc, library, thread::chat, this::toast);
        panel.setContent(SidePanel.Tab.STICKERS, stickers, stickers::refresh);
        GifGrid gifs = new GifGrid(svc, library, thread::chat, this::toast);
        panel.setContent(SidePanel.Tab.GIFS, gifs, gifs::refresh);
        thread.composer().setOnPanel(this::togglePanel);
        rail = new Rail(svc, listPane, this::showAccounts, this::addAccount, this::showSettings);

        threadArea.setCenter(thread);
        SplitPane split = new SplitPane(listPane, threadArea);
        split.setDividerPositions(0.3);
        SplitPane.setResizableWithParent(listPane, false);
        listPane.setMinWidth(260);
        listPane.setMaxWidth(480);
        listPane.setPrefWidth(340);
        split.getStyleClass().add("main-split");

        connection.getStyleClass().add("connection-banner");
        connection.setMaxWidth(Double.MAX_VALUE);
        connection.setVisible(false);
        connection.setManaged(false);

        BorderPane main = new BorderPane(split);
        main.setLeft(rail);
        main.setTop(connection);

        overlay.getStyleClass().add("overlay");
        overlay.setVisible(false);
        overlay.setOnMouseClicked(e -> { if (e.getTarget() == overlay) hideOverlay(); });

        toast.getStyleClass().add("toast");
        toast.setVisible(false);
        toast.setMouseTransparent(true);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        toast.setTranslateY(-90);

        getChildren().addAll(main, overlay, toast);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        stage.focusedProperty().addListener((o, a, focused) -> {
            if (focused && thread.chat() != null) svc.markRead(thread.chat().id());
        });

        svc.addListener(listener);
        showConnection(svc.connectionState());
        refreshAll();
    }

    public void dispose() {
        svc.removeListener(listener);
    }

    // ------------------------------------------------------------------ updates (coalesced)

    private void scheduleChats() {
        synchronized (dirtyThreads) {
            if (chatsDirty) return;
            chatsDirty = true;
        }
        Platform.runLater(() -> {
            synchronized (dirtyThreads) { chatsDirty = false; }
            refreshAll();
        });
    }

    private void scheduleThread(String chatId) {
        synchronized (dirtyThreads) {
            if (!dirtyThreads.add(chatId)) return;
        }
        Platform.runLater(() -> {
            synchronized (dirtyThreads) { dirtyThreads.remove(chatId); }
            Chat open = thread.chat();
            if (open != null && open.id().equals(chatId)) {
                thread.rebuild(false);
                thread.refreshHeader();
                if (stage.isFocused() && open.isUnread()) svc.markRead(chatId);
                if (infoVisible) panel.refresh();
            }
        });
    }

    private boolean avatarsPreloaded;

    /** Warms the photo cache with the most recent chats, so even the first open of a chat shows no initials. */
    private void preloadAvatars() {
        if (avatarsPreloaded) return;
        java.util.List<Chat> recent = svc.chats().stream().filter(c -> !c.bridgeBot() && c.avatarUrl() != null)
                .sorted(ChatFilter.ORDER).limit(150).toList();
        if (recent.isEmpty()) return;
        avatarsPreloaded = true;
        for (Chat c : recent) ImageCache.load(c.avatarUrl(), svc);
    }

    private void refreshAll() {
        preloadAvatars();
        if (pendingOpenId != null) {
            Chat pending = svc.chat(pendingOpenId);
            if (pending != null) {
                pendingOpenId = null;
                openChat(pending);
            } else if (System.currentTimeMillis() > pendingOpenUntil) {
                pendingOpenId = null;
                toast("El chat tarda en aparecer. Prueba de nuevo en unos segundos.");
            }
        }
        listPane.refresh();
        rail.refresh();
        Chat open = thread.chat();
        if (open != null) {
            Chat current = svc.chat(open.id());
            if (current == null) {
                thread.open(null);
                listPane.setSelected(null);
                setInfo(false);
            } else {
                thread.refreshHeader();
                if (infoVisible) panel.refresh();
            }
        }
        long unread = svc.chats().stream().filter(ChatFilter::inboxUnread).count();
        notifier.setUnread((int) unread);
        stage.setTitle(unread > 0 ? "Unichat (" + unread + ")" : "Unichat");
    }

    private void onIncoming(Chat chat, Message m) {
        boolean viewing = stage.isFocused() && stage.isShowing() && thread.chat() != null && thread.chat().id().equals(chat.id());
        var settings = app.unichat.settings.Settings.get();
        if (NotificationPolicy.shouldNotify(chat.archived(), settings.notifyArchived(), viewing)) notifier.notify(chat, m);
        if (settings.speakMessages() && (!chat.archived() || settings.notifyArchived())) Speaker.say(spoken(chat, m));
    }

    /** "Mensaje de Laura en Familia, WhatsApp: hola" (only the sender when previews are hidden). */
    static String spoken(Chat chat, Message m) {
        String who = chat.group() ? m.senderName() + " en " + chat.title() : chat.title();
        String head = "Mensaje de " + who + ", " + chat.network().displayName();
        return app.unichat.settings.Settings.get().notificationPreview() ? head + ": " + m.preview() : head;
    }

    private void showConnection(ConnectionState s) {
        String text = switch (s) {
            case CONNECTING -> "Conectando…";
            case SYNCING -> "Sincronizando chats…";
            case OFFLINE -> "Sin conexión con el servidor. Reintentando…";
            case LOGGED_OUT -> "La sesión expiró. Vuelve a iniciar sesión.";
            case ONLINE -> null;
        };
        connection.setText(text);
        connection.getStyleClass().removeAll("error");
        if (s == ConnectionState.OFFLINE || s == ConnectionState.LOGGED_OUT) connection.getStyleClass().add("error");
        connection.setVisible(text != null);
        connection.setManaged(text != null);
        if (s == ConnectionState.LOGGED_OUT) onLogout.run();
    }

    // ------------------------------------------------------------------ navigation

    public void openChat(Chat c) {
        openChat(c, false);
    }

    /** {@code keepFilters}: open the chat even if the current filters hide it in the list (notification clicks). */
    private void openChat(Chat c, boolean keepFilters) {
        if (c == null) return;
        hideOverlay();
        if (!keepFilters && c.archived() && listPane.filterProperty().get().view() != ChatFilter.View.ARCHIVE && !listPane.filterProperty().get().searching()) {
            listPane.filterProperty().set(listPane.filterProperty().get().withView(ChatFilter.View.ARCHIVE));
        }
        listPane.setSelected(c.id());
        thread.open(c);
        if (infoVisible) panel.show(c, panel.current());
        svc.markRead(c.id());
        listPane.refresh();
    }

    /** Shows a server's channels over the chat list (snapshot diagnostics). */
    public void showSpace(String id) {
        listPane.showSpace(id);
    }

    public void openChatId(String id) {
        if (id == null) return;
        Chat c = svc.chat(id);
        if (c != null) {
            pendingOpenId = null;
            openChat(c, true);
            return;
        }
        // The room (e.g. a just-created bridge-bot chat) hasn't arrived through sync yet: open it when it does.
        pendingOpenId = id;
        pendingOpenUntil = System.currentTimeMillis() + 20_000;
        toast("Abriendo el chat…");
    }

    /** ⓘ button / ⌘I: the side panel on its Perfil tab. */
    public void toggleInfo() {
        togglePanel(SidePanel.Tab.PROFILE);
    }

    /** Opens the side panel on {@code t}; if it's already showing {@code t}, closes it (same button toggles). */
    public void togglePanel(SidePanel.Tab t) {
        if (infoVisible && panel.current() == t) setInfo(false);
        else showPanel(t);
    }

    public void showPanel(SidePanel.Tab t) {
        if (thread.chat() == null) return;
        infoVisible = true;
        threadArea.setRight(panel);
        thread.setInfoSelected(t == SidePanel.Tab.PROFILE);
        panel.show(thread.chat(), t);
    }

    private void setInfo(boolean show) {
        if (show) {
            showPanel(panel.current());
            return;
        }
        infoVisible = false;
        threadArea.setRight(null);
        thread.setInfoSelected(false);
    }

    public void toast(String text) {
        toast.setText(text);
        toast.setAccessibleText(text);
        toast.setVisible(true);
        toast.setOpacity(1);
        FadeTransition out = new FadeTransition(Duration.millis(Theme.reduceMotion() ? 1 : 400), toast);
        out.setToValue(0);
        SequentialTransition seq = new SequentialTransition(new PauseTransition(Duration.seconds(Theme.reduceMotion() ? 5 : 3)), out);
        seq.setOnFinished(e -> toast.setVisible(false));
        seq.play();
    }

    private void showOverlay(Node card) {
        if (card instanceof javafx.scene.layout.Region r) r.maxHeightProperty().bind(overlay.heightProperty().subtract(130));
        overlay.getChildren().setAll(card);
        StackPane.setAlignment(card, Pos.TOP_CENTER);
        card.setTranslateY(90);
        overlay.setVisible(true);
    }

    private void hideOverlay() {
        overlay.setVisible(false);
        overlay.getChildren().clear();
        if (thread.chat() != null) thread.composer().focus();
    }

    /** Nuevo chat (⌘N / Ctrl+N): any contact of a network, starting with the one the list shows. */
    public void showNewChat() {
        NewChatPane pane = new NewChatPane(svc, listPane.filterProperty().get().network(), this::openChatId, this::hideOverlay, this::toast);
        showOverlay(pane);
        Platform.runLater(pane::focus);
    }

    public void showCommandBar() {
        String m = MAC ? "⌘" : "Ctrl+";
        CommandBar bar = new CommandBar(svc, List.of(
                new CommandBar.Command("Ir a la bandeja", "", () -> setView(ChatFilter.View.INBOX, false)),
                new CommandBar.Command("Ver no leídos", m + "2", () -> setView(ChatFilter.View.INBOX, true)),
                new CommandBar.Command("Ver favoritos", m + "3", () -> setView(ChatFilter.View.FAVORITES, false)),
                new CommandBar.Command("Ver prioridad baja", m + "4", () -> setView(ChatFilter.View.LOW_PRIORITY, false)),
                new CommandBar.Command("Ver archivados", "", () -> setView(ChatFilter.View.ARCHIVE, false)),
                new CommandBar.Command("Nuevo chat", m + "N", this::showNewChat),
                new CommandBar.Command("Cuentas", "", this::showAccounts),
                new CommandBar.Command("Agregar cuenta", "", () -> showAccounts().showPicker()),
                new CommandBar.Command("Ajustes", m + ",", this::showSettings),
                new CommandBar.Command("Cerrar sesión", "", onLogout)
        ), this::openChat, this::hideOverlay);
        showOverlay(bar);
        Platform.runLater(bar::focus);
    }

    private void setView(ChatFilter.View v, boolean unread) {
        listPane.filterProperty().set(listPane.filterProperty().get().withView(v).withUnreadOnly(unread).withQuery(""));
    }

    /** Opens Ajustes → Cuentas (list of accounts, add account, login wizard). */
    public AccountsPane showAccounts() {
        showSettings();
        SettingsWindow.select("Cuentas");
        return SettingsWindow.accountsPage();
    }

    /** Filters the inbox to one network (all its accounts). */
    public void showNetwork(app.unichat.model.Network n) {
        listPane.filterProperty().set(listPane.filterProperty().get().switchTo(n, null));
    }

    /** Starts the login of one more account on {@code n} and opens its bridge-bot chat. */
    public void addAccount(app.unichat.model.Network n) {
        showAccounts().startLogin(n);
    }

    public void showSettings() {
        SettingsWindow.show(stage, svc, () -> {
            Theme.applyAll();
            refreshAll();
            thread.rebuild(false);
        }, onLogout, this::openChatId, this::toast);
    }

    /** Text size: delta 0 resets to 100 %. */
    private void zoom(double delta) {
        var s = app.unichat.settings.Settings.get();
        s.setFontScale(delta == 0 ? 1.0 : s.fontScale() + delta);
        Theme.applyAll();
        listPane.refresh();
        thread.rebuild(false);
        toast("Tamaño de texto: " + Math.round(s.fontScale() * 100) + " %");
    }

    /** F6 / Shift+F6: rail → chat list → messages → message input (→ side panel when open). */
    private void cycleFocus(int dir) {
        Node owner = getScene() == null ? null : getScene().getFocusOwner();
        int current = -1;
        for (Node n = owner; n != null; n = n.getParent()) {
            if (n == rail) current = 0;
            else if (n == listPane) current = 1;
            else if (n instanceof javafx.scene.control.ListView<?> lv && lv.getStyleClass().contains("timeline")) current = 2;
            else if (n == thread.composer()) current = 3;
            else if (n == panel) current = 4;
            if (current >= 0) break;
        }
        int zones = thread.chat() == null ? 2 : infoVisible ? 5 : 4;
        int next = Math.floorMod((current < 0 ? (dir > 0 ? -1 : 0) : current) + dir, zones);
        switch (next) {
            case 0 -> { Node b = rail.lookup(".rail-button"); if (b != null) b.requestFocus(); }
            case 1 -> listPane.focusList();
            case 2 -> thread.focusTimeline();
            case 4 -> { Node b = panel.lookup(".chip"); if (b != null) b.requestFocus(); }
            default -> thread.composer().focus();
        }
    }

    private void onKey(KeyEvent e) {
        boolean mod = e.isShortcutDown();
        Chat c = thread.chat();
        if (mod && e.getCode() == KeyCode.K) {
            showCommandBar();
        } else if (mod && e.getCode() == KeyCode.N) {
            showNewChat();
        } else if (e.getCode() == KeyCode.ESCAPE && overlay.isVisible()) {
            hideOverlay();
        } else if (mod && e.getCode() == KeyCode.COMMA) {
            showSettings();
        } else if (mod && e.getCode() == KeyCode.F) {
            listPane.focusSearch();
        } else if (e.isAltDown() && (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN)) {
            listPane.move(e.getCode() == KeyCode.DOWN ? 1 : -1, e.isShiftDown());
        } else if (mod && e.getCode() == KeyCode.E && c != null) {
            svc.setArchived(c.id(), !c.archived());
            toast(c.archived() ? "Chat desarchivado" : "Chat archivado");
        } else if (mod && e.isShiftDown() && e.getCode() == KeyCode.U && c != null) {
            if (c.isUnread()) svc.markRead(c.id()); else svc.setMarkedUnread(c.id(), true);
        } else if (mod && e.getCode() == KeyCode.I && c != null) {
            toggleInfo();
        } else if (e.getCode() == KeyCode.F6) {
            cycleFocus(e.isShiftDown() ? -1 : 1);
        } else if (mod && (e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.PLUS || e.getCode() == KeyCode.ADD)) {
            zoom(0.1);
        } else if (mod && (e.getCode() == KeyCode.MINUS || e.getCode() == KeyCode.SUBTRACT)) {
            zoom(-0.1);
        } else if (mod && (e.getCode() == KeyCode.DIGIT0 || e.getCode() == KeyCode.NUMPAD0)) {
            zoom(0);
        } else if (mod && e.getCode().isDigitKey()) {
            switch (e.getCode()) {
                case DIGIT1 -> setView(ChatFilter.View.INBOX, false);
                case DIGIT2 -> setView(ChatFilter.View.INBOX, true);
                case DIGIT3 -> setView(ChatFilter.View.FAVORITES, false);
                case DIGIT4 -> setView(ChatFilter.View.LOW_PRIORITY, false);
                default -> { return; }
            }
        } else {
            return;
        }
        e.consume();
    }
}

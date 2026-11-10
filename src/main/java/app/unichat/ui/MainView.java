package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.support.Os;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;

/** Main window content: rail | chat list | thread (+ info panel), overlays and toasts. */
public final class MainView extends StackPane {
    public static final boolean MAC = Os.mac();

    private final ChatService svc;
    private final Stage stage;
    private final Notifier notifier;
    private final ChatListPane listPane;
    private final ThreadPane thread;
    private final InfoPane info;
    private final Rail rail;
    private final FolderDialogs folderDialogs;
    private final BorderPane threadArea = new BorderPane();
    private final StackPane overlay = new StackPane();
    private Runnable protectPhoto = () -> { };
    private final Label toast = new Label();
    private final Label connection = new Label();
    private ConnectionState state = ConnectionState.CONNECTING;
    /** What the top bar says once the server is connected, while networks reconnect (null: nothing). */
    private String syncingNetworks;
    private final AccountAlertBar accountAlerts = new AccountAlertBar(this::addAccount);
    /** A newer Unichat, above the account alerts. */
    private final UpdateBar updateBar;
    private final Runnable onLogout;
    /** The one audio playing, whichever chat is open. */
    private final AudioPlayback audio;
    /** The batch going up, whichever chat is open. */
    private final UploadBar uploads;
    private final Consumer<String> openLink;
    private String pendingOpenId;
    private long pendingOpenUntil;
    private boolean infoVisible;
    private SidePanel panel;
    /** Favourite and recent stickers/GIFs, shared by every chat. */
    private final MediaLibrary library =
            new MediaLibrary(Settings.dataDir().resolve("media-library.json"));

    public MediaLibrary library() { return library; }

    /** The chat open on the right, or null. */
    public String currentChatId() { return thread.chat() == null ? null : thread.chat().id(); }
    /**
     * A busy account (big Discord servers) changes chats many times a second: rebuild at most every REFRESH_GAP_MS, so the UI
     * thread isn't redrawing the whole inbox nonstop. Same for a chat where many people write: a few updates a second at most.
     */
    private static final long REFRESH_GAP_MS = 250;
    private final Throttle chatsThrottle = new Throttle(REFRESH_GAP_MS);
    private final Throttle threadThrottle = new Throttle(REFRESH_GAP_MS);

    private final ChatService.Listener listener = new ChatService.Listener() {
        @Override public void chatsChanged() { scheduleChats(); }
        @Override public void messagesChanged(String chatId) { scheduleThread(chatId); }
        @Override public void accountsChanged() {
            scheduleChats();
            Platform.runLater(() -> {
                if (SettingsWindow.accountsPage() != null) SettingsWindow.accountsPage().refresh();
                if (stage.isShowing()) showAccountAlerts();
            });
        }
        @Override public void connectionChanged(ConnectionState s) { Platform.runLater(() -> showConnection(s)); }
        @Override public void incomingMessage(Chat chat, Message m) { Platform.runLater(() -> onIncoming(chat, m)); }
        @Override public void reminderDue(Chat chat) {
            Platform.runLater(() -> {
                notifier.reminder(chat);
                if (Settings.get().speakMessages()) Speaker.say(I18n.t("main.reminderSpoken", chat.title()));
            });
        }
    };

    public MainView(ChatService svc, Stage stage, Notifier notifier, Runnable onLogout, Consumer<String> openLink) {
        this.svc = svc;
        this.stage = stage;
        this.notifier = notifier;
        this.audio = new AudioPlayback(svc, this::toast);
        this.onLogout = () -> {
            audio.stop();
            onLogout.run();
        };
        this.openLink = openLink;
        getStyleClass().add("main-root");

        folderDialogs = new FolderDialogs(svc, this::showOverlay, this::hideOverlay);
        listPane = new ChatListPane(svc, this::openChat, folderDialogs);
        listPane.setOnNewChat(this::showNewChat);
        updateBar = new UpdateBar(app.unichat.update.UpdateChecker.get(), openLink);
        listPane.setFooter(updateBar);
        listPane.setFooter(accountAlerts);
        listPane.setToast(this::toast);
        thread = new ThreadPane(svc, openLink, this::toggleInfo, this::toast, audio, folderDialogs, library);
        info = new InfoPane(svc, this::toggleInfo, this::toast);
        panel = new SidePanel(info);
        thread.setOnShowServer(id -> {
            listPane.showSpace(id);
            listPane.setSelected(thread.chat() == null ? null : thread.chat().id());
        });
        StickerGrid stickers = new StickerGrid(svc, library, thread::chat, thread::takeReply, this::toast);
        MediaItems.keepCached(svc, library);
        library.addListener(() -> MediaItems.keepCached(svc, library));
        panel.setContent(SidePanel.Tab.STICKERS, stickers, stickers::refresh);
        GifGrid gifs = new GifGrid(svc, library, thread::chat, thread::takeReply, this::toast);
        panel.setContent(SidePanel.Tab.GIFS, gifs, gifs::refresh);
        EmojiGrid.Tab emojis = EmojiGrid.panel(library, text -> thread.composer().insert(text), this::toast);
        panel.setContent(SidePanel.Tab.EMOJIS, emojis.node(), emojis.refresh());
        FavoritesPane favorites = new FavoritesPane(library, stickers, gifs, text -> thread.composer().insert(text), this::toast);
        panel.setContent(SidePanel.Tab.FAVORITES, favorites, favorites::refresh);
        thread.composer().setOnPanel(this::togglePanel);
        offerProtectedPhotos(svc);
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
        Nodes.show(connection, false);

        BorderPane main = new BorderPane(split);
        main.setLeft(rail);
        // On disk (this computer's paths, so it stays here like session.properties): a batch survives a restart.
        java.nio.file.Path pendingUploads = Settings.dataDir().resolve(svc instanceof app.unichat.service.MockChatService ? "uploads-demo.json" : "uploads.json");
        uploads = new UploadBar(app.unichat.service.UploadQueue.of(svc, pendingUploads), svc, this::openChatId);
        main.setTop(new javafx.scene.layout.VBox(connection, new AudioBar(audio, c -> openChat(c, true)), uploads,
                new CloakBar(app.unichat.cloak.CloakJob.get())));

        overlay.getStyleClass().add("overlay");
        overlay.setVisible(false);
        overlay.setOnMouseClicked(e -> { if (e.getTarget() == overlay) hideOverlay(); });

        toast.getStyleClass().add("toast");
        toast.setWrapText(true);
        toast.setMaxWidth(560);
        toast.setVisible(false);
        toast.setMouseTransparent(true);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        toast.setTranslateY(-90);
        // Above the message box, whatever its height: at a fixed place it covered a box grown to several lines.
        thread.composer().layoutBoundsProperty().addListener((o, a, b) -> { if (toast.isVisible()) placeToast(); });

        getChildren().addAll(main, overlay, toast);
        WindowShortcuts shortcuts = new WindowShortcuts(this, svc, rail, listPane, thread, panel);
        addEventFilter(KeyEvent.KEY_PRESSED, shortcuts);
        addEventHandler(KeyEvent.KEY_PRESSED, shortcuts::typeIntoComposer); // a handler: after anything that used the key
        addEventFilter(KeyEvent.KEY_RELEASED, e -> thread.composer().aiKeyReleased(e.getCode()));
        stage.showingProperty().addListener((o, a, showing) -> {
            if (showing) Platform.runLater(this::catchUp);
        });
        stage.focusedProperty().addListener((o, a, focused) -> {
            if (focused && thread.chat() != null) svc.markRead(thread.chat().id());
        });

        svc.addListener(listener);
        showConnection(svc.connectionState());
        showAccountAlerts();
        refreshAll();
        String warning = svc.preferencesWarning();
        if (warning != null) toast(warning);
        svc.onSaveFailure(text -> Platform.runLater(() -> toast(text)));
    }

    public void dispose() {
        audio.stop();
        uploads.dispose();
        updateBar.dispose();
        svc.removeListener(listener);
        svc.onSaveFailure(null);
    }

    // ------------------------------------------------------------------ updates (coalesced)

    private void scheduleChats() {
        chatsThrottle.schedule(chatsThrottle, () -> {
            chatsThrottle.ran();
            refreshAll();
        });
    }

    private void scheduleThread(String chatId) {
        threadThrottle.schedule(chatId, () -> refreshThread(chatId));
    }

    private void refreshThread(String chatId) {
        Chat open = thread.chat();
        if (open != null && open.id().equals(chatId)) {
            if (!stage.isShowing()) {
                threadStale = true;
                return;
            }
            threadThrottle.ran();
            thread.rebuild(false);
            thread.refreshHeader();
            if (stage.isFocused() && open.isUnread()) svc.markRead(chatId);
            if (infoVisible) panel.refresh();
        }
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

    /**
     * With the window closed to the menu bar nothing is drawn, and JavaFX only forgets the nodes that changed when it draws: all
     * that's built meanwhile stays in memory, even what's thrown away (1 GB in a night, a real heap dump). So it waits for the window.
     */
    private boolean staleWhileHidden;
    private boolean threadStale;

    private void catchUp() {
        if (!stage.isShowing()) return;
        if (staleWhileHidden) {
            staleWhileHidden = false;
            refreshAll();
            showAccountAlerts();
        }
        if (threadStale) {
            threadStale = false;
            thread.rebuild(false);
        }
    }

    private void refreshAll() {
        if (!stage.isShowing()) {
            staleWhileHidden = true;
            showUnread();
            return;
        }
        preloadAvatars();
        if (pendingOpenId != null) {
            Chat pending = svc.chat(pendingOpenId);
            if (pending != null) {
                pendingOpenId = null;
                openChat(pending);
            } else if (System.currentTimeMillis() > pendingOpenUntil) {
                pendingOpenId = null;
                toast(I18n.t("main.chatSlow"));
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
        showUnread();
    }

    private void showUnread() {
        long unread = svc.chats().stream().filter(ChatFilter::inboxUnread).count();
        notifier.setUnread((int) unread);
        stage.setTitle(unread > 0 ? "Unichat (" + unread + ")" : "Unichat");
    }

    private void onIncoming(Chat chat, Message m) {
        boolean viewing = stage.isFocused() && stage.isShowing() && thread.chat() != null && thread.chat().id().equals(chat.id());
        var settings = Settings.get();
        if (NotificationPolicy.shouldNotify(chat.archived(), settings.notifyArchived(), viewing)) notifier.notify(chat, m);
        if (settings.speakMessages() && (!chat.archived() || settings.notifyArchived())) Speaker.say(A11yText.spoken(chat, m, settings.notificationPreview()));
    }

    private void showAccountAlerts() {
        java.util.Set<Network> inUse = java.util.EnumSet.noneOf(Network.class);
        for (Chat c : svc.chats()) if (!c.bridgeBot() && !c.space()) inUse.add(c.network());
        List<AccountAlerts.Alert> alerts = AccountAlerts.of(svc.accounts(), inUse);
        // Reconnecting is "Sincronizando…" at the top, with the connection; the foot keeps what needs a hand.
        accountAlerts.show(alerts.stream().filter(a -> !a.syncing()).toList());
        syncingNetworks = AccountAlerts.syncingLine(alerts);
        showConnection(state);
    }

    /** Chats are up to date only when both the server and every network are: until then the top bar says what's behind. */
    private void showConnection(ConnectionState s) {
        state = s;
        String text = s == ConnectionState.ONLINE ? syncingNetworks : switch (s) {
            case CONNECTING -> I18n.t("main.connecting");
            case SYNCING -> I18n.t("main.syncing");
            case OFFLINE -> I18n.t("main.offline");
            case LOGGED_OUT -> I18n.t("main.loggedOut");
            case ONLINE -> null;
        };
        connection.setText(text);
        connection.getStyleClass().removeAll("error");
        if (s == ConnectionState.OFFLINE || s == ConnectionState.LOGGED_OUT) connection.getStyleClass().add("error");
        Nodes.show(connection, text != null);
        if (s == ConnectionState.LOGGED_OUT) onLogout.run();
    }

    // ------------------------------------------------------------------ navigation

    AudioPlayback audio() { return audio; }

    ThreadPane thread() { return thread; }

    ChatListPane list() { return listPane; }

    public void openChat(Chat c) {
        openChat(c, false);
    }

    /** {@code keepFilters}: open the chat even if the current filters hide it in the list (notification clicks). */
    private void openChat(Chat c, boolean keepFilters) {
        if (c == null) return;
        hideOverlay();
        if (!keepFilters && c.archived() && listPane.filterProperty().get().view() != ChatFilter.View.ARCHIVE && !listPane.filterProperty().get().searching()
                && !listPane.foldersOpen()) { // a folder shows its archived chats: the filter it covers stays put
            listPane.filterProperty().set(listPane.filterProperty().get().withView(ChatFilter.View.ARCHIVE));
        }
        listPane.setSelected(c.id());
        thread.open(c);
        if (infoVisible) panel.show(c, panel.current());
        svc.markRead(c.id());
        notifier.clear(c.id());
        listPane.refresh();
    }

    /** Shows the folders, or inside the one called {@code name} (snapshot diagnostics). */
    public void showFolders(String name) {
        if (name == null) listPane.showFolders();
        else listPane.showFolder(name);
    }

    /** Asks for a new folder's name, or to delete the second folder (snapshot diagnostics). */
    public void showFolderDialog(boolean delete) {
        if (delete) folderDialogs.delete(svc.folders().get(1), () -> { });
        else folderDialogs.create(() -> { });
    }

    /** Shows a server's channels over the chat list (snapshot diagnostics). */
    public void showSpace(String id) {
        listPane.showSpace(id);
    }

    /** As if typed in the open chat's message box. */
    public void type(String text) {
        thread.composer().insert(text);
    }

    /** As if dropped on the open chat. */
    public void sendFiles(java.util.List<java.io.File> files, app.unichat.service.SendAs as) {
        thread.composer().sendFiles(files, as);
    }

    public void openChatId(String id) {
        if (id == null) return;
        Chat c = svc.chat(id);
        if (c != null) {
            pendingOpenId = null;
            // From a notification: a channel shows inside its server, so it's clear where it came from.
            Chat server = SpaceTree.serverOf(c, svc.chats());
            if (server != null) listPane.showSpace(server.id());
            openChat(c, true);
            return;
        }
        // The room (e.g. a just-created bridge-bot chat) hasn't arrived through sync yet: open it when it does.
        pendingOpenId = id;
        pendingOpenUntil = System.currentTimeMillis() + 20_000;
        toast(I18n.t("main.openingChat"));
    }

    /** ⓘ button / ⌘⇧I: the side panel on its Perfil tab. */
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

    boolean infoVisible() { return infoVisible; }

    private void setInfo(boolean show) {
        if (show) {
            showPanel(panel.current());
            return;
        }
        infoVisible = false;
        threadArea.setRight(null);
        thread.setInfoSelected(false);
    }

    /** Just above the open chat's message box and centered on it; with no chat, near the bottom of the window. */
    private void placeToast() {
        Node box = thread.composer();
        if (thread.chat() == null || box.getScene() == null || !box.isVisible()) {
            toast.setTranslateX(0);
            toast.setTranslateY(-90);
            return;
        }
        javafx.geometry.Bounds b = box.localToScene(box.getLayoutBounds());
        javafx.geometry.Bounds me = localToScene(getLayoutBounds());
        toast.setTranslateY(-(me.getMaxY() - b.getMinY()) - 12);
        toast.setTranslateX(b.getCenterX() - me.getCenterX());
    }

    /** The clip's "Foto protegida contra IA": asks how hard, then protects and sends ({@link CloakSend}). */
    private void offerProtectedPhotos(ChatService svc) {
        CloakSend cloak = new CloakSend(svc, this::showOverlay, this::hideOverlay, this::toast);
        protectPhoto = () -> {
            if (thread.chat() != null) cloak.open(thread.chat().id(), () -> thread.composer().focus());
        };
        thread.composer().setOnProtect(protectPhoto);
    }

    /** The sheet that asks how hard to protect a photo, for the open chat (snapshot diagnostics). */
    public void showProtectPhoto() {
        protectPhoto.run();
    }

    public void toast(String text) {
        placeToast();
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

    boolean overlayOpen() { return overlay.isVisible(); }

    void hideOverlay() {
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
        CommandBar bar = new CommandBar(svc, svc, List.of(
                new CommandBar.Command(I18n.t("main.cmd.inbox"), "", () -> setView(ChatFilter.View.INBOX, false)),
                new CommandBar.Command(I18n.t("main.cmd.unread"), Shortcuts.key("2"), () -> setView(ChatFilter.View.INBOX, true)),
                new CommandBar.Command(I18n.t("main.cmd.favorites"), Shortcuts.key("3"), () -> setView(ChatFilter.View.FAVORITES, false)),
                new CommandBar.Command(I18n.t("main.cmd.lowPriority"), Shortcuts.key("4"), () -> setView(ChatFilter.View.LOW_PRIORITY, false)),
                new CommandBar.Command(I18n.t("main.cmd.archived"), "", () -> setView(ChatFilter.View.ARCHIVE, false)),
                new CommandBar.Command(I18n.t("main.cmd.folders"), "", listPane::showFolders),
                new CommandBar.Command(I18n.t("main.cmd.newChat"), Shortcuts.key("N"), this::showNewChat),
                new CommandBar.Command(I18n.t("main.cmd.accounts"), "", this::showAccounts),
                new CommandBar.Command(I18n.t("main.cmd.addAccount"), "", () -> showAccounts().showPicker()),
                new CommandBar.Command(I18n.t("main.cmd.settings"), Shortcuts.key(","), this::showSettings),
                new CommandBar.Command(I18n.t("main.cmd.logout"), "", onLogout)
        ), this::openChat, this::hideOverlay);
        showOverlay(bar);
        Platform.runLater(bar::focus);
    }

    void setView(ChatFilter.View v, boolean unread) {
        listPane.goTo(listPane.filterProperty().get().withView(v).withUnreadOnly(unread).withQuery(""));
    }

    /** Opens Ajustes → Cuentas (list of accounts, add account, login wizard). */
    public AccountsPane showAccounts() {
        showSettings();
        SettingsWindow.select("Cuentas");
        return SettingsWindow.accountsPage();
    }

    /** Filters the inbox to one network (all its accounts). */
    public void showNetwork(Network n) {
        listPane.goTo(listPane.filterProperty().get().switchTo(n, null));
    }

    /** Starts the login of one more account on {@code n} and opens its bridge-bot chat. */
    public void addAccount(Network n) {
        showAccounts().startLogin(n);
    }

    public void showSettings() {
        SettingsWindow.show(stage, svc, () -> {
            Theme.applyAll();
            refreshAll();
            thread.redraw();
        }, onLogout, this::openChatId, this::toast);
    }
}

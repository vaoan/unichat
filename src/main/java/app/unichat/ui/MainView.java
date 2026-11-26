package app.unichat.ui;

import app.unichat.ui.chats.AccountAlertBar;
import app.unichat.ui.chats.AccountAlerts;
import app.unichat.ui.chats.ChatFilter;
import app.unichat.ui.chats.ChatListPane;
import app.unichat.ui.chats.ChatOptions;
import app.unichat.ui.chats.CommandBar;
import app.unichat.ui.chats.ExploreBar;
import app.unichat.ui.chats.FolderDialogs;
import app.unichat.ui.chats.InfoPane;
import app.unichat.ui.chats.NewChatPane;
import app.unichat.ui.chats.Rail;
import app.unichat.ui.chats.SidePanel;
import app.unichat.ui.chats.SpaceTree;
import app.unichat.ui.desktop.NotificationPolicy;
import app.unichat.ui.desktop.Notifier;
import app.unichat.ui.emoji.EmojiGrid;
import app.unichat.ui.emoji.FavoritesPane;
import app.unichat.ui.emoji.GifGrid;
import app.unichat.ui.emoji.MediaItems;
import app.unichat.ui.emoji.StickerGrid;
import app.unichat.ui.emoji.StickerSuggestions;
import app.unichat.ui.login.AccountsPane;
import app.unichat.ui.media.AudioBar;
import app.unichat.ui.media.AudioPlayback;
import app.unichat.ui.media.ImageCache;
import app.unichat.ui.settings.CloudPage;
import app.unichat.ui.settings.SettingsWindow;
import app.unichat.ui.settings.SyncedSettings;
import app.unichat.ui.timeline.CloakBar;
import app.unichat.ui.timeline.CloakSend;
import app.unichat.ui.timeline.Composer;
import app.unichat.ui.timeline.ThreadPane;
import app.unichat.ui.timeline.UploadBar;
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
import org.jspecify.annotations.Nullable;

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
    private Consumer<Chat> chatOptions = c -> { };
    private final Label toast = new Label();
    private final Label connection = new Label();
    private ConnectionState state = ConnectionState.CONNECTING;
    /** What the top bar says once the server is connected, while networks reconnect (null: nothing). */
    private @Nullable String syncingNetworks;
    private final AccountAlertBar accountAlerts = new AccountAlertBar(this::addAccount);
    /** A newer Unichat, above the account alerts. */
    private final UpdateBar updateBar;
    private final Runnable onLogout;
    /** The one audio playing, whichever chat is open. */
    private final AudioPlayback audio;
    /** The batch going up, whichever chat is open. */
    private final UploadBar uploads;
    private final app.unichat.ui.timeline.ForwardBar forwards;
    private final ExploreBar exploring;
    private final app.unichat.ui.chats.RestBar resting;
    private final app.unichat.ui.chats.OlderChatsBar olderChats;
    /** "Load older chats" from the rail's network menu and ⌘K; none when the service has no older chats. */
    private final app.unichat.ui.chats.@Nullable OlderChatsLauncher olderLauncher;
    private @Nullable String pendingOpenId;
    private long pendingOpenUntil;
    private boolean infoVisible;
    private final SidePanel panel;
    private final StickerGrid stickers;
    private StickerSuggestions suggestions;
    /** Favourite and recent stickers/GIFs, shared by every chat. */
    private final MediaLibrary library =
            new MediaLibrary(Settings.dataDir().resolve("media-library.json"));

    public MediaLibrary library() { return library; }

    /** The chat open on the right, or null. */
    public @Nullable String currentChatId() {
        Chat open = thread.chat();
        return open == null ? null : open.id();
    }
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
                AccountsPane accounts = SettingsWindow.accountsPage();
                if (accounts != null) accounts.refresh();
                if (stage.isShowing()) showAccountAlerts();
                stickers.rebuildOnce(svc.accounts());
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
        info.setAudio(audio);
        panel = new SidePanel(info);
        thread.setOnShowServer(id -> {
            listPane.showSpace(id);
            listPane.setSelected(currentChatId());
        });
        stickers = new StickerGrid(svc, library, thread::chat, thread::takeReply, this::toast);
        MediaItems.keepCached(svc, library);
        library.addListener(() -> MediaItems.keepCached(svc, library));
        panel.setContent(SidePanel.Tab.STICKERS, stickers, stickers::refresh);
        suggestStickers();
        thread.setOnShowStickerSet(this::showStickerSet);
        GifGrid gifs = new GifGrid(svc, library, thread::chat, thread::takeReply, this::toast);
        panel.setContent(SidePanel.Tab.GIFS, gifs, gifs::refresh);
        EmojiGrid.Tab emojis = EmojiGrid.panel(library, text -> thread.composer().insert(text), this::toast);
        panel.setContent(SidePanel.Tab.EMOJIS, emojis.node(), emojis.refresh());
        FavoritesPane favorites = new FavoritesPane(library, stickers, gifs, text -> thread.composer().insert(text), this::toast);
        panel.setContent(SidePanel.Tab.FAVORITES, favorites, favorites::refresh);
        thread.composer().setOnPanel(this::togglePanel);
        offerProtectedPhotos(svc);
        forwards = new app.unichat.ui.timeline.ForwardBar(svc, this::openChatId);
        sheets(svc);
        offerChatOptions(svc);
        olderLauncher = app.unichat.ui.chats.OlderChatsLauncher.of(svc, this::showOverlay, this::hideOverlay);
        rail = new Rail(svc, listPane, this::showAccounts, this::addAccount, this::gearClicked, olderLauncher);
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
        exploring = new ExploreBar(app.unichat.autosave.AutoSave.of(svc), svc, this::openChatId);
        resting = app.unichat.ui.chats.RestBar.of(svc, listPane);
        olderChats = app.unichat.ui.chats.OlderChatsBar.of(svc);
        main.setTop(new javafx.scene.layout.VBox(connection, new AudioBar(audio, c -> openChat(c, true)), uploads, forwards, exploring,
                resting, new CloakBar(app.unichat.cloak.CloakJob.get()), olderChats));

        overlay.getStyleClass().add("overlay");
        overlay.setVisible(false);
        overlay.setOnMouseClicked(e -> { if (e.getTarget() == overlay) hideOverlay(); });

        toast.getStyleClass().add("toast");
        toast.setWrapText(true);
        toast.setMaxWidth(560);
        toast.setVisible(false);
        toast.setMouseTransparent(true);
        setAlignment(toast, Pos.BOTTOM_CENTER);
        toast.setTranslateY(-90);
        // Above the message box, whatever its height: at a fixed place it covered a box grown to several lines.
        thread.composer().layoutBoundsProperty().addListener((o, a, b) -> { if (toast.isVisible()) placeToast(); });

        getChildren().addAll(main, overlay, toast);
        WindowShortcuts shortcuts = new WindowShortcuts(this, svc, rail, listPane, thread, panel);
        addEventFilter(KeyEvent.KEY_PRESSED, shortcuts);
        addEventHandler(KeyEvent.KEY_PRESSED, shortcuts::typeIntoComposer); // a handler: after anything that used the key
        addEventFilter(KeyEvent.KEY_RELEASED, e -> thread.composer().aiKeyReleased(e.getCode()));
        stage.showingProperty().addListener(onShowing);
        stage.focusedProperty().addListener(onFocused);

        svc.addListener(listener);
        app.unichat.support.Storage.SHARED.addListener(onStorage);
        showConnection(svc.connectionState());
        showAccountAlerts();
        refreshAll();
        String warning = svc.preferencesWarning();
        if (warning != null) toast(warning);
        svc.onSaveFailure(text -> Platform.runLater(() -> toast(text)));
    }

    /**
     * The stage outlives this view (a language change or a logout builds another on it): what this view listens to
     * there is removed in {@link #dispose}, or it kept marking its chat read on each focus and stayed in memory.
     */
    private final javafx.beans.value.ChangeListener<Boolean> onShowing = this::showingChanged;
    private final javafx.beans.value.ChangeListener<Boolean> onFocused = this::focusChanged;

    private void showingChanged(javafx.beans.value.ObservableValue<? extends Boolean> o, Boolean was, Boolean showing) { // NOPMD: UnusedFormalParameter, a ChangeListener by method reference
        if (showing) catchingUp.request(); // the window shown again is brought up to date: a redraw to come
    }

    private final FxCoalescer catchingUp = new FxCoalescer(this::catchUp);

    private void focusChanged(javafx.beans.value.ObservableValue<? extends Boolean> o, Boolean was, Boolean focused) { // NOPMD: UnusedFormalParameter, a ChangeListener by method reference
        String open = currentChatId();
        if (focused && open != null) svc.markRead(open);
    }

    public void dispose() {
        stage.showingProperty().removeListener(onShowing);
        stage.focusedProperty().removeListener(onFocused);
        thread.dispose();
        audio.stop();
        uploads.dispose();
        exploring.dispose();
        resting.dispose();
        olderChats.dispose();
        updateBar.dispose();
        svc.removeListener(listener);
        app.unichat.support.Storage.SHARED.removeListener(onStorage);
        svc.onSaveFailure(null);
    }

    /** The disk ran low or had room again, or a process of the server couldn't write: told on another thread. */
    @SuppressWarnings("UnnecessaryLambda") // one instance: removeListener takes away what addListener added
    private final Runnable onStorage = () -> Platform.runLater(() -> showConnection(state));

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
        List<Chat> recent = svc.chats().stream().filter(c -> !c.bridgeBot() && c.avatarUrl() != null)
                .sorted(ChatFilter.ORDER).limit(150).toList();
        if (recent.isEmpty()) return;
        avatarsPreloaded = true;
        for (Chat c : recent) {
            String url = c.avatarUrl();
            if (url != null) { var _ = ImageCache.load(url, svc, ImageCache.Tier.SMALL); }
        }
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
        boolean viewing = stage.isFocused() && stage.isShowing() && chat.id().equals(currentChatId());
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
        app.unichat.support.Storage disk = app.unichat.support.Storage.SHARED;
        // A full disk first: with it nothing new arrives while the server and bridges still answer, so nothing else says so.
        String storage = s == ConnectionState.LOGGED_OUT ? null : AccountAlerts.storageLine(disk.low(), disk.free(), !disk.failing().isEmpty());
        String text = storage != null ? storage : s == ConnectionState.ONLINE ? syncingNetworks : switch (s) {
            case CONNECTING -> I18n.t("main.connecting");
            case SYNCING -> I18n.t("main.syncing");
            case OFFLINE -> I18n.t("main.offline");
            case LOGGED_OUT -> I18n.t("main.loggedOut");
            case ONLINE -> null;
        };
        connection.setText(text);
        connection.getStyleClass().removeAll("error");
        if (storage != null || s == ConnectionState.OFFLINE || s == ConnectionState.LOGGED_OUT) connection.getStyleClass().add("error");
        Nodes.show(connection, text != null);
        if (s == ConnectionState.LOGGED_OUT) onLogout.run();
    }

    // ------------------------------------------------------------------ navigation

    public AudioPlayback audio() { return audio; }

    public ThreadPane thread() { return thread; }

    public ChatListPane list() { return listPane; }

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
        resting.chatOpened(c);
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
    public void sendFiles(List<java.io.File> files, app.unichat.service.SendAs as) {
        thread.composer().sendFiles(files, as);
    }

    /**
     * What a notification's click led to, for the log (no ids: the kind of chat, whether it was known, what's open now).
     * Clicks on some Discord channels' notifications left another chat open; this says which and why.
     */
    public String openOutcome(String id) {
        Chat c = svc.chat(id);
        if (c == null) return "a chat not loaded yet" + (pendingOpenId != null ? " (waiting for it)" : "");
        Chat server = SpaceTree.serverOf(c, svc.chats());
        String kind = c.network() + (c.space() ? " server" : server != null ? " channel of a server" : " chat")
                + (c.archived() ? ", archived" : "") + (server != null && server.archived() ? ", its server archived" : "");
        Chat open = thread.chat();
        return kind + "; " + (open == null ? "nothing open" : open.id().equals(id) ? "opened" : "another chat open (" + open.network()
                + (SpaceTree.serverOf(open, svc.chats()) != null ? " channel" : " chat") + ")");
    }

    /** Goes to message {@code messageId} of the open chat {@code chatId}, if it's loaded (a notice about it was clicked). */
    public void showMessage(String chatId, String messageId) {
        if (!chatId.equals(currentChatId())) return;
        svc.messages(chatId).stream().filter(m -> messageId.equals(m.id())).findFirst().ifPresent(thread::jumpToMessage);
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

    /** The side panel's Stickers tab with {@code url}'s set open (a sticker's menu, the snapshot's {@code unichat.stickerSet}). */
    public void showStickerSet(String url) {
        showPanel(SidePanel.Tab.STICKERS);
        stickers.showPack(url);
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

    /** The gear among the profile's actions: the chat's own options, what it saves of what arrives among them. */
    private void offerChatOptions(ChatService service) {
        ChatOptions options = new ChatOptions(service, this::showOverlay, this::hideOverlay, this::toast);
        chatOptions = chat -> options.open(chat, () -> thread.composer().focus());
        info.setOnOptions(chatOptions);
        info.setOnJump(thread::jumpToMessage);
    }

    /** A chat's options sheet, for the chat open (snapshot diagnostics). */
    public void showChatOptions() {
        if (thread.chat() != null) chatOptions.accept(thread.chat());
    }

    /** The clip's "Foto protegida contra IA": asks how hard, then protects and sends ({@link CloakSend}). */
    private void offerProtectedPhotos(ChatService service) {
        CloakSend cloak = new CloakSend(service, this::showOverlay, this::hideOverlay, this::toast);
        protectPhoto = () -> {
            String open = currentChatId();
            if (open != null) cloak.open(open, () -> thread.composer().focus());
        };
        thread.composer().setOnProtect(protectPhoto);
    }

    /** Forwarding for the open chat: the sheet, or several messages picked (snapshot diagnostics). */
    public void showForwarding(boolean several) {
        if (thread.chat() != null) thread.showForwarding(several);
    }

    /** The question before deleting the newest messages of mine for everyone (snapshot diagnostics). */
    public void showDeleting() {
        if (thread.chat() != null) thread.showDeleting();
    }

    /** A stretch of the open chat picked to capture, or ({@code sheet}) its capture (snapshot diagnostics). */
    public void showCapture(boolean sheet) {
        if (thread.chat() != null) thread.showCapture(sheet);
    }

    /** Texts waiting in the open chat to be corrected before they go (snapshot diagnostics). */
    public void showQueue() {
        if (thread.chat() != null) thread.showQueue();
    }

    /** "Cargar mensajes anteriores" over the open chat, ({@code loading}) working (snapshot diagnostics). */
    public void showOlder(boolean loading) {
        if (thread.chat() != null) thread.showOlder(loading);
    }

    /** My newest message in the open chat being corrected with AI (snapshot diagnostics). */
    public void showFixing() {
        if (thread.chat() != null) thread.showFixing();
    }

    /** The chat's questions and sheets, in the window's overlay: forwarding, deleting, capturing, what a message said. */
    private void sheets(ChatService service) {
        thread.setForwardSheet(new app.unichat.ui.timeline.ForwardSheet(service, this::showOverlay, this::hideOverlay, forwards::show));
        thread.setDeleteSheet(new app.unichat.ui.timeline.DeleteSheet(this::showOverlay, this::hideOverlay));
        thread.setCaptureSheet(new app.unichat.ui.timeline.CaptureSheet(service, this::showOverlay, this::hideOverlay, this::toast));
        thread.setSavedTextSheet(new app.unichat.ui.timeline.SavedTextSheet(this::showOverlay, this::hideOverlay, this::toast));
    }

    /** What a deleted message said, or ({@code none}) that there's no copy, for the open chat (snapshot diagnostics). */
    public void showSavedText(boolean none) {
        if (thread.chat() != null) thread.showSavedText(none);
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
        setAlignment(card, Pos.TOP_CENTER);
        card.setTranslateY(90);
        overlay.setVisible(true);
        app.unichat.ui.media.AnimatedImage.cover(getScene(), overlay);
    }

    boolean overlayOpen() { return overlay.isVisible(); }

    void hideOverlay() {
        overlay.setVisible(false);
        overlay.getChildren().clear();
        app.unichat.ui.media.AnimatedImage.cover(getScene(), null);
        if (thread.chat() != null) thread.composer().focus();
    }

    /** Nuevo chat (⌘N / Ctrl+N): any contact of a network, starting with the one the list shows. */
    public void showNewChat() {
        NewChatPane pane = new NewChatPane(svc, listPane.filterProperty().get().network(), this::openChatId, this::hideOverlay, this::toast);
        showOverlay(pane);
        Platform.runLater(pane::focus);
    }

    public void showCommandBar() {
        List<CommandBar.Command> commands = new java.util.ArrayList<>(List.of(
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
        ));
        if (olderLauncher != null) commands.addAll(olderLauncher.commands(listPane::olderChatsNetworks));
        CommandBar bar = new CommandBar(svc, svc, commands, this::openChat, this::hideOverlay);
        showOverlay(bar);
        Platform.runLater(bar::focus);
    }

    void setView(ChatFilter.View v, boolean unread) {
        listPane.goTo(listPane.filterProperty().get().withView(v).withUnreadOnly(unread).withQuery(""));
    }

    /** Opens Settings → Accounts (list of accounts, add account, login wizard). */
    public AccountsPane showAccounts() {
        showSettings();
        SettingsWindow.select("accounts");
        return java.util.Objects.requireNonNull(SettingsWindow.accountsPage(), "selecting the accounts page builds it");
    }

    /** Filters the inbox to one network (all its accounts). */
    public void showNetwork(@Nullable Network n) {
        listPane.goTo(listPane.filterProperty().get().switchTo(n, null));
    }

    /** Starts the login of one more account on {@code n} and opens its bridge-bot chat. */
    public void addAccount(Network n) {
        showAccounts().startLogin(n);
    }

    public void showSettings() {
        SettingsWindow.show(stage, svc, this::appearanceChanged, onLogout, this::openChatId, this::toast);
    }

    /** The stickers of the one emoji typed, above the message box. */
    private void suggestStickers() {
        Composer box = thread.composer();
        suggestions = new StickerSuggestions(stickers, box.typed(), box::isEditing, box::clearLater);
        box.setOnModeChange(suggestions::refresh);
        box.showJustAbove(suggestions.node());
    }

    /** What Ajustes does after a change: the theme, the lists and the open chat drawn again. */
    private void appearanceChanged() {
        Theme.applyAll();
        suggestions.refresh();
        refreshAll();
        thread.redraw();
    }

    /** The rail's gear: Ajustes, straight on Nube while files go up (their detail). */
    private void gearClicked() {
        showSettings();
        if (rail.cloudActive()) SettingsWindow.select("cloud");
    }

    /** What the cloud is doing, on the settings gear. UI thread. */
    public void cloudChanged(app.unichat.cloud.Cloud.Status s) {
        rail.setCloud(s);
    }

    /** The person's cloud for Ajustes → Nube; null: none (the demo). */
    public void useCloud(app.unichat.cloud.@Nullable Cloud cloud) {
        CloudPage.use(cloud);
    }

    /** The settings changed on another computer and came through the cloud: applied now, as Ajustes would. UI thread. */
    public void settingsSynced() {
        SyncedSettings.apply(this::appearanceChanged);
    }
}

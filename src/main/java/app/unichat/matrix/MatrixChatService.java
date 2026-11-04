package app.unichat.matrix;

import app.unichat.login.BridgeLogin;
import app.unichat.model.Account;
import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Contact;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.Network;
import app.unichat.model.RoomFeatures;
import app.unichat.service.BridgeAccess;
import app.unichat.service.ChatService;
import app.unichat.service.QuietRule;
import app.unichat.service.StickerMode;
import app.unichat.settings.Settings;
import app.unichat.support.Hashes;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static app.unichat.matrix.BridgeProtocol.*;
import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.matrix.MatrixClient.enc;

/**
 * {@link ChatService} backed by a Matrix homeserver whose mautrix bridges provide the networks.
 * Model mutations happen under the {@link SyncProcessor} lock; listeners are called from background threads.
 * End-to-end encryption is not supported: bridges must be configured without encryption.
 */
public final class MatrixChatService implements ChatService, BridgeAccess {
    private static final String SYNC_FILTER =
            "{\"room\":{\"state\":{\"lazy_load_members\":true},\"timeline\":{\"limit\":30,\"lazy_load_members\":true}}}";
    private static final String HISTORY_FILTER = "{\"lazy_load_members\":true}";
    /** Users whose profile was already requested (lazy-loaded members have no display name yet). */
    private final Set<String> profileRequested = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Semaphore profileSlots = new java.util.concurrent.Semaphore(6);
    private final MatrixClient client;
    private final String userId;
    private final String serverName;
    private final SyncProcessor proc;
    private final NotifyGate notifyGate = new NotifyGate();
    /** Chats already seen in an earlier sync of this run (see QuietRule.toInherit). */
    private final Set<String> knownChats = new java.util.HashSet<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "matrix-timer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, CompletableFuture<Boolean>> historyInFlight = new ConcurrentHashMap<>();
    private final Set<String> joinAttempted = ConcurrentHashMap.newKeySet();
    private volatile ConnectionState state = ConnectionState.CONNECTING;
    private volatile String nextBatch;
    private volatile String displayName;
    private Thread syncThread;

    private final MatrixCore core;
    private final ExecutorService io;
    private final SyncCache cache;
    private final MediaDownloader media;
    private final MessageSender sender;
    private final ChatActions chatActions;
    private final BridgeAccounts bridges;
    private final ContactActions contacts;
    private final DiscordServers discord;

    private final String accessToken;
    /** Distinguishes this sign-in's cache from earlier sessions of the same user (see syncCacheFile). */
    private final String sessionKey;

    public MatrixChatService(String homeserver, String userId, String accessToken) {
        this.client = new MatrixClient(homeserver, accessToken);
        this.accessToken = accessToken;
        this.sessionKey = Hashes.sha256(accessToken).substring(0, 8);
        this.userId = userId;
        this.serverName = userId.substring(userId.indexOf(':') + 1);
        this.proc = new SyncProcessor(userId);
        this.displayName = SyncEvents.localpart(userId);
        this.core = new MatrixCore(client, proc, userId, serverName, accessToken);
        this.io = core.io;
        this.cache = new SyncCache(proc, serverName, userId, sessionKey);
        this.media = new MediaDownloader(client, io);
        this.sender = new MessageSender(core, media);
        BotChat bots = new BotChat(core, sender);
        this.discord = new DiscordServers(core, bots, this::accounts);
        this.bridges = new BridgeAccounts(core, bots, discord);
        this.chatActions = new ChatActions(core, this, discord);
        this.contacts = new ContactActions(core);
    }

    // ---------------------------------------------------------------- session

    // ---------------------------------------------------------------- lifecycle

    @Override
    public synchronized void start() {
        if (core.running) return;
        core.running = true;
        bridges.loadCachedLogins();
        syncThread = new Thread(this::syncLoop, "matrix-sync");
        syncThread.setDaemon(true);
        syncThread.start();
        io.execute(this::loadProfile);
        io.execute(bridges::checkBridges);
        timer.scheduleWithFixedDelay(chatActions::checkReminders, 10, 10, TimeUnit.SECONDS);
        bridges.watchLiveStates();
    }

    @Override
    public synchronized void stop() {
        bridges.unwatchLiveStates();
        core.running = false;
        if (syncThread != null) syncThread.interrupt();
        timer.shutdownNow();
    }

    @Override
    public CompletableFuture<Void> logout() {
        return CompletableFuture.runAsync(() -> {
            stop();
            try {
                client.post(CLIENT + "/logout", null);
            } catch (Exception e) {
                System.err.println("Matrix logout failed: " + e);
            }
            Settings.get().clearSession();
            try {
                Files.deleteIfExists(cache.file());
            } catch (IOException ignored) {
                // best effort
            }
            setState(ConnectionState.LOGGED_OUT);
        }, io);
    }

    private void loadProfile() {
        try {
            String dn = client.get(CLIENT + "/profile/" + enc(userId) + "/displayname").path("displayname").asText(null);
            if (dn != null && !dn.isBlank()) {
                displayName = dn;
                core.fire(Listener::accountsChanged);
            }
        } catch (Exception ignored) {
            // keep localpart
        }
    }

    // ---------------------------------------------------------------- sync loop

    /** Shows the cached snapshot right away; the network sync then only fetches what changed since. */
    private boolean loadSyncCache() {
        Path f = cache.file();
        if (!Files.exists(f)) return false;
        try {
            JsonNode resp = MatrixClient.JSON.readTree(f.toFile());
            String token = resp.path("next_batch").asText(null);
            if (token == null) return false;
            SyncProcessor.Result r;
            synchronized (proc) {
                r = proc.process(resp, false);
            }
            nextBatch = token;
            dispatch(r, false);
            return true;
        } catch (Exception e) {
            System.err.println("Ignoring unreadable sync cache: " + e);
            return false;
        }
    }

    /** Replaces the snapshot with a fresh full sync, in the background, so catch-ups stay small. */
    private void refreshSyncCache() {
        io.execute(() -> {
            try {
                Thread.sleep(30_000);
                if (!core.running) return;
                String path = CLIENT + "/sync?filter=" + enc(SYNC_FILTER) + "&timeout=0";
                cache.save(client.request("GET", path, null, Duration.ofSeconds(300)));
            } catch (Exception e) {
                System.err.println("Sync cache refresh failed: " + e);
            }
        });
    }

    private void syncLoop() {
        int failures = 0;
        setState(ConnectionState.CONNECTING);
        boolean catchUp = loadSyncCache();
        while (core.running) {
            try {
                boolean initial = nextBatch == null;
                if (initial) setState(ConnectionState.SYNCING);
                // A catch-up from the cached token must return at once, not long-poll for 30 s.
                int timeout = initial || catchUp ? 0 : 30000;
                String path = CLIENT + "/sync?filter=" + enc(SYNC_FILTER) + "&timeout=" + timeout
                        + (initial ? "" : "&since=" + enc(nextBatch));
                JsonNode resp;
                try {
                    resp = client.request("GET", path, null, Duration.ofSeconds(initial || catchUp ? 180 : 75));
                } catch (MatrixException e) {
                    if (catchUp && !"M_UNKNOWN_TOKEN".equals(e.errcode())) {
                        // The cached token was rejected (expired/purged): fall back to a full sync.
                        catchUp = false;
                        nextBatch = null;
                        continue;
                    }
                    throw e;
                }
                if (!core.running) return;
                // Missed messages during the catch-up don't notify, like the initial sync.
                boolean live = core.initialSyncDone && !catchUp;
                SyncProcessor.Result r;
                List<String> mutedElsewhere;
                List<String> inArchivedServer;
                synchronized (proc) {
                    r = proc.process(resp, live);
                    List<Chat> now = proc.rooms().stream().map(x -> x.chat).toList();
                    mutedElsewhere = QuietRule.toArchive(mutedSeen, now);
                    mutedSeen.clear();
                    for (Chat c : now) if (c.muted()) mutedSeen.add(c.id());
                    inArchivedServer = QuietRule.toInherit(knownChats, now);
                    for (Chat c : now) knownChats.add(c.id());
                }
                // Muted on the phone (or anywhere): archived too, like a mute made here.
                for (String id : mutedElsewhere) chatActions.archiveOnly(id, true).exceptionally(e -> null);
                // A channel that arrived in an archived server goes quiet like its siblings.
                for (String id : inArchivedServer) chatActions.quiet(id, true).exceptionally(e -> null);
                nextBatch = resp.path("next_batch").asText(null);
                if (initial) cache.save(resp);
                else if (catchUp) refreshSyncCache();
                boolean firstRound = !core.initialSyncDone;
                catchUp = false;
                core.initialSyncDone = true;
                failures = 0;
                setState(ConnectionState.ONLINE);
                dispatch(r, live && !firstRound);
                if (firstRound) chatActions.checkReminders();
            } catch (MatrixException e) {
                if ("M_UNKNOWN_TOKEN".equals(e.errcode())) {
                    core.running = false;
                    Settings.get().clearSession();
                    setState(ConnectionState.LOGGED_OUT);
                    return;
                }
                failures = backoff(++failures, e);
            } catch (InterruptedIOException e) {
                if (!core.running) return;
                failures = backoff(++failures, e);
            } catch (Exception e) {
                failures = backoff(++failures, e);
            }
        }
    }

    private int backoff(int failures, Exception e) {
        if (!core.running) return failures;
        System.err.println("Matrix sync failed (" + failures + "): " + e);
        setState(ConnectionState.OFFLINE);
        long seconds = Math.min(30, 1L << Math.min(failures - 1, 5));
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return failures;
    }

    private void dispatch(SyncProcessor.Result r, boolean notify) {
        if (r.chatsChanged) core.fire(Listener::chatsChanged);
        for (String id : r.messagesChanged) core.fire(l -> l.messagesChanged(id));
        // Read elsewhere (the phone, another client): nothing up to there is news any more.
        r.readByMe.forEach(notifyGate::seen);
        if (!notify) {
            // Initial sync or catch-up: everything there is counts as seen, so it can't be announced later.
            synchronized (proc) {
                for (MatrixRoom room : proc.rooms()) notifyGate.seen(room.id, room.chat.lastActivity());
            }
        }
        if (notify) {
            java.time.Instant now = java.time.Instant.now();
            for (Message m : r.newMessages) {
                // History a bridge backfills or resends arrives as new events: only what's really new is announced.
                if (!notifyGate.isNew(m, now)) continue;
                Chat c = chat(m.chatId());
                if (c != null && !c.muted() && !c.bridgeBot()) core.fire(l -> l.incomingMessage(c, m));
            }
        }
        for (String roomId : r.invites) autoJoin(roomId);
        if (notify) {
            for (Message m : r.newMessages) {
                Chat c = chat(m.chatId());
                if (c != null && c.bridgeBot() && loginChanged(m.body())) bridges.refreshLogins(c.network());
            }
        } else {
            bridges.refreshStaleLogins();
        }
        fetchMissingProfiles();
        bridges.publishIfChanged();
    }

    /** Fetches display names/avatars of chat counterparts the sync didn't include (lazy-loaded members). */
    private void fetchMissingProfiles() {
        Set<String> users;
        synchronized (proc) {
            users = proc.unnamedUsers();
        }
        for (String u : users) {
            if (!profileRequested.add(u)) continue;
            io.execute(() -> {
                try {
                    profileSlots.acquire();
                } catch (InterruptedException e) {
                    profileRequested.remove(u);
                    return;
                }
                try {
                    JsonNode p = client.get(CLIENT + "/profile/" + enc(u));
                    String name = p.path("displayname").asText(null);
                    String avatar = p.path("avatar_url").asText(null);
                    if (name == null && avatar == null) return;
                    List<String> changed;
                    synchronized (proc) {
                        changed = proc.applyProfile(u, name, avatar);
                    }
                    if (!changed.isEmpty()) core.fire(Listener::chatsChanged);
                } catch (Exception ignored) {
                    // keep the fallback name
                } finally {
                    profileSlots.release();
                }
            });
        }
    }

    /** Bridges invite us to portal rooms; join every invite (non-bridge invites show up as chats too). */
    private void autoJoin(String roomId) {
        if (!joinAttempted.add(roomId)) return;
        io.execute(() -> {
            try {
                client.post(CLIENT + "/join/" + enc(roomId), null);
            } catch (Exception e) {
                System.err.println("Could not join " + roomId + ": " + e);
            }
        });
    }

    private void setState(ConnectionState s) {
        if (state == s) return;
        state = s;
        core.fire(l -> l.connectionChanged(s));
    }

    // ---------------------------------------------------------------- queries

    @Override public ConnectionState connectionState() { return state; }
    @Override public String myUserId() { return userId; }
    @Override public String myDisplayName() { return displayName; }

    @Override
    public List<Chat> chats() {
        synchronized (proc) {
            List<Chat> out = new ArrayList<>();
            for (MatrixRoom r : proc.rooms()) out.add(r.chat);
            return out;
        }
    }

    @Override
    public List<Message> messages(String chatId) {
        synchronized (proc) {
            MatrixRoom r = proc.room(chatId);
            return r == null ? List.of() : new ArrayList<>(r.timeline);
        }
    }

    @Override
    public CompletableFuture<Boolean> loadOlder(String chatId) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        CompletableFuture<Boolean> prev = historyInFlight.putIfAbsent(chatId, f);
        if (prev != null) return prev;
        io.execute(() -> {
            try {
                String from;
                synchronized (proc) {
                    MatrixRoom r = proc.room(chatId);
                    from = r == null ? null : r.prevBatch;
                }
                if (from == null) {
                    f.complete(false);
                    return;
                }
                JsonNode resp = client.get(CLIENT + "/rooms/" + enc(chatId) + "/messages?dir=b&limit=30&from="
                        + enc(from) + "&filter=" + enc(HISTORY_FILTER));
                SyncProcessor.Result r;
                synchronized (proc) {
                    r = proc.processHistory(chatId, resp);
                }
                dispatch(r, false);
                f.complete(r.hasMore);
            } catch (Exception e) {
                f.completeExceptionally(e);
            } finally {
                historyInFlight.remove(chatId, f);
            }
        });
        return f;
    }

    // ---------------------------------------------------------------- sending

    // ---------------------------------------------------------------- chat state

    // ---------------------------------------------------------------- archived Discord servers load nothing

    // ---------------------------------------------------------------- bridges & accounts

    // ---------------------------------------------------------------- Discord servers

    /** Chats muted at the last sync ({@link app.unichat.service.QuietRule#toArchive}). */
    private final Set<String> mutedSeen = new java.util.HashSet<>();

    // ---------------------------------------------------------------- the parts

    Path syncCacheFile() { return cache.file(); }

    public static void setBridgeProvisioner(BridgeProvisioner p) { BridgeAccounts.provisioner = p; }

    @Override public void addListener(Listener listener) { core.listeners.add(listener); }
    @Override public void removeListener(Listener listener) { core.listeners.remove(listener); }
    @Override public Chat chat(String chatId) { return core.chat(chatId); }
    @Override public CompletableFuture<Path> media(String url, int width, int height) { return media.media(url, width, height); }

    @Override public CompletableFuture<Void> sendText(String chatId, String text, String replyToId) { return sender.sendText(chatId, text, replyToId); }
    @Override public CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item) { return sender.sendSticker(chatId, item); }
    CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item, RoomFeatures features, StickerMode mode) { return sender.sendSticker(chatId, item, features, mode); }
    CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item, RoomFeatures features) { return sender.sendSticker(chatId, item, features); }
    @Override public CompletableFuture<Void> sendFile(String chatId, Path file, String replyToId) { return sender.sendFile(chatId, file, replyToId); }
    @Override public CompletableFuture<Void> sendGif(String chatId, Path file, String mime, int width, int height) { return sender.sendGif(chatId, file, mime, width, height); }
    @Override public CompletableFuture<Void> editMessage(String chatId, String messageId, String newText) { return sender.editMessage(chatId, messageId, newText); }
    @Override public CompletableFuture<Void> deleteMessage(String chatId, String messageId) { return sender.deleteMessage(chatId, messageId); }
    @Override public CompletableFuture<Void> toggleReaction(String chatId, String messageId, String emoji) { return sender.toggleReaction(chatId, messageId, emoji); }
    @Override public void sendTyping(String chatId, boolean typing) { sender.sendTyping(chatId, typing); }
    @Override public void markRead(String chatId) { chatActions.markRead(chatId); }
    @Override public CompletableFuture<Void> setMarkedUnread(String chatId, boolean unread) { return chatActions.setMarkedUnread(chatId, unread); }
    @Override public CompletableFuture<Void> setFavorite(String chatId, boolean favorite) { return chatActions.setFavorite(chatId, favorite); }
    @Override public CompletableFuture<Void> setLowPriority(String chatId, boolean lowPriority) { return chatActions.setLowPriority(chatId, lowPriority); }
    @Override public CompletableFuture<Void> setArchived(String chatId, boolean archived) { return chatActions.setArchived(chatId, archived); }
    @Override public CompletableFuture<Void> setPinned(String chatId, boolean pinned) { return chatActions.setPinned(chatId, pinned); }
    @Override public CompletableFuture<Void> setMuted(String chatId, boolean muted) { return chatActions.setMuted(chatId, muted); }
    @Override public CompletableFuture<Void> setReminder(String chatId, Instant at) { return chatActions.setReminder(chatId, at); }
    @Override public CompletableFuture<Void> joinChat(String roomId) { return chatActions.joinChat(roomId); }
    @Override public CompletableFuture<Map<String, String>> spaceNames(String spaceId) { return chatActions.spaceNames(spaceId); }
    @Override public CompletableFuture<Void> leaveChat(String chatId) { return chatActions.leaveChat(chatId); }
    @Override public List<Network> availableNetworks() { return bridges.availableNetworks(); }
    @Override public List<Account> accounts() { return bridges.accounts(); }
    @Override public BridgeLogin bridgeLogin(Network n) { return bridges.bridgeLogin(n); }
    @Override public void prepareBridge(Network n) throws Exception { bridges.prepareBridge(n); }
    @Override public void loginFinished(Network n) { bridges.loginFinished(n); }
    @Override public CompletableFuture<String> connectNetwork(Network network) { return bridges.connectNetwork(network); }
    @Override public CompletableFuture<Void> disconnectNetwork(Network network) { return bridges.disconnectNetwork(network); }
    @Override public CompletableFuture<Void> disconnectAccount(Account account) { return bridges.disconnectAccount(account); }
    @Override public CompletableFuture<Void> cancelLogin(Network network, String botChatId) { return bridges.cancelLogin(network, botChatId); }
    @Override public CompletableFuture<String> syncChats(Network n) { return bridges.syncChats(n); }
    @Override public CompletableFuture<ContactSupport> contactSupport(Account a) { return contacts.contactSupport(a); }
    @Override public CompletableFuture<List<Contact>> contacts(Account a) { return contacts.contacts(a); }
    @Override public CompletableFuture<List<Contact>> searchContacts(Account a, String query) { return contacts.searchContacts(a, query); }
    @Override public CompletableFuture<Contact> resolveContact(Account a, String identifier) { return contacts.resolveContact(a, identifier); }
    @Override public CompletableFuture<String> openDm(Account a, Contact contact) { return contacts.openDm(a, contact); }
    @Override public CompletableFuture<Void> loadAllChannels(String spaceId) { return discord.loadAllChannels(spaceId); }
    @Override public CompletableFuture<List<DiscordGuild>> discordGuilds() { return discord.discordGuilds(); }
    @Override public CompletableFuture<Void> setDiscordGuildBridged(String guildId, boolean bridged) { return discord.setDiscordGuildBridged(guildId, bridged); }
    @Override public CompletableFuture<List<GroupOffer>> groupOffers(Network network) { return discord.groupOffers(network); }
    @Override public CompletableFuture<Void> addGroup(Network network, String offerId) {
        return network == Network.DISCORD ? discord.addGroup(offerId) : ChatService.super.addGroup(network, offerId);
    }
}

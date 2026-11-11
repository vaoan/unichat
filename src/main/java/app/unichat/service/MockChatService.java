package app.unichat.service;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Contact;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.model.MimeTypes;
import app.unichat.model.Network;
import app.unichat.model.VoiceNote;
import app.unichat.support.I18n;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Offline demo backend: a believable multi-network inbox that lives in memory. Incoming messages,
 * typing and read receipts are simulated so every UI state can be exercised without a server.
 */
public class MockChatService implements ChatService, FolderForwarding {
    private final Map<String, app.unichat.model.LinkMedia> demoLinks = new java.util.concurrent.ConcurrentHashMap<>();
    static final String ME = "@yo:demo.unichat";
    static final String WA_PERSONAL = "whatsapp:personal";
    static final String WA_WORK = "whatsapp:trabajo";
    private static final List<Network> AVAILABLE = List.of(Network.WHATSAPP, Network.TELEGRAM, Network.SIGNAL,
            Network.INSTAGRAM, Network.MESSENGER, Network.DISCORD, Network.SLACK, Network.GOOGLE_MESSAGES,
            Network.X, Network.LINKEDIN);
    private static String autoReply(int i) { return I18n.t("demo.reply." + (i + 1)); }
    private static final int AUTO_REPLY_COUNT = 10;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    protected final Map<String, Chat> chats = new ConcurrentHashMap<>();
    protected final Map<String, List<Message>> messages = new ConcurrentHashMap<>();
    /** Account id -> account, in connection order. */
    final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Random random = new Random(7);
    private ScheduledExecutorService scheduler;
    private volatile ConnectionState state = ConnectionState.CONNECTING;

    private final PreferencesFile prefs;
    private final FolderActions folders;

    /** Demo mode with nothing kept on disk (tests, snapshots). */
    public MockChatService() {
        this(PreferencesFile.inMemory());
    }

    /** Demo mode whose choices and folders live in {@code prefs}, like the real service. */
    public MockChatService(PreferencesFile prefs) {
        this.prefs = prefs;
        this.folders = new FolderActions(prefs, this::fireChats);
        DemoChats.seed(this);
    }

    void adopt(Chat c) { DemoChats.adopt(prefs, c); }

    @Override public ChatFolders folderActions() { return folders; }

    // ---------------------------------------------------------------- lifecycle

    @Override public void addListener(Listener l) { listeners.add(l); }
    @Override public void removeListener(Listener l) { listeners.remove(l); }

    @Override
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mock-chat");
            t.setDaemon(true);
            return t;
        });
        after(400, () -> setState(ConnectionState.ONLINE));
        scheduler.scheduleWithFixedDelay(this::simulateIncoming, 25, 40, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::checkReminders, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        synchronized (this) {
            if (scheduler != null) scheduler.shutdownNow();
            scheduler = null;
        }
        prefs.flush();
    }

    @Override
    public CompletableFuture<Void> logout() {
        stop();
        setState(ConnectionState.LOGGED_OUT);
        return CompletableFuture.completedFuture(null);
    }

    @Override public ConnectionState connectionState() { return state; }
    @Override public String myUserId() { return ME; }
    @Override public String myDisplayName() { return I18n.t("demo.me"); }

    private void setState(ConnectionState s) {
        state = s;
        listeners.forEach(l -> l.connectionChanged(s));
    }

    // ---------------------------------------------------------------- reading

    public static final String UNJOINED_CHANNEL_ID = "!srv-anuncios:demo";

    @Override
    public CompletableFuture<Void> joinChat(String roomId) {
        if (failJoins) return CompletableFuture.failedFuture(new IllegalStateException("join refused"));
        Runnable arrive = () -> {
            if (UNJOINED_CHANNEL_ID.equals(roomId) && !chats.containsKey(roomId)) {
                DemoChats.ChatBuilder.group(this, "srv-anuncios", Network.DISCORD, I18n.t("demo.title.announcements"), "Mafe")
                        .in(-100, "Mafe", I18n.t("demo.announcements.1")).done();
                fireChats();
            }
        };
        // Like a real server: the join answers at once, the room reaches sync later on a slow bridge.
        viaBridge(arrive);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<String> syncChats(Network network) {
        return network == Network.TELEGRAM ? later(() -> "Successfully synchronized chats")
                : CompletableFuture.failedFuture(new UnsupportedOperationException("syncChats"));
    }

    /** A demo Discord server you're in that doesn't come into Unichat until you add it. */
    public static final String OFFERED_GUILD_ID = "900000000000000001";

    /** Demo/tests: no Discord server in Unichat yet, like right after logging in. */
    public void dropDemoServer() {
        for (String id : List.of("!servidor:demo", "!srv-general:demo", "!srv-memes:demo")) chats.remove(id);
        fireChats();
    }

    private volatile boolean offerAdded;

    @Override
    public CompletableFuture<List<GroupOffer>> groupOffers(Network network) {
        return later(() -> network == Network.DISCORD && !offerAdded
                ? List.of(new GroupOffer(OFFERED_GUILD_ID, I18n.t("demo.title.games"))) : List.of());
    }

    @Override
    public CompletableFuture<Void> addGroup(Network network, String offerId) {
        if (network != Network.DISCORD || !OFFERED_GUILD_ID.equals(offerId)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown server"));
        }
        return later(() -> {
            offerAdded = true;
            Chat space = new Chat("!juegos:demo");
            space.setNetwork(Network.DISCORD);
            space.setTitle(I18n.t("demo.title.games"));
            space.setSpace(true);
            space.setSpaceChildren(List.of("!juegos-general:demo"));
            adopt(space);
            chats.put(space.id(), space);
            DemoChats.ChatBuilder.group(this, "juegos-general", Network.DISCORD, "#general", "xNico")
                    .in(-100, "xNico", I18n.t("demo.games.1")).done();
            fireChats();
            return null;
        });
    }

    private final List<String> channelLoads = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public CompletableFuture<Void> loadAllChannels(String spaceId) {
        if (!channelLoads.contains(spaceId)) channelLoads.add(spaceId);
        return CompletableFuture.completedFuture(null);
    }

    /** Demo/tests: the servers whose channels were all asked for. */
    public List<String> channelLoads() {
        return channelLoads;
    }

    private volatile boolean failJoins;

    /** Demo/tests: every join is refused (a channel you lost access to). */
    public void failJoins(boolean fail) {
        failJoins = fail;
    }

    @Override
    public CompletableFuture<Map<String, String>> spaceNames(String spaceId) {
        return CompletableFuture.completedFuture("!servidor:demo".equals(spaceId) ? Map.of(UNJOINED_CHANNEL_ID, I18n.t("demo.title.announcements")) : Map.of());
    }

    /** Open unless a test holds the bridge ({@link #holdBridge}): what the bridge does then waits for it. */
    private volatile CompletableFuture<Void> bridgeGate = CompletableFuture.completedFuture(null);

    /**
     * A slow bridge, for tests: its answers, the rooms it joins and the chats it creates wait until the returned
     * Runnable lets them through, so a test decides what happens first instead of racing two times.
     */
    public Runnable holdBridge() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        bridgeGate = gate;
        return () -> gate.complete(null);
    }

    private void viaBridge(Runnable r) {
        CompletableFuture<Void> gate = bridgeGate;
        if (gate.isDone()) r.run();
        else gate.thenRunAsync(r);
    }

    private <T> CompletableFuture<T> later(java.util.function.Supplier<T> s) {
        CompletableFuture<Void> gate = bridgeGate;
        if (gate.isDone()) return CompletableFuture.completedFuture(s.get());
        return gate.thenApplyAsync(x -> s.get());
    }

    @Override
    public CompletableFuture<ContactSupport> contactSupport(Account account) {
        return later(() -> switch (account.network()) {
            case WHATSAPP, TELEGRAM -> new ContactSupport(true, true);
            case INSTAGRAM, MESSENGER -> new ContactSupport(false, true);
            default -> new ContactSupport(false, false);
        });
    }

    @Override
    public CompletableFuture<List<Contact>> contacts(Account account) {
        return CompletableFuture.completedFuture(account.network() == Network.WHATSAPP ? DemoChats.contacts() : List.of());
    }

    @Override
    public CompletableFuture<List<Contact>> searchContacts(Account account, String query) {
        if (account.network() != Network.WHATSAPP && account.network() != Network.TELEGRAM
                && account.network() != Network.INSTAGRAM && account.network() != Network.MESSENGER) return CompletableFuture.failedFuture(new UnsupportedOperationException("search"));
        String q = query.toLowerCase(java.util.Locale.ROOT);
        return CompletableFuture.completedFuture(DemoChats.contacts().stream()
                .filter(c -> c.name().toLowerCase(java.util.Locale.ROOT).contains(q)).toList());
    }

    @Override
    public CompletableFuture<Contact> resolveContact(Account account, String identifier) {
        String digits = identifier.replaceAll("[^0-9]", "");
        return CompletableFuture.completedFuture(digits.length() >= 7
                ? new Contact("c-" + digits, identifier.strip(), List.of("tel:+" + digits), null, null) : null);
    }

    @Override
    public CompletableFuture<String> openDm(Account account, Contact contact) {
        if (contact.dmRoomId() != null) return CompletableFuture.completedFuture(contact.dmRoomId());
        String key = "dm-" + contact.id();
        Runnable create = () -> {
            if (!chats.containsKey("!" + key + ":demo")) {
                DemoChats.ChatBuilder.dm(this, key, account.network(), contact.name()).done();
                fireChats();
            }
        };
        viaBridge(create);
        return CompletableFuture.completedFuture("!" + key + ":demo");
    }

    @Override public List<Chat> chats() { return new ArrayList<>(chats.values()); }
    @Override public Chat chat(String chatId) { return chats.get(chatId); }

    @Override
    public List<Message> messages(String chatId) {
        List<Message> list = messages.get(chatId);
        if (list == null) return List.of();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    @Override
    public CompletableFuture<Boolean> loadOlder(String chatId) {
        return CompletableFuture.completedFuture(false);
    }

    // ---------------------------------------------------------------- sending

    @Override
    public CompletableFuture<Void> sendText(String chatId, String text, String replyToId) {
        CompletableFuture<Void> sent = sendLocal(chatId, replyToId, m -> {
            m.setBody(Message.withoutEmoteCommand(text));
            m.setHtml(app.unichat.model.Markup.html(m.body()));
            if (Message.isEmoteCommand(text)) m.setType(MessageType.EMOTE);
        });
        Chat chat = chats.get(chatId);
        if (chat != null) {
            if (chat.bridgeBot()) answerBridgeBot(chat, text.trim());
            else if (autoReplies && random.nextInt(3) == 0) replyLater(chat, 3 + random.nextInt(4));
        }
        return sent;
    }

    /** Whether someone answers now and then what's sent (the demo's life); tests turn it off and play the others. */
    private volatile boolean autoReplies = true;

    public void noAutoReplies() { autoReplies = false; }

    @Override
    public CompletableFuture<List<app.unichat.model.StickerPack.Ref>> stickerPacks() { return DemoStickers.packs(); }

    @Override
    public CompletableFuture<app.unichat.model.StickerPack> stickerPack(String url) { return DemoStickers.pack(url); }

    @Override
    public CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item, String replyToId) {
        return sendLocal(chatId, replyToId, m -> {
            m.setType(MessageType.STICKER);
            m.setMediaUrl(item.url());
            m.setMimeType(item.mime());
            m.setSize(item.width(), item.height());
            m.setMediaInfo(item.info());
        });
    }

    @Override
    public CompletableFuture<Void> sendVoice(String chatId, VoiceNote note, String replyToId) {
        return sendLocal(chatId, replyToId, m -> {
            m.setType(MessageType.AUDIO);
            m.setFileName(note.file().getFileName().toString());
            m.setMimeType("audio/ogg");
            m.setMediaUrl(note.file().toUri().toString());
            m.setMediaInfo(com.fasterxml.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("duration", note.durationMs()));
        });
    }

    /** Shows at once like the real one; the "upload" takes half a second, so the upload bar can be seen. */
    @Override
    public CompletableFuture<Void> sendFile(String chatId, Path file, String replyToId, SendAs as,
                                            java.util.function.DoubleConsumer progress) {
        CompletableFuture<Void> shown = sendLocal(chatId, replyToId, m -> {
            String name = file.getFileName().toString();
            m.setFileName(name);
            m.setBody(name);
            m.setMediaUrl(file.toUri().toString());
            try {
                m.setFileSize(Files.size(file));
                m.setType(MessageType.ofMime(MimeTypes.of(file)));
                if (as == SendAs.DOCUMENT) m.setType(MessageType.FILE);
                if (m.type() == MessageType.IMAGE) {
                    BufferedImage img = ImageIO.read(file.toFile());
                    if (img != null) m.setSize(img.getWidth(), img.getHeight());
                }
            } catch (IOException e) {
                m.setType(MessageType.FILE);
            }
        });
        if (shown.isCompletedExceptionally()) return shown;
        CompletableFuture<Void> sent = new CompletableFuture<>();
        for (int i = 1; i <= 5; i++) {
            double p = i / 5.0;
            after(i * 100L, () -> progress.accept(p));
        }
        after(550, () -> sent.complete(null));
        return sent;
    }

    /** What sending anything has in common: a new outgoing message that {@code fill} completes, then its delivery. */
    private CompletableFuture<Void> sendLocal(String chatId, String replyToId, Consumer<Message> fill) {
        Chat chat = chats.get(chatId);
        if (chat == null) return CompletableFuture.failedFuture(new IllegalArgumentException(I18n.t("error.chatNotFound")));
        Message m = outgoing(chatId);
        m.setReplyToId(replyToId);
        fill.accept(m);
        append(chat, m);
        deliver(chat, m);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> editMessage(String chatId, String messageId, String newText) {
        Message m = find(chatId, messageId);
        if (m != null && m.outgoing()) {
            m.setBody(newText);
            m.setEdited(true);
            refreshPreview(chats.get(chatId));
            fireMessages(chatId);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> deleteMessage(String chatId, String messageId) {
        Message m = find(chatId, messageId);
        if (m != null) {
            m.markDeleted();
            refreshPreview(chats.get(chatId));
            fireMessages(chatId);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> toggleReaction(String chatId, String messageId, String emoji) {
        Message m = find(chatId, messageId);
        if (m != null) {
            m.toggleReaction(emoji, ME);
            fireMessages(chatId);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void sendTyping(String chatId, boolean typing) {
        // Nobody is listening in the demo.
    }

    // ---------------------------------------------------------------- chat state

    @Override
    public void markRead(String chatId) {
        Chat c = chats.get(chatId);
        if (c == null || (!c.isUnread() && c.mentionCount() == 0)) return;
        c.setUnreadCount(0);
        c.setMentionCount(0);
        c.setMarkedUnread(false);
        fireChats();
    }

    @Override public CompletableFuture<Void> setMarkedUnread(String id, boolean v) { return update(id, c -> c.setMarkedUnread(v)); }
    @Override public CompletableFuture<Void> setFavorite(String id, boolean v) { return choose(id, PreferencesFile.Flag.FAVORITE, v, c -> c.setFavorite(v)); }
    @Override public CompletableFuture<Void> setLowPriority(String id, boolean v) { return choose(id, PreferencesFile.Flag.LOW_PRIORITY, v, c -> c.setLowPriority(v)); }
    @Override public CompletableFuture<Void> setAutoSave(String id, boolean mine, java.util.Set<app.unichat.model.SaveKind> kinds) {
        return update(id, c -> { if (c.prefKey() != null) prefs.chooseSaveKinds(c.prefKey(), mine, kinds); });
    }
    @Override public CompletableFuture<Void> setMediaHidden(String id, boolean v) { return choose(id, PreferencesFile.Flag.MEDIA_HIDDEN, v, c -> { }); }
    @Override public boolean mediaHidden(String id) {
        Chat c = chat(id);
        return c != null && c.prefKey() != null && prefs.isOn(c.prefKey(), PreferencesFile.Flag.MEDIA_HIDDEN);
    }
    @Override public java.util.Set<app.unichat.model.SaveKind> autoSaves(String id, boolean mine) {
        Chat c = chat(id);
        return c == null || c.prefKey() == null ? java.util.Set.of() : prefs.saveKinds(c.prefKey(), mine);
    }
    @Override public CompletableFuture<Void> setArchived(String id, boolean v) { return quiet(id, v); }
    @Override public CompletableFuture<Void> setMuted(String id, boolean v) { return quiet(id, v); }

    /** Muted = archived ({@link QuietRule}). */
    private CompletableFuture<Void> quiet(String id, boolean v) {
        for (String t : QuietRule.targets(this, id)) {
            Chat c = chats.get(t);
            if (c == null) continue;
            if (c.prefKey() != null) prefs.chooseQuiet(c.prefKey(), v);
            c.setArchived(v);
            c.setMuted(v);
        }
        fireChats();
        return CompletableFuture.completedFuture(null);
    }
    @Override public CompletableFuture<Void> setPinned(String id, boolean v) { return choose(id, PreferencesFile.Flag.PINNED, v, c -> c.setPinned(v)); }
    @Override public CompletableFuture<Void> setReminder(String id, Instant at) { return update(id, c -> {
        if (c.prefKey() != null) prefs.chooseReminder(c.prefKey(), at);
        c.setReminderAt(at);
    }); }

    @Override
    public CompletableFuture<Void> leaveChat(String chatId) {
        chats.remove(chatId);
        messages.remove(chatId);
        fireChats();
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> choose(String id, PreferencesFile.Flag flag, boolean v,
                                           Consumer<Chat> change) {
        return update(id, c -> {
            if (c.prefKey() != null) prefs.choose(c.prefKey(), flag, v);
            change.accept(c);
        });
    }

    private CompletableFuture<Void> update(String chatId, Consumer<Chat> change) {
        Chat c = chats.get(chatId);
        if (c != null) {
            change.accept(c);
            fireChats();
        }
        return CompletableFuture.completedFuture(null);
    }

    // ---------------------------------------------------------------- media

    @Override
    public CompletableFuture<java.util.Optional<app.unichat.model.LinkMedia>> linkMedia(String url) {
        return CompletableFuture.completedFuture(java.util.Optional.ofNullable(demoLinks.get(url)));
    }

    /** The demo opens nothing on the web: these are the links it pretends hold media. */
    public void addLinkMedia(String url, app.unichat.model.LinkMedia media) {
        demoLinks.put(url, media);
    }

    @Override
    public CompletableFuture<Path> media(String url, int width, int height) {
        if (url == null) return CompletableFuture.failedFuture(new IllegalArgumentException(I18n.t("error.noUrl")));
        if (url.startsWith("file:")) return CompletableFuture.completedFuture(Path.of(java.net.URI.create(url)));
        if (url.startsWith("demo://") || url.startsWith("mxc://demo/")) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String name = url.substring(url.indexOf("demo") + (url.startsWith("demo://") ? "demo://" : "demo/").length());
                    return name.endsWith(".ogg") ? DemoMedia.demoAudio(name) : DemoMedia.demoImage(name);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        return CompletableFuture.failedFuture(new IllegalArgumentException(I18n.t("demo.unsupportedUrl", url)));
    }

    // ---------------------------------------------------------------- accounts

    @Override
    public List<Account> accounts() {
        List<Account> out;
        synchronized (accounts) {
            out = new ArrayList<>(accounts.values());
        }
        // Snapshots of the "not working" strip: -Dunichat.demoAccounts=WHATSAPP:NEEDS_LOGIN,DISCORD:CONNECTING
        String broken = System.getProperty("unichat.demoAccounts", "");
        for (String part : broken.split(",")) {
            String[] nv = part.split(":");
            if (nv.length != 2) continue;
            Network n = Network.valueOf(nv[0].strip());
            AccountStatus st = AccountStatus.valueOf(nv[1].strip());
            out.replaceAll(acc -> acc.network() == n ? new Account(acc.id(), n, acc.label(), st, null) : acc);
        }
        return out;
    }

    @Override public List<Network> availableNetworks() { return AVAILABLE; }

    @Override
    public CompletableFuture<String> connectNetwork(Network network) {
        boolean hasAccount;
        synchronized (accounts) {
            hasAccount = accounts.values().stream().anyMatch(a -> a.network() == network);
        }
        return hasAccount ? CompletableFuture.completedFuture(botChat(network).id()) : addAccount(network);
    }

    @Override
    public CompletableFuture<String> addAccount(Network network) {
        Chat chat = botChat(network);
        synchronized (accounts) {
            accounts.put(pendingId(network), new Account(pendingId(network), network, I18n.t("demo.newAccount"), AccountStatus.NEEDS_LOGIN, null));
        }
        answerBridgeBot(chat, "login");
        listeners.forEach(Listener::accountsChanged);
        return CompletableFuture.completedFuture(chat.id());
    }

    @Override
    public CompletableFuture<Void> disconnectNetwork(Network network) {
        synchronized (accounts) {
            accounts.values().removeIf(a -> a.network() == network);
        }
        chats.values().removeIf(c -> c.network() == network);
        listeners.forEach(Listener::accountsChanged);
        fireChats();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> disconnectAccount(Account account) {
        boolean last;
        synchronized (accounts) {
            accounts.remove(account.id());
            last = accounts.values().stream().noneMatch(a -> a.network() == account.network());
        }
        chats.values().removeIf(c -> account.id().equals(c.accountId()) || (last && c.network() == account.network() && !c.bridgeBot()));
        listeners.forEach(Listener::accountsChanged);
        fireChats();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> cancelLogin(Network network, String botChatId) {
        synchronized (accounts) {
            accounts.remove(pendingId(network));
        }
        Chat chat = chats.get(botChatId);
        if (chat != null) incoming(chat, botMember(network), "Login cancelled.", false);
        listeners.forEach(Listener::accountsChanged);
        return CompletableFuture.completedFuture(null);
    }

    private static String pendingId(Network n) {
        return n.protocolId() + ":pending";
    }

    private Chat botChat(Network network) {
        String id = "!bot-" + network.name().toLowerCase() + ":demo";
        return chats.computeIfAbsent(id, k -> {
            Chat bot = new Chat(k);
            bot.setNetwork(network);
            bot.setTitle(network.displayName() + " bridge bot");
            bot.setBridgeBot(true);
            bot.setMembers(List.of(botMember(network)));
            adopt(bot);
            messages.put(k, new ArrayList<>());
            return bot;
        });
    }

    private Member botMember(Network n) {
        return new Member("@" + n.botLocalpart() + ":demo.unichat", n.displayName() + " bridge bot", null);
    }

    private void answerBridgeBot(Chat chat, String command) {
        Network n = chat.network();
        Member bot = botMember(n);
        scheduler().schedule(() -> {
            if (command.equalsIgnoreCase("login")) {
                Message qr = new Message(UUID.randomUUID().toString(), chat.id(), bot.id(), bot.name(), false, Instant.now());
                qr.setType(MessageType.IMAGE);
                qr.setMediaUrl("demo://codigo-QR-" + n.protocolId());
                qr.setFileName("qr.png");
                qr.setSize(640, 420);
                append(chat, qr);
                incoming(chat, bot, "Scan the QR code with the " + n.displayName() + " mobile app to log in.", false);
                scheduler().schedule(() -> answerBridgeBot(chat, "ok"), 6, TimeUnit.SECONDS); // demo: simulated scan
            } else if (command.equalsIgnoreCase("ok")) {
                incoming(chat, bot, "Successfully logged in as demo_" + n.protocolId(), false);
                synchronized (accounts) {
                    accounts.remove(pendingId(n));
                    long k = accounts.values().stream().filter(a -> a.network() == n).count() + 1;
                    String id = n.protocolId() + ":demo" + k;
                    accounts.put(id, new Account(id, n, "demo_" + n.protocolId() + "_" + k, AccountStatus.CONNECTED, null));
                }
                listeners.forEach(Listener::accountsChanged);
            } else if (command.equalsIgnoreCase("logout")) {
                incoming(chat, bot, I18n.t("demo.loggedOut"), false);
                synchronized (accounts) {
                    accounts.values().removeIf(a -> a.network() == n);
                }
                listeners.forEach(Listener::accountsChanged);
            } else {
                incoming(chat, bot, I18n.t("demo.unknownCommand"), false);
            }
        }, 900, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- simulation

    private ScheduledExecutorService scheduler() {
        if (scheduler == null) start();
        return scheduler;
    }

    private Message outgoing(String chatId) {
        Message m = new Message("local-" + UUID.randomUUID(), chatId, ME, I18n.t("demo.me"), true, Instant.now());
        m.setStatus(MessageStatus.SENDING);
        return m;
    }

    /** Demo pauses run at once (in the same order): tests wait for what they bring, not for them. */
    private volatile boolean noPauses;

    public void skipDemoPauses() { noPauses = true; }

    /** {@code r} after the demo's pause of {@code ms}, or right away (queued in order) with {@link #skipDemoPauses}. */
    private void after(long ms, Runnable r) {
        scheduler().schedule(r, noPauses ? 0 : ms, TimeUnit.MILLISECONDS);
    }

    /** SENDING -> SENT -> DELIVERED -> READ, like a real network. */
    private void deliver(Chat chat, Message m) {
        after(300, () -> setStatus(chat, m, MessageStatus.SENT));
        if (chat.bridgeBot()) return;
        after(1200, () -> setStatus(chat, m, MessageStatus.DELIVERED));
        after(4000 + random.nextInt(4000), () -> setStatus(chat, m, MessageStatus.READ));
    }

    private void setStatus(Chat chat, Message m, MessageStatus status) {
        if (m.status() == MessageStatus.FAILED) return;
        m.setStatus(status);
        fireMessages(chat.id());
    }

    private void replyLater(Chat chat, int seconds) {
        List<Member> others = chat.members().stream().filter(mb -> !mb.id().equals(ME)).toList();
        if (others.isEmpty()) return;
        Member who = others.get(random.nextInt(others.size()));
        ScheduledExecutorService s = scheduler();
        s.schedule(() -> setTyping(chat, who, true), 1, TimeUnit.SECONDS);
        s.schedule(() -> {
            setTyping(chat, who, false);
            incoming(chat, who, autoReply(random.nextInt(AUTO_REPLY_COUNT)), true);
        }, seconds, TimeUnit.SECONDS);
    }

    protected void setTyping(Chat chat, Member who, boolean typing) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>(chat.typing());
        if (typing) names.add(who.displayName()); else names.remove(who.displayName());
        chat.setTyping(names);
        fireChats();
        fireMessages(chat.id());
    }

    private void simulateIncoming() {
        List<Chat> candidates = chats.values().stream()
                .filter(c -> !c.bridgeBot() && !c.archived() && c.members().size() > 1).toList();
        if (candidates.isEmpty()) return;
        replyLater(candidates.get(random.nextInt(candidates.size())), 4);
    }

    private void checkReminders() {
        Instant now = Instant.now();
        for (Chat c : chats.values()) {
            if (c.reminderAt() != null && !c.reminderAt().isAfter(now)) {
                c.setReminderAt(null);
                c.setMarkedUnread(true);
                listeners.forEach(l -> l.reminderDue(c));
                fireChats();
            }
        }
    }

    protected void incoming(Chat chat, Member from, String text, boolean countUnread) {
        Message m = new Message(UUID.randomUUID().toString(), chat.id(), from.id(), from.displayName(), false, Instant.now());
        m.setBody(text);
        append(chat, m);
        if (countUnread) chat.setUnreadCount(chat.unreadCount() + 1);
        fireChats();
        if (!chat.muted()) listeners.forEach(l -> l.incomingMessage(chat, m));
    }

    protected void append(Chat chat, Message m) {
        List<Message> list = messages.computeIfAbsent(chat.id(), k -> new ArrayList<>());
        synchronized (list) {
            list.add(m);
        }
        chat.applyLastMessage(m);
        if (chat.archived() && !m.outgoing() && !chat.muted()) chat.setArchived(false);
        fireMessages(chat.id());
        fireChats();
    }

    private void refreshPreview(Chat chat) {
        List<Message> list = messages(chat.id());
        if (!list.isEmpty()) {
            Instant keep = chat.lastActivity();
            chat.applyLastMessage(list.get(list.size() - 1));
            chat.setLastActivity(keep);
        }
        fireChats();
    }

    private Message find(String chatId, String messageId) {
        for (Message m : messages(chatId)) if (m.id().equals(messageId)) return m;
        return null;
    }

    private void fireChats() { listeners.forEach(Listener::chatsChanged); }

    @Override public byte[] preferencesJson() { return prefs.json(); }

    @Override public boolean adoptPreferences(byte[] json) {
        if (!prefs.replace(json)) return false;
        chats.values().forEach(this::adopt);
        fireChats();
        return true;
    }
    protected void fireMessages(String chatId) { listeners.forEach(l -> l.messagesChanged(chatId)); }
}

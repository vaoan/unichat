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
import app.unichat.model.Network;
import app.unichat.model.VoiceNote;
import app.unichat.settings.Settings;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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

/**
 * Offline demo backend: a believable multi-network inbox that lives in memory. Incoming messages,
 * typing and read receipts are simulated so every UI state can be exercised without a server.
 */
public final class MockChatService implements ChatService {
    static final String ME = "@yo:demo.unichat";
    static final String WA_PERSONAL = "whatsapp:personal";
    static final String WA_WORK = "whatsapp:trabajo";
    private static final List<Network> AVAILABLE = List.of(Network.WHATSAPP, Network.TELEGRAM, Network.SIGNAL,
            Network.INSTAGRAM, Network.MESSENGER, Network.DISCORD, Network.SLACK, Network.GOOGLE_MESSAGES,
            Network.X, Network.LINKEDIN);
    private static final String[] AUTO_REPLIES = {
            "Jaja de acuerdo", "Dale, te aviso", "¿Viste lo que pasó hoy?", "Perfecto 👌", "Ahora no puedo, luego te escribo",
            "Listo!", "Me parece bien", "😂😂", "¿A qué hora nos vemos?", "Gracias!"
    };

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    final Map<String, Chat> chats = new ConcurrentHashMap<>();
    final Map<String, List<Message>> messages = new ConcurrentHashMap<>();
    /** Account id -> account, in connection order. */
    final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Random random = new Random(7);
    private ScheduledExecutorService scheduler;
    private volatile ConnectionState state = ConnectionState.CONNECTING;

    public MockChatService() {
        DemoChats.seed(this);
    }

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
        scheduler.schedule(() -> setState(ConnectionState.ONLINE), 400, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::simulateIncoming, 25, 40, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::checkReminders, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
    }

    @Override
    public CompletableFuture<Void> logout() {
        stop();
        setState(ConnectionState.LOGGED_OUT);
        return CompletableFuture.completedFuture(null);
    }

    @Override public ConnectionState connectionState() { return state; }
    @Override public String myUserId() { return ME; }
    @Override public String myDisplayName() { return "Yo"; }

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
                DemoChats.ChatBuilder.group(this, "srv-anuncios", Network.DISCORD, "#anuncios", "Mafe")
                        .in(-100, "Mafe", "Nuevo evento el sábado").done();
                fireChats();
            }
        };
        // Like a real server: the join answers at once, the room reaches sync a bit later on a slow bridge.
        if (bridgeDelayMs <= 0) arrive.run();
        else CompletableFuture.delayedExecutor(bridgeDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS).execute(arrive);
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
                ? List.of(new GroupOffer(OFFERED_GUILD_ID, "Servidor de juegos")) : List.of());
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
            space.setTitle("Servidor de juegos");
            space.setSpace(true);
            space.setSpaceChildren(List.of("!juegos-general:demo"));
            chats.put(space.id(), space);
            DemoChats.ChatBuilder.group(this, "juegos-general", Network.DISCORD, "#general", "xNico")
                    .in(-100, "xNico", "¿Quién juega hoy?").done();
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
        return CompletableFuture.completedFuture("!servidor:demo".equals(spaceId) ? Map.of(UNJOINED_CHANNEL_ID, "#anuncios") : Map.of());
    }

    private static final List<Contact> DEMO_CONTACTS = List.of(
            new Contact("c-mama", "Mamá", List.of("tel:+573001110001"), null, "!mama:demo"),
            new Contact("c-ana", "Ana Restrepo", List.of("tel:+573001110002"), null, null),
            new Contact("c-anamaria", "Ana María", List.of("tel:+573001110003"), null, null),
            new Contact("c-carlos", "Carlos Peña", List.of("tel:+573001110004"), null, null),
            new Contact("c-dentista", "Consultorio dental", List.of("tel:+573001110005"), null, null),
            new Contact("c-pedro", "Pedro", List.of("tel:+573001110006"), null, null));

    private volatile long bridgeDelayMs;

    /** Demo/tests: the contacts API answers after {@code ms} and new DMs arrive that much later, like a real bridge. */
    public void simulateSlowBridge(long ms) {
        bridgeDelayMs = ms;
    }

    private <T> CompletableFuture<T> later(java.util.function.Supplier<T> s) {
        if (bridgeDelayMs <= 0) return CompletableFuture.completedFuture(s.get());
        return CompletableFuture.supplyAsync(s, CompletableFuture.delayedExecutor(bridgeDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS));
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
        return CompletableFuture.completedFuture(account.network() == Network.WHATSAPP ? DEMO_CONTACTS : List.of());
    }

    @Override
    public CompletableFuture<List<Contact>> searchContacts(Account account, String query) {
        if (account.network() != Network.WHATSAPP && account.network() != Network.TELEGRAM
                && account.network() != Network.INSTAGRAM && account.network() != Network.MESSENGER) return CompletableFuture.failedFuture(new UnsupportedOperationException("search"));
        String q = query.toLowerCase(java.util.Locale.ROOT);
        return CompletableFuture.completedFuture(DEMO_CONTACTS.stream()
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
        if (bridgeDelayMs <= 0) create.run();
        else CompletableFuture.delayedExecutor(bridgeDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS).execute(create);
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
        Chat chat = chats.get(chatId);
        if (chat == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Chat no encontrado"));
        Message m = outgoing(chatId);
        m.setBody(text);
        if (text.startsWith("/me ")) {
            m.setType(MessageType.EMOTE);
            m.setBody(text.substring(4));
        }
        m.setReplyToId(replyToId);
        append(chat, m);
        deliver(chat, m);
        if (chat.bridgeBot()) answerBridgeBot(chat, text.trim());
        else if (random.nextInt(3) == 0) replyLater(chat, 3 + random.nextInt(4));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item) {
        Chat chat = chats.get(chatId);
        if (chat == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Chat no encontrado"));
        Message m = outgoing(chatId);
        m.setType(MessageType.STICKER);
        m.setMediaUrl(item.url());
        m.setMimeType(item.mime());
        m.setSize(item.width(), item.height());
        m.setMediaInfo(item.info());
        append(chat, m);
        deliver(chat, m);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendVoice(String chatId, VoiceNote note, String replyToId) {
        Chat chat = chats.get(chatId);
        if (chat == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Chat no encontrado"));
        Message m = outgoing(chatId);
        m.setType(MessageType.AUDIO);
        m.setFileName(note.file().getFileName().toString());
        m.setMimeType("audio/ogg");
        m.setMediaUrl(note.file().toUri().toString());
        m.setReplyToId(replyToId);
        m.setMediaInfo(com.fasterxml.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("duration", note.durationMs()));
        append(chat, m);
        deliver(chat, m);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendFile(String chatId, Path file, String replyToId) {
        Chat chat = chats.get(chatId);
        if (chat == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Chat no encontrado"));
        Message m = outgoing(chatId);
        String name = file.getFileName().toString();
        m.setFileName(name);
        m.setBody(name);
        m.setMediaUrl(file.toUri().toString());
        m.setReplyToId(replyToId);
        try {
            m.setFileSize(Files.size(file));
            String mime = Files.probeContentType(file);
            if (mime != null && mime.startsWith("image/")) {
                m.setType(MessageType.IMAGE);
                BufferedImage img = ImageIO.read(file.toFile());
                if (img != null) m.setSize(img.getWidth(), img.getHeight());
            } else if (mime != null && mime.startsWith("video/")) {
                m.setType(MessageType.VIDEO);
            } else if (mime != null && mime.startsWith("audio/")) {
                m.setType(MessageType.AUDIO);
            } else {
                m.setType(MessageType.FILE);
            }
        } catch (IOException e) {
            m.setType(MessageType.FILE);
        }
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
            m.setDeleted(true);
            m.setBody("");
            m.reactions().clear();
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
    @Override public CompletableFuture<Void> setFavorite(String id, boolean v) { return update(id, c -> c.setFavorite(v)); }
    @Override public CompletableFuture<Void> setLowPriority(String id, boolean v) { return update(id, c -> c.setLowPriority(v)); }
    @Override public CompletableFuture<Void> setArchived(String id, boolean v) { return quiet(id, v); }
    @Override public CompletableFuture<Void> setMuted(String id, boolean v) { return quiet(id, v); }

    /** Muted = archived ({@link QuietRule}). */
    private CompletableFuture<Void> quiet(String id, boolean v) {
        for (String t : QuietRule.targets(this, id)) {
            Chat c = chats.get(t);
            if (c == null) continue;
            c.setArchived(v);
            c.setMuted(v);
        }
        fireChats();
        return CompletableFuture.completedFuture(null);
    }
    @Override public CompletableFuture<Void> setPinned(String id, boolean v) { return update(id, c -> c.setPinned(v)); }
    @Override public CompletableFuture<Void> setReminder(String id, Instant at) { return update(id, c -> c.setReminderAt(at)); }

    @Override
    public CompletableFuture<Void> leaveChat(String chatId) {
        chats.remove(chatId);
        messages.remove(chatId);
        fireChats();
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> update(String chatId, java.util.function.Consumer<Chat> change) {
        Chat c = chats.get(chatId);
        if (c != null) {
            change.accept(c);
            fireChats();
        }
        return CompletableFuture.completedFuture(null);
    }

    // ---------------------------------------------------------------- media

    @Override
    public CompletableFuture<Path> media(String url, int width, int height) {
        if (url == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Sin URL"));
        if (url.startsWith("file:")) return CompletableFuture.completedFuture(Path.of(java.net.URI.create(url)));
        if (url.startsWith("demo://")) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String name = url.substring("demo://".length());
                    return name.endsWith(".ogg") ? demoAudio(name) : demoImage(name);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        return CompletableFuture.failedFuture(new IllegalArgumentException("URL no soportada en demo: " + url));
    }

    /** The demo voice note (a 2 s tone, no speech), copied out of the app's resources. */
    private Path demoAudio(String name) throws IOException {
        Path dir = Settings.get().cacheDir().resolve("demo");
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        if (Files.exists(file)) return file;
        try (var in = MockChatService.class.getResourceAsStream("/app/unichat/demo/" + name)) {
            if (in == null) throw new IOException("Falta el audio de demostración " + name);
            Files.copy(in, file);
        }
        return file;
    }

    /** Renders a placeholder "photo" (gradient + caption) once and caches it. */
    private Path demoImage(String name) throws IOException {
        Path dir = Settings.get().cacheDir().resolve("demo");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".png");
        if (Files.exists(file)) return file;
        int w = 640, h = 420;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int seed = Math.abs(name.hashCode());
        Color a = Color.getHSBColor((seed % 360) / 360f, 0.55f, 0.95f);
        Color b = Color.getHSBColor(((seed / 7) % 360) / 360f, 0.65f, 0.55f);
        g.setPaint(new GradientPaint(0, 0, a, w, h, b));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 60));
        for (int i = 0; i < 6; i++) {
            int r = 60 + (seed >> i) % 120;
            g.fillOval((seed >> (i + 2)) % w, (seed >> (i + 4)) % h, r, r);
        }
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
        g.drawString(name.replace('-', ' '), 32, h - 36);
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
        return file;
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
            accounts.put(pendingId(network), new Account(pendingId(network), network, "Nueva cuenta", AccountStatus.NEEDS_LOGIN, null));
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
            bot.members().add(botMember(network));
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
                incoming(chat, bot, "Sesión cerrada.", false);
                synchronized (accounts) {
                    accounts.values().removeIf(a -> a.network() == n);
                }
                listeners.forEach(Listener::accountsChanged);
            } else {
                incoming(chat, bot, "Comando desconocido. Comandos: login, logout, help.", false);
            }
        }, 900, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- simulation

    private ScheduledExecutorService scheduler() {
        if (scheduler == null) start();
        return scheduler;
    }

    private Message outgoing(String chatId) {
        Message m = new Message("local-" + UUID.randomUUID(), chatId, ME, "Yo", true, Instant.now());
        m.setStatus(MessageStatus.SENDING);
        return m;
    }

    /** SENDING -> SENT -> DELIVERED -> READ, like a real network. */
    private void deliver(Chat chat, Message m) {
        ScheduledExecutorService s = scheduler();
        s.schedule(() -> setStatus(chat, m, MessageStatus.SENT), 300, TimeUnit.MILLISECONDS);
        if (chat.bridgeBot()) return;
        s.schedule(() -> setStatus(chat, m, MessageStatus.DELIVERED), 1200, TimeUnit.MILLISECONDS);
        s.schedule(() -> setStatus(chat, m, MessageStatus.READ), 4000 + random.nextInt(4000), TimeUnit.MILLISECONDS);
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
            incoming(chat, who, AUTO_REPLIES[random.nextInt(AUTO_REPLIES.length)], true);
        }, seconds, TimeUnit.SECONDS);
    }

    private void setTyping(Chat chat, Member who, boolean typing) {
        if (typing) chat.typing().add(who.displayName()); else chat.typing().remove(who.displayName());
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

    private void incoming(Chat chat, Member from, String text, boolean countUnread) {
        Message m = new Message(UUID.randomUUID().toString(), chat.id(), from.id(), from.displayName(), false, Instant.now());
        m.setBody(text);
        append(chat, m);
        if (countUnread) chat.setUnreadCount(chat.unreadCount() + 1);
        fireChats();
        if (!chat.muted()) listeners.forEach(l -> l.incomingMessage(chat, m));
    }

    private void append(Chat chat, Message m) {
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
    private void fireMessages(String chatId) { listeners.forEach(l -> l.messagesChanged(chatId)); }

    // ---------------------------------------------------------------- seed data

}

package app.unichat.matrix;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ChatService} backed by a Matrix homeserver whose mautrix bridges provide the networks.
 * Model mutations happen under the {@link SyncProcessor} lock; listeners are called from background threads.
 * End-to-end encryption is not supported: bridges must be configured without encryption.
 */
public final class MatrixChatService implements ChatService {
    private static final String SYNC_FILTER =
            "{\"room\":{\"state\":{\"lazy_load_members\":true},\"timeline\":{\"limit\":30,\"lazy_load_members\":true}}}";
    private static final String HISTORY_FILTER = "{\"lazy_load_members\":true}";
    private static final String CLIENT = "/_matrix/client/v3";
    private static final Pattern LOGIN_ID = Pattern.compile("(?m)^\\s*[*-]\\s*`([^`\\s]+)`");
    /** One line of bridgev2 {@code list-logins}: {@code * `id` (Remote name) - `STATE`}. */
    private static final Pattern LOGIN_LINE = Pattern.compile("(?m)^\\s*[*-]\\s*`([^`\\s]+)`\\s*(?:\\(([^)\\n]*)\\))?\\s*(?:-\\s*`?([A-Z_]+)`?)?");
    private static final long LOGINS_MAX_AGE_MS = 6L * 3600 * 1000;

    /** A bridge login as reported by {@code list-logins}. */
    record Login(String id, String name, String state) {}

    /** network -> known logins (empty list = bridge says not logged in); absent = unknown. */
    private final Map<Network, List<Login>> logins = new ConcurrentHashMap<>();
    private final Set<Network> refreshingLogins = ConcurrentHashMap.newKeySet();
    /** Users whose profile was already requested (lazy-loaded members have no display name yet). */
    private final Set<String> profileRequested = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Semaphore profileSlots = new java.util.concurrent.Semaphore(6);
    private static final Pattern LOGGED_IN_AS = Pattern.compile("(?i)logged in as ([^\\n.]+)");

    private final MatrixClient client;
    private final String userId;
    private final String serverName;
    private final SyncProcessor proc;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "matrix-timer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, CompletableFuture<Path>> mediaInFlight = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> historyInFlight = new ConcurrentHashMap<>();
    private final Map<Network, Boolean> bridgeAvailable = new ConcurrentHashMap<>();
    private final Set<String> joinAttempted = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastReadSent = new ConcurrentHashMap<>();
    private final AtomicLong txnCounter = new AtomicLong();

    private volatile ConnectionState state = ConnectionState.CONNECTING;
    private volatile boolean running;
    private volatile boolean initialSyncDone;
    private volatile String nextBatch;
    private volatile String displayName;
    private volatile List<Account> lastAccounts = List.of();
    private Thread syncThread;

    private final String accessToken;
    /** Distinguishes this sign-in's cache from earlier sessions of the same user (see syncCacheFile). */
    private final String sessionKey;

    public MatrixChatService(String homeserver, String userId, String accessToken) {
        this.client = new MatrixClient(homeserver, accessToken);
        this.accessToken = accessToken;
        this.sessionKey = sha256(accessToken).substring(0, 8);
        this.userId = userId;
        this.serverName = userId.substring(userId.indexOf(':') + 1);
        this.proc = new SyncProcessor(userId);
        this.displayName = SyncProcessor.localpart(userId);
    }

    // ---------------------------------------------------------------- session

    /** Resolves .well-known for a server name or URL, logs in with password, stores the session in Settings. */
    public static CompletableFuture<MatrixChatService> login(String homeserverOrDomain, String user, String password) {
        CompletableFuture<MatrixChatService> f = new CompletableFuture<>();
        Thread.ofVirtual().name("matrix-login").start(() -> {
            try {
                f.complete(doLogin(homeserverOrDomain, user, password));
            } catch (MatrixException e) {
                f.completeExceptionally(e);
            } catch (Exception e) {
                f.completeExceptionally(new MatrixException(userMessage(e), e));
            }
        });
        return f;
    }

    /** Session from Settings, or null if none stored. */
    public static MatrixChatService fromSettings() {
        Settings s = Settings.get();
        if (s.homeserver() == null || s.userId() == null || s.accessToken() == null) return null;
        return new MatrixChatService(s.homeserver(), s.userId(), s.accessToken());
    }

    /** Short Spanish message for an error coming out of this service's futures. */
    public static String userMessage(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof MatrixException me) {
            if (me.errcode() == null) return me.getMessage();
            return switch (me.errcode()) {
                case "M_FORBIDDEN" -> "No tienes permiso para hacer esto";
                case "M_LIMIT_EXCEEDED" -> "Demasiados intentos, espera un momento";
                case "M_UNKNOWN_TOKEN" -> "La sesión expiró, vuelve a iniciar sesión";
                case "M_USER_DEACTIVATED" -> "La cuenta está desactivada";
                case "M_TOO_LARGE" -> "El archivo es demasiado grande para el servidor";
                case "M_NOT_FOUND" -> "No encontrado en el servidor";
                default -> "Error del servidor: " + me.getMessage();
            };
        }
        if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                || t instanceof java.net.UnknownHostException || t instanceof java.nio.channels.UnresolvedAddressException) {
            return "No se pudo conectar con el servidor";
        }
        if (t instanceof IOException) return "Error de red: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        return t.getMessage() == null ? "Error inesperado" : t.getMessage();
    }

    private static MatrixChatService doLogin(String homeserverOrDomain, String user, String password) throws IOException {
        String u = user == null ? "" : user.trim();
        String hs = homeserverOrDomain == null ? "" : homeserverOrDomain.trim();
        if (hs.isEmpty() && u.startsWith("@") && u.contains(":")) hs = u.substring(u.indexOf(':') + 1);
        if (hs.isEmpty() || u.isEmpty()) throw new MatrixException(0, null, "Indica el servidor y el usuario");
        HttpClient http = MatrixClient.newHttpClient();
        String base = resolveHomeserver(http, hs);

        ObjectNode body = MatrixClient.JSON.createObjectNode();
        body.put("type", "m.login.password");
        body.putObject("identifier").put("type", "m.id.user").put("user", u);
        body.put("password", password == null ? "" : password);
        body.put("initial_device_display_name", "Unichat");
        JsonNode resp;
        try {
            resp = new MatrixClient(http, base, null).post(CLIENT + "/login", body);
        } catch (MatrixException e) {
            String msg = switch (e.errcode() == null ? "" : e.errcode()) {
                case "M_FORBIDDEN" -> "Usuario o contraseña incorrectos";
                case "M_USER_DEACTIVATED" -> "La cuenta está desactivada";
                case "M_LIMIT_EXCEEDED" -> "Demasiados intentos, espera un momento";
                case "M_UNKNOWN", "M_BAD_JSON", "M_INVALID_USERNAME" -> "Usuario no válido";
                default -> "No se pudo iniciar sesión: " + e.getMessage();
            };
            throw new MatrixException(e.status(), e.errcode(), msg);
        }
        String token = resp.path("access_token").asText(null);
        String uid = resp.path("user_id").asText(null);
        if (token == null || uid == null) throw new MatrixException(0, null, "Respuesta de inicio de sesión no válida");
        String wkBase = resp.path("well_known").path("m.homeserver").path("base_url").asText(null);
        if (wkBase != null && !wkBase.isBlank()) base = MatrixClient.stripSlash(wkBase);
        dropSyncCaches();
        Settings.get().setMatrixSession(base, uid, token, resp.path("device_id").asText(null));
        return new MatrixChatService(base, uid, token);
    }

    /** Finds the client-server base URL for a domain or URL via .well-known, verifying /versions. */
    static String resolveHomeserver(HttpClient http, String input) {
        String in = MatrixClient.stripSlash(input);
        List<String> candidates = new ArrayList<>();
        if (in.startsWith("http://") || in.startsWith("https://")) {
            candidates.add(in);
        } else {
            candidates.add("https://" + in);
            if (isLocalHost(in)) candidates.add("http://" + in);
        }
        for (String c : candidates) {
            MatrixClient anon = new MatrixClient(http, c, null);
            try {
                String wk = anon.get("/.well-known/matrix/client").path("m.homeserver").path("base_url").asText(null);
                if (wk != null && !wk.isBlank() && isHomeserver(http, wk)) return MatrixClient.stripSlash(wk);
            } catch (IOException | RuntimeException ignored) {
                // no .well-known: try the URL itself
            }
            if (isHomeserver(http, c)) return c;
        }
        throw new MatrixException(0, null, "No se pudo conectar con el servidor");
    }

    private static boolean isHomeserver(HttpClient http, String base) {
        try {
            return new MatrixClient(http, base, null).get("/_matrix/client/versions").has("versions");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isLocalHost(String hostPort) {
        String h = hostPort.split("[:/]")[0].toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")
                || h.endsWith(".local") || h.endsWith(".lan") || h.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    // ---------------------------------------------------------------- lifecycle

    @Override public void addListener(Listener listener) { listeners.add(listener); }
    @Override public void removeListener(Listener listener) { listeners.remove(listener); }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        loadCachedLogins();
        syncThread = new Thread(this::syncLoop, "matrix-sync");
        syncThread.setDaemon(true);
        syncThread.start();
        io.execute(this::loadProfile);
        io.execute(this::checkBridges);
        timer.scheduleWithFixedDelay(this::checkReminders, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
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
                Files.deleteIfExists(syncCacheFile());
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
                fire(Listener::accountsChanged);
            }
        } catch (Exception ignored) {
            // keep localpart
        }
    }

    // ---------------------------------------------------------------- sync loop

    /** Last full /sync response for this account; shown instantly on the next start (like Element's cache). */
    Path syncCacheFile() {
        // Per session, not only per user: two servers can share the name and user (Docker and the local server are
        // both "localhost" / @yo:localhost), and a new session must never show another one's chats.
        return Settings.get().cacheDir().resolve("sync-" + sha256(serverName + "|" + userId).substring(0, 16) + "-" + sessionKey + ".json");
    }

    /** Removes every session's cached sync (called on sign-in: the new session builds its own). */
    static void dropSyncCaches() {
        try (var files = Files.list(Settings.get().cacheDir())) {
            for (Path f : files.filter(x -> x.getFileName().toString().matches("sync-.*\\.json")).toList()) Files.deleteIfExists(f);
        } catch (IOException ignored) {
            // No cache dir yet.
        }
    }

    /** Shows the cached snapshot right away; the network sync then only fetches what changed since. */
    private boolean loadSyncCache() {
        Path f = syncCacheFile();
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

    /**
     * Deep copy of a /sync response with bridge-bot room message content blanked, so login credentials
     * (cookies, tokens, QR data) sent in those DMs never reach the on-disk cache. Input is not mutated.
     */
    static JsonNode redactForCache(JsonNode fullSync, Set<String> botRoomIds) {
        JsonNode copy = fullSync.deepCopy();
        for (String section : new String[]{"join", "invite", "leave"}) {
            JsonNode sec = copy.path("rooms").path(section);
            for (String id : botRoomIds) {
                JsonNode room = sec.path(id);
                if (!room.isObject()) continue;
                blank(room.path("timeline").path("events"));
                blank(room.path("state").path("events"));
                blank(room.path("invite_state").path("events"));
            }
        }
        return copy;
    }

    /** Blanks message-type events (no state_key); state events keep their content so the room still resolves. */
    private static void blank(JsonNode events) {
        if (!events.isArray()) return;
        for (JsonNode ev : events) {
            if (!(ev instanceof ObjectNode o)) continue;
            if (o.has("state_key")) continue;
            o.set("content", MatrixClient.JSON.createObjectNode());
        }
    }

    private void saveSyncCache(JsonNode fullSync) {
        try {
            Path f = syncCacheFile();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Set<String> botRoomIds = new java.util.HashSet<>();
            synchronized (proc) {
                for (MatrixRoom r : proc.rooms()) if (r.chat.bridgeBot()) botRoomIds.add(r.id);
            }
            MatrixClient.JSON.writeValue(tmp.toFile(), redactForCache(fullSync, botRoomIds));
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save sync cache: " + e);
        }
    }

    /** Replaces the snapshot with a fresh full sync, in the background, so catch-ups stay small. */
    private void refreshSyncCache() {
        io.execute(() -> {
            try {
                Thread.sleep(30_000);
                if (!running) return;
                String path = CLIENT + "/sync?filter=" + enc(SYNC_FILTER) + "&timeout=0";
                saveSyncCache(client.request("GET", path, null, Duration.ofSeconds(300)));
            } catch (Exception e) {
                System.err.println("Sync cache refresh failed: " + e);
            }
        });
    }

    private void syncLoop() {
        int failures = 0;
        setState(ConnectionState.CONNECTING);
        boolean catchUp = loadSyncCache();
        while (running) {
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
                if (!running) return;
                // Missed messages during the catch-up don't notify, like the initial sync.
                boolean live = initialSyncDone && !catchUp;
                SyncProcessor.Result r;
                synchronized (proc) {
                    r = proc.process(resp, live);
                }
                nextBatch = resp.path("next_batch").asText(null);
                if (initial) saveSyncCache(resp);
                else if (catchUp) refreshSyncCache();
                boolean firstRound = !initialSyncDone;
                catchUp = false;
                initialSyncDone = true;
                failures = 0;
                setState(ConnectionState.ONLINE);
                dispatch(r, live && !firstRound);
                if (firstRound) checkReminders();
            } catch (MatrixException e) {
                if ("M_UNKNOWN_TOKEN".equals(e.errcode())) {
                    running = false;
                    Settings.get().clearSession();
                    setState(ConnectionState.LOGGED_OUT);
                    return;
                }
                failures = backoff(++failures, e);
            } catch (InterruptedIOException e) {
                if (!running) return;
                failures = backoff(++failures, e);
            } catch (Exception e) {
                failures = backoff(++failures, e);
            }
        }
    }

    private int backoff(int failures, Exception e) {
        if (!running) return failures;
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
        if (r.chatsChanged) fire(Listener::chatsChanged);
        for (String id : r.messagesChanged) fire(l -> l.messagesChanged(id));
        if (notify) {
            for (Message m : r.newMessages) {
                Chat c = chat(m.chatId());
                if (c != null && !c.muted() && !c.bridgeBot()) fire(l -> l.incomingMessage(c, m));
            }
        }
        for (String roomId : r.invites) autoJoin(roomId);
        if (notify) {
            for (Message m : r.newMessages) {
                Chat c = chat(m.chatId());
                if (c != null && c.bridgeBot() && loginChanged(m.body())) refreshLogins(c.network());
            }
        } else {
            refreshStaleLogins();
        }
        fetchMissingProfiles();
        List<Account> acc = accounts();
        if (!acc.equals(lastAccounts)) {
            lastAccounts = acc;
            fire(Listener::accountsChanged);
        }
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
                    if (!changed.isEmpty()) fire(Listener::chatsChanged);
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
        fire(l -> l.connectionChanged(s));
    }

    private void fire(java.util.function.Consumer<Listener> call) {
        for (Listener l : listeners) {
            try {
                call.accept(l);
            } catch (RuntimeException e) {
                System.err.println("Listener failed: " + e);
            }
        }
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
    public Chat chat(String chatId) {
        synchronized (proc) {
            MatrixRoom r = proc.room(chatId);
            return r == null ? null : r.chat;
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

    private String newTxnId() {
        return "uc" + System.currentTimeMillis() + "." + txnCounter.incrementAndGet();
    }

    @Override
    public CompletableFuture<Void> sendText(String chatId, String text, String replyToId) {
        if (text == null || text.isBlank()) return CompletableFuture.completedFuture(null);
        String txn = newTxnId();
        ObjectNode content = MatrixClient.JSON.createObjectNode();
        boolean emote = text.startsWith("/me ");
        String body = emote ? text.substring(4) : text;
        content.put("msgtype", emote ? "m.emote" : "m.text");
        content.put("body", body);
        addReply(chatId, content, replyToId);
        synchronized (proc) {
            proc.addLocalEcho(chatId, txn, m -> {
                m.setType(emote ? MessageType.EMOTE : MessageType.TEXT);
                m.setBody(body);
                m.setReplyToId(replyToId);
            });
        }
        fireLocal(chatId);
        return sendEvent(chatId, "m.room.message", txn, content);
    }

    /** Adds m.relates_to and the plain-text reply fallback. */
    private void addReply(String chatId, ObjectNode content, String replyToId) {
        if (replyToId == null || replyToId.startsWith(SyncProcessor.LOCAL_PREFIX)) return;
        content.putObject("m.relates_to").putObject("m.in_reply_to").put("event_id", replyToId);
        Message orig;
        synchronized (proc) {
            MatrixRoom r = proc.room(chatId);
            orig = r == null ? null : r.byId.get(replyToId);
        }
        if (orig == null) return;
        String quoted = orig.isMedia() ? orig.preview() : orig.body();
        StringBuilder sb = new StringBuilder();
        String[] lines = quoted.split("\n");
        for (int i = 0; i < lines.length; i++) {
            sb.append("> ");
            if (i == 0) sb.append('<').append(orig.senderId()).append("> ");
            sb.append(lines[i]).append('\n');
        }
        sb.append('\n').append(content.path("body").asText());
        content.put("body", sb.toString());
    }

    private CompletableFuture<Void> sendEvent(String chatId, String type, String txn, ObjectNode content) {
        return CompletableFuture.runAsync(() -> {
            try {
                JsonNode resp = client.put(CLIENT + "/rooms/" + enc(chatId) + "/send/" + enc(type) + "/" + enc(txn), content);
                synchronized (proc) {
                    proc.confirmLocalEcho(chatId, txn, resp.path("event_id").asText(null));
                }
                fireLocal(chatId);
            } catch (Exception e) {
                synchronized (proc) {
                    proc.failLocalEcho(chatId, txn);
                }
                fireLocal(chatId);
                throw new CompletionException(e);
            }
        }, io);
    }

    private void fireLocal(String chatId) {
        fire(l -> l.messagesChanged(chatId));
        fire(Listener::chatsChanged);
    }

    @Override
    public CompletableFuture<Void> sendFile(String chatId, Path file, String replyToId) {
        String txn = newTxnId();
        String name = file.getFileName().toString();
        String mime = probeMime(file);
        MessageType type = mime.startsWith("image/") ? MessageType.IMAGE
                : mime.startsWith("video/") ? MessageType.VIDEO
                : mime.startsWith("audio/") ? MessageType.AUDIO : MessageType.FILE;
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new MatrixException(0, null, "No se pudo leer el archivo"));
        }
        int[] wh = type == MessageType.IMAGE ? imageSize(file) : new int[]{0, 0};
        synchronized (proc) {
            proc.addLocalEcho(chatId, txn, m -> {
                m.setType(type);
                m.setMediaUrl(file.toUri().toString());
                m.setFileName(name);
                m.setFileSize(size);
                m.setSize(wh[0], wh[1]);
                m.setReplyToId(replyToId);
            });
        }
        fireLocal(chatId);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.upload(file, mime, name);
            } catch (Exception e) {
                synchronized (proc) {
                    proc.failLocalEcho(chatId, txn);
                }
                fireLocal(chatId);
                throw new CompletionException(e);
            }
        }, io).thenCompose(uri -> {
            ObjectNode content = MatrixClient.JSON.createObjectNode();
            content.put("msgtype", switch (type) {
                case IMAGE -> "m.image";
                case VIDEO -> "m.video";
                case AUDIO -> "m.audio";
                default -> "m.file";
            });
            content.put("body", name);
            content.put("filename", name);
            content.put("url", uri);
            ObjectNode info = content.putObject("info");
            info.put("mimetype", mime);
            info.put("size", size);
            if (wh[0] > 0) {
                info.put("w", wh[0]);
                info.put("h", wh[1]);
            }
            addReply(chatId, content, replyToId);
            return sendEvent(chatId, "m.room.message", txn, content);
        });
    }

    private static String probeMime(Path file) {
        try {
            String m = Files.probeContentType(file);
            if (m != null) return m;
        } catch (IOException ignored) {
            // fall through
        }
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static int[] imageSize(Path file) {
        try {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img != null) return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception ignored) {
            // unsupported format
        }
        return new int[]{0, 0};
    }

    @Override
    public CompletableFuture<Void> editMessage(String chatId, String messageId, String newText) {
        if (messageId.startsWith(SyncProcessor.LOCAL_PREFIX)) return notSentYet();
        Message target;
        synchronized (proc) {
            MatrixRoom r = proc.room(chatId);
            target = r == null ? null : r.byId.get(messageId);
            if (target != null) {
                if (!target.isMedia()) target.setBody(newText);
                target.setEdited(true);
            }
        }
        fireLocal(chatId);
        ObjectNode content = MatrixClient.JSON.createObjectNode();
        String msgtype = target != null && target.type() == MessageType.EMOTE ? "m.emote" : "m.text";
        content.put("msgtype", msgtype);
        content.put("body", "* " + newText);
        content.putObject("m.new_content").put("msgtype", msgtype).put("body", newText);
        content.putObject("m.relates_to").put("rel_type", "m.replace").put("event_id", messageId);
        return run(() -> client.put(CLIENT + "/rooms/" + enc(chatId) + "/send/m.room.message/" + enc(newTxnId()), content));
    }

    @Override
    public CompletableFuture<Void> deleteMessage(String chatId, String messageId) {
        if (messageId.startsWith(SyncProcessor.LOCAL_PREFIX)) {
            synchronized (proc) {
                proc.removeLocal(chatId, messageId);
            }
            fireLocal(chatId);
            return CompletableFuture.completedFuture(null);
        }
        return run(() -> {
            redactEvent(chatId, messageId);
            synchronized (proc) {
                MatrixRoom r = proc.room(chatId);
                if (r != null) proc.redact(r, messageId, new SyncProcessor.Result());
            }
            fireLocal(chatId);
        });
    }

    private void redactEvent(String chatId, String eventId) throws IOException {
        client.put(CLIENT + "/rooms/" + enc(chatId) + "/redact/" + enc(eventId) + "/" + enc(newTxnId()), null);
    }

    @Override
    public CompletableFuture<Void> toggleReaction(String chatId, String messageId, String emoji) {
        if (messageId.startsWith(SyncProcessor.LOCAL_PREFIX)) return notSentYet();
        return run(() -> {
            String own;
            synchronized (proc) {
                own = proc.ownReactionEvent(chatId, messageId, emoji);
            }
            if (own != null) {
                redactEvent(chatId, own);
                synchronized (proc) {
                    MatrixRoom r = proc.room(chatId);
                    if (r != null) proc.redact(r, own, new SyncProcessor.Result());
                }
            } else {
                ObjectNode content = MatrixClient.JSON.createObjectNode();
                content.putObject("m.relates_to").put("rel_type", "m.annotation").put("event_id", messageId).put("key", emoji);
                JsonNode resp = client.put(CLIENT + "/rooms/" + enc(chatId) + "/send/m.reaction/" + enc(newTxnId()), content);
                synchronized (proc) {
                    proc.registerOwnReaction(chatId, resp.path("event_id").asText(null), messageId, emoji);
                }
            }
            fire(l -> l.messagesChanged(chatId));
        });
    }

    private static CompletableFuture<Void> notSentYet() {
        return CompletableFuture.failedFuture(new MatrixException(0, null, "El mensaje aún no se ha enviado"));
    }

    @Override
    public void sendTyping(String chatId, boolean typing) {
        if (!Settings.get().sendTypingNotifications()) return;
        ObjectNode body = MatrixClient.JSON.createObjectNode().put("typing", typing);
        if (typing) body.put("timeout", 5000);
        io.execute(() -> {
            try {
                client.put(CLIENT + "/rooms/" + enc(chatId) + "/typing/" + enc(userId), body);
            } catch (Exception ignored) {
                // best effort
            }
        });
    }

    // ---------------------------------------------------------------- chat state

    /**
     * Clears unread state and moves the read marker. Sends a public m.read receipt only when
     * {@link Settings#sendReadReceipts()} is on; otherwise a private receipt (m.read.private).
     */
    @Override
    public void markRead(String chatId) {
        String last;
        boolean wasMarked;
        synchronized (proc) {
            MatrixRoom r = proc.room(chatId);
            if (r == null) return;
            last = proc.lastEventId(chatId);
            wasMarked = r.chat.markedUnread();
            boolean wasUnread = r.chat.unreadCount() > 0 || r.chat.mentionCount() > 0;
            r.chat.setUnreadCount(0);
            r.chat.setMentionCount(0);
            r.chat.setMarkedUnread(false);
            if (!wasMarked && !wasUnread && last != null && last.equals(lastReadSent.get(chatId))) return;
        }
        fire(Listener::chatsChanged);
        io.execute(() -> {
            try {
                if (last != null) {
                    ObjectNode body = MatrixClient.JSON.createObjectNode().put("m.fully_read", last);
                    body.put(Settings.get().sendReadReceipts() ? "m.read" : "m.read.private", last);
                    client.post(CLIENT + "/rooms/" + enc(chatId) + "/read_markers", body);
                    lastReadSent.put(chatId, last);
                }
                if (wasMarked) putRoomAccountData(chatId, "m.marked_unread", MatrixClient.JSON.createObjectNode().put("unread", false));
            } catch (Exception e) {
                System.err.println("markRead failed: " + e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> setMarkedUnread(String chatId, boolean unread) {
        return optimistic(chatId, Chat::markedUnread, Chat::setMarkedUnread, unread,
                () -> putRoomAccountData(chatId, "m.marked_unread", MatrixClient.JSON.createObjectNode().put("unread", unread)));
    }

    @Override
    public CompletableFuture<Void> setFavorite(String chatId, boolean favorite) {
        return optimistic(chatId, Chat::favorite, Chat::setFavorite, favorite, () -> setTag(chatId, "m.favourite", favorite));
    }

    @Override
    public CompletableFuture<Void> setLowPriority(String chatId, boolean lowPriority) {
        return optimistic(chatId, Chat::lowPriority, Chat::setLowPriority, lowPriority,
                () -> setTag(chatId, "m.lowpriority", lowPriority));
    }

    @Override
    public CompletableFuture<Void> setArchived(String chatId, boolean archived) {
        return optimistic(chatId, Chat::archived, Chat::setArchived, archived,
                () -> setTag(chatId, SyncProcessor.TAG_ARCHIVED, archived));
    }

    @Override
    public CompletableFuture<Void> setPinned(String chatId, boolean pinned) {
        return optimistic(chatId, Chat::pinned, Chat::setPinned, pinned, () -> setTag(chatId, SyncProcessor.TAG_PINNED, pinned));
    }

    @Override
    public CompletableFuture<Void> setMuted(String chatId, boolean muted) {
        boolean old;
        synchronized (proc) {
            Chat c = chat(chatId);
            old = c != null && c.muted();
            proc.setMutedLocal(chatId, muted);
        }
        fire(Listener::chatsChanged);
        return run(() -> {
            String rule = CLIENT + "/pushrules/global/room/" + enc(chatId);
            try {
                if (muted) {
                    ObjectNode body = MatrixClient.JSON.createObjectNode();
                    body.putArray("actions");
                    client.put(rule, body);
                } else {
                    deleteIgnoringMissing(rule);
                    deleteIgnoringMissing(CLIENT + "/pushrules/global/override/" + enc(chatId));
                }
            } catch (Exception e) {
                synchronized (proc) {
                    proc.setMutedLocal(chatId, old);
                }
                fire(Listener::chatsChanged);
                throw e;
            }
        });
    }

    private void deleteIgnoringMissing(String path) throws IOException {
        try {
            client.delete(path);
        } catch (MatrixException e) {
            if (e.status() != 404) throw e;
        }
    }

    @Override
    public CompletableFuture<Void> setReminder(String chatId, Instant at) {
        ObjectNode body = MatrixClient.JSON.createObjectNode();
        if (at != null) body.put("ts", at.toEpochMilli());
        return optimistic(chatId, Chat::reminderAt, Chat::setReminderAt, at,
                () -> putRoomAccountData(chatId, SyncProcessor.REMINDER_TYPE, body));
    }

    @Override
    public CompletableFuture<Void> leaveChat(String chatId) {
        return run(() -> {
            client.post(CLIENT + "/rooms/" + enc(chatId) + "/leave", null);
            try {
                client.post(CLIENT + "/rooms/" + enc(chatId) + "/forget", null);
            } catch (Exception ignored) {
                // forgetting is optional
            }
            synchronized (proc) {
                proc.removeRoom(chatId);
            }
            fire(Listener::chatsChanged);
            fire(Listener::accountsChanged);
        });
    }

    private void setTag(String chatId, String tag, boolean on) throws IOException {
        String path = CLIENT + "/user/" + enc(userId) + "/rooms/" + enc(chatId) + "/tags/" + enc(tag);
        if (on) client.put(path, MatrixClient.JSON.createObjectNode().put("order", 0.5));
        else deleteIgnoringMissing(path);
    }

    private void putRoomAccountData(String chatId, String type, ObjectNode content) throws IOException {
        client.put(CLIENT + "/user/" + enc(userId) + "/rooms/" + enc(chatId) + "/account_data/" + enc(type), content);
    }

    private interface IoAction { void run() throws Exception; }

    private CompletableFuture<Void> run(IoAction action) {
        return CompletableFuture.runAsync(() -> {
            try {
                action.run();
            } catch (CompletionException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, io);
    }

    /** Applies a chat field change locally, sends it, and reverts on failure. */
    private <T> CompletableFuture<Void> optimistic(String chatId, java.util.function.Function<Chat, T> getter,
                                                   java.util.function.BiConsumer<Chat, T> setter, T value, IoAction call) {
        Chat c = chat(chatId);
        if (c == null) return CompletableFuture.failedFuture(new MatrixException(0, null, "Chat no encontrado"));
        T old;
        synchronized (proc) {
            old = getter.apply(c);
            setter.accept(c, value);
        }
        fire(Listener::chatsChanged);
        return run(() -> {
            try {
                call.run();
            } catch (Exception e) {
                synchronized (proc) {
                    setter.accept(c, old);
                }
                fire(Listener::chatsChanged);
                throw e;
            }
        });
    }

    private void checkReminders() {
        if (!initialSyncDone) return;
        Instant now = Instant.now();
        List<Chat> due = new ArrayList<>();
        synchronized (proc) {
            for (MatrixRoom r : proc.rooms()) {
                Instant at = r.chat.reminderAt();
                if (at != null && !at.isAfter(now)) due.add(r.chat);
            }
        }
        for (Chat c : due) {
            setReminder(c.id(), null).exceptionally(e -> null);
            fire(l -> l.reminderDue(c));
        }
    }

    // ---------------------------------------------------------------- media

    @Override
    public CompletableFuture<Path> media(String url, int width, int height) {
        if (url == null || url.isBlank()) return CompletableFuture.failedFuture(new MatrixException(0, null, "Sin URL"));
        if (url.startsWith("file:")) return CompletableFuture.completedFuture(Path.of(URI.create(url)));
        String key = url + "|" + Math.max(0, width) + "x" + Math.max(0, height);
        Path dir = Settings.get().cacheDir().resolve("media");
        String hash = sha256(key);
        Path cached = findCached(dir, hash);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        CompletableFuture<Path> f = new CompletableFuture<>();
        CompletableFuture<Path> prev = mediaInFlight.putIfAbsent(key, f);
        if (prev != null) return prev;
        io.execute(() -> {
            try {
                f.complete(download(url, width, height, dir, hash));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            } finally {
                mediaInFlight.remove(key, f);
            }
        });
        return f;
    }

    private Path download(String url, int w, int h, Path dir, String hash) throws IOException {
        Path tmp = dir.resolve(hash + ".download");
        Files.deleteIfExists(tmp);
        String contentType;
        if (url.startsWith("mxc://")) {
            String rest = url.substring("mxc://".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) throw new MatrixException(0, null, "URL de medio no válida");
            String sm = enc(rest.substring(0, slash)) + "/" + enc(rest.substring(slash + 1));
            contentType = null;
            if (w > 0 && h > 0) {
                String q = "?width=" + w + "&height=" + h + "&method=scale";
                try {
                    contentType = fetchMedia("/thumbnail/" + sm + q, tmp);
                } catch (MatrixException e) {
                    contentType = null; // not thumbnailable: fall back to the original
                }
            }
            if (contentType == null && !Files.exists(tmp)) contentType = fetchMedia("/download/" + sm, tmp);
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            contentType = client.download(url, false, tmp);
        } else {
            throw new MatrixException(0, null, "URL de medio no compatible");
        }
        Path target = dir.resolve(hash + extension(contentType));
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Authenticated media (Matrix 1.11) with fallback to the legacy unauthenticated endpoints. */
    private String fetchMedia(String suffix, Path tmp) throws IOException {
        try {
            return client.download("/_matrix/client/v1/media" + suffix, true, tmp);
        } catch (MatrixException e) {
            if (!e.isUnrecognized()) throw e;
            return client.download("/_matrix/media/v3" + suffix, true, tmp);
        }
    }

    private static Path findCached(Path dir, String hash) {
        if (!Files.isDirectory(dir)) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, hash + "*")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".download") && !n.contains(".part")) return p;
            }
        } catch (IOException ignored) {
            // treat as not cached
        }
        return null;
    }

    private static String extension(String contentType) {
        if (contentType == null) return "";
        String ct = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return switch (ct) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "audio/ogg" -> ".ogg";
            case "audio/mpeg" -> ".mp3";
            case "audio/mp4" -> ".m4a";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- bridges & accounts

    private String botId(Network n) { return "@" + n.botLocalpart() + ":" + serverName; }

    /** Probes every bridge bot's profile in parallel; a bot that exists means its bridge is installed. */
    private void checkBridges() {
        List<CompletableFuture<Void>> probes = new ArrayList<>();
        for (Network n : Network.values()) {
            if (!n.isBridged()) continue;
            probes.add(CompletableFuture.runAsync(() -> {
                try {
                    client.get(CLIENT + "/profile/" + enc(botId(n)));
                    bridgeAvailable.put(n, true);
                } catch (MatrixException e) {
                    bridgeAvailable.put(n, false);
                } catch (Exception e) {
                    // unknown (offline); retried on next start
                }
            }, io));
        }
        CompletableFuture.allOf(probes.toArray(CompletableFuture[]::new)).join();
        lastAccounts = accounts();
        fire(Listener::accountsChanged);
    }

    @Override
    public List<Network> availableNetworks() {
        List<Network> out = new ArrayList<>();
        Map<Network, Boolean> seen = new EnumMap<>(Network.class);
        synchronized (proc) {
            for (MatrixRoom r : proc.rooms()) if (r.chat.network().isBridged()) seen.put(r.chat.network(), true);
        }
        BridgeProvisioner p = provisioner;
        for (Network n : Network.values()) {
            if (!n.isBridged()) continue;
            boolean installable = p != null && p.provides(n);
            if (installable || Boolean.TRUE.equals(bridgeAvailable.get(n)) || seen.containsKey(n)) out.add(n);
        }
        return out;
    }

    @Override
    public List<Account> accounts() {
        List<Account> out = new ArrayList<>();
        synchronized (proc) {
            for (Network n : Network.values()) {
                if (!n.isBridged()) continue;
                int portals = 0;
                MatrixRoom botRoom = null;
                for (MatrixRoom r : proc.rooms()) {
                    if (r.chat.network() != n || r.invite) continue;
                    if (r.chat.bridgeBot()) botRoom = r; else portals++;
                }
                List<Login> cachedLogins = logins.get(n);
                if (cachedLogins != null && !cachedLogins.isEmpty()) {
                    // Known logins don't depend on rooms: show them even before the first sync arrives.
                    for (Login l : cachedLogins) {
                        out.add(new Account(accountId(n, l.id()), n, l.name() == null || l.name().isBlank() ? l.id() : l.name(),
                                loginStatus(l.state()), l.state()));
                    }
                    continue;
                }
                if (portals == 0 && botRoom == null) continue;
                AccountStatus status = portals > 0 ? AccountStatus.CONNECTED : AccountStatus.NEEDS_LOGIN;
                String label = n.displayName();
                if (botRoom != null) {
                    for (int i = botRoom.timeline.size() - 1; i >= 0; i--) {
                        Message m = botRoom.timeline.get(i);
                        if (m.outgoing() || m.deleted()) continue;
                        String b = m.body().toLowerCase(Locale.ROOT);
                        if (b.contains("logged out") || b.contains("not logged in") || b.contains("you're not logged")) {
                            status = AccountStatus.NEEDS_LOGIN;
                            break;
                        }
                        if (b.contains("successfully logged in") || b.contains("logged in as")) {
                            status = AccountStatus.CONNECTED;
                            Matcher mt = LOGGED_IN_AS.matcher(m.body());
                            if (mt.find()) label = mt.group(1).replace("`", "").trim();
                            break;
                        }
                    }
                }
                String detail = status == AccountStatus.CONNECTED
                        ? portals + (portals == 1 ? " chat" : " chats")
                        : "Inicia sesión desde el chat del bot";
                List<Login> known = logins.get(n);
                if (known != null && !known.isEmpty()) {
                    for (Login l : known) {
                        out.add(new Account(accountId(n, l.id()), n, l.name() == null || l.name().isBlank() ? l.id() : l.name(),
                                loginStatus(l.state()), l.state()));
                    }
                    continue;
                }
                if (known != null && portals == 0) status = AccountStatus.NEEDS_LOGIN;
                out.add(new Account(n, label, status, detail));
            }
        }
        return out;
    }

    /** Makes a network's bridge available before signing in to it (the local server downloads and starts it). */
    @FunctionalInterface
    public interface BridgeProvisioner {
        void ensure(Network network) throws Exception;

        /** Whether this provisioner can install {@code network}'s bridge (it's offered in Cuentas before it exists). */
        default boolean provides(Network network) { return true; }

        /** Base URL of the bridge's own login API ({@code .../_matrix/provision}), or null when unreachable. */
        default String provisioningUrl(Network network) { return null; }
    }

    /**
     * The bridge's own login API for {@code n} (dynamic login: the bridge says which methods and steps it has),
     * or null when only the bot chat can be used (external servers). Call {@link #prepareBridge} first.
     */
    public app.unichat.login.BridgeLogin bridgeLogin(Network n) {
        BridgeProvisioner p = provisioner;
        String url = p == null ? null : p.provisioningUrl(n);
        return url == null ? null : new app.unichat.login.BridgeLogin(url, accessToken, userId);
    }

    /** Installs and starts {@code n}'s bridge when the local server provides it. Blocking. */
    public void prepareBridge(Network n) throws Exception {
        BridgeProvisioner p = provisioner;
        if (p != null) p.ensure(n);
    }

    /** After a login through the bridge's API: refresh that network's accounts. */
    public void loginFinished(Network n) {
        refreshLogins(n);
    }

    private static volatile BridgeProvisioner provisioner;

    public static void setBridgeProvisioner(BridgeProvisioner p) {
        provisioner = p;
    }

    @Override
    public CompletableFuture<String> connectNetwork(Network network) {
        if (!network.isBridged()) return CompletableFuture.failedFuture(new MatrixException(0, null, "Red no compatible"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                BridgeProvisioner p = provisioner;
                if (p != null) {
                    try {
                        p.ensure(network);
                    } catch (MatrixException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new MatrixException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
                    }
                }
                String bot = botId(network);
                String roomId = findBotRoom(network);
                if (roomId == null) {
                    ObjectNode body = MatrixClient.JSON.createObjectNode();
                    body.put("is_direct", true);
                    body.put("preset", "trusted_private_chat");
                    body.putArray("invite").add(bot);
                    roomId = client.post(CLIENT + "/createRoom", body).path("room_id").asText(null);
                    if (roomId == null) throw new MatrixException(0, null, "No se pudo crear el chat con el bot");
                    addDirect(bot, roomId);
                    waitForJoin(roomId, bot);
                }
                sendCommand(roomId, botCommand(network, loginCommand(network)));
                waitForRoom(roomId);
                return roomId;
            } catch (MatrixException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(new MatrixException(userMessage(e), e));
            }
        }, io);
    }

    /**
     * Prefixes a bot command with {@code !<protocol id>} (e.g. {@code !whatsapp login qr}); server/setup.* sets that
     * prefix on every bridge. Without it a bridge ignores commands in any bot DM other than its management room.
     */
    static String botCommand(Network network, String command) {
        return "!" + network.protocolId() + " " + command;
    }

    /**
     * First command sent to a bridge bot to start logging in. Bridges with several login flows answer a bare
     * {@code login} with a list of flow ids, so pick the one that suits a desktop user (checked against the
     * bridge versions installed by server/setup.*; see docs/BRIDGES.md).
     */
    static String loginCommand(Network network) {
        return switch (network) {
            case WHATSAPP -> "login qr";              // flows: qr, phone
            case MESSENGER -> "login facebook";       // flows: facebook, messenger, messenger-lite, ...
            case SLACK -> "login email";              // flows: email, token, app
            case X, LINKEDIN -> "login cookies";      // flows: cookies, password
            case TELEGRAM -> "login qr";              // flows: qr, phone, bot
            // Single flow (Signal QR, Google Messages "google", Instagram cookies) or legacy bridge (Discord QR).
            default -> "login";
        };
    }

    @Override
    public CompletableFuture<Void> disconnectNetwork(Network network) {
        return run(() -> {
            String roomId = findBotRoom(network);
            if (roomId == null) return;
            int before = botMessageCount(roomId);
            sendCommand(roomId, botCommand(network, "list-logins"));
            String reply = null;
            for (int i = 0; i < 20 && reply == null; i++) {
                Thread.sleep(500);
                reply = botReplyAfter(roomId, before);
            }
            List<String> ids = new ArrayList<>();
            if (reply != null) {
                Matcher m = LOGIN_ID.matcher(reply);
                while (m.find()) ids.add(m.group(1));
            }
            if (ids.isEmpty()) sendCommand(roomId, botCommand(network, "logout"));
            for (String id : ids) sendCommand(roomId, botCommand(network, "logout " + id));
            Thread.sleep(1500);
            refreshLoginsNow(network);
        });
    }

    @Override
    public CompletableFuture<Void> disconnectAccount(Account account) {
        String prefix = account.network().protocolId() + ":";
        if (!account.id().startsWith(prefix)) return disconnectNetwork(account.network());
        String loginId = account.id().substring(prefix.length());
        return run(() -> {
            String roomId = findBotRoom(account.network());
            if (roomId == null) return;
            sendCommand(roomId, botCommand(account.network(), "logout " + loginId));
            Thread.sleep(1500);
            refreshLoginsNow(account.network());
        });
    }

    static String accountId(Network n, String loginId) {
        return n.protocolId() + ":" + loginId;
    }

    @Override
    public CompletableFuture<Void> cancelLogin(Network network, String botChatId) {
        return run(() -> sendCommand(botChatId, botCommand(network, "cancel")));
    }

    static AccountStatus loginStatus(String state) {
        if (state == null) return AccountStatus.CONNECTED;
        return switch (state) {
            case "CONNECTED", "BACKFILLING" -> AccountStatus.CONNECTED;
            case "CONNECTING", "TRANSIENT_DISCONNECT", "STARTING" -> AccountStatus.CONNECTING;
            case "BAD_CREDENTIALS", "LOGGED_OUT" -> AccountStatus.NEEDS_LOGIN;
            default -> state.startsWith("UNKNOWN") || state.contains("ERROR") ? AccountStatus.ERROR : AccountStatus.CONNECTED;
        };
    }

    /** Parses a {@code list-logins} reply; empty when the bot says we're not logged in. */
    static List<Login> parseLogins(String reply) {
        List<Login> out = new ArrayList<>();
        if (reply == null) return out;
        Matcher m = LOGIN_LINE.matcher(reply);
        while (m.find()) out.add(new Login(m.group(1), m.group(2) == null ? null : m.group(2).trim(), m.group(3)));
        return out;
    }

    /** Bot messages after which the list of logins may have changed. */
    static boolean loginChanged(String body) {
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return b.contains("successfully logged in") || b.contains("logged out") || b.contains("logged in as");
    }

    private void refreshLogins(Network n) {
        if (!refreshingLogins.add(n)) return;
        io.execute(() -> {
            try {
                Thread.sleep(1000);
                refreshLoginsNow(n);
            } catch (Exception e) {
                System.err.println("list-logins failed for " + n + ": " + e);
            } finally {
                refreshingLogins.remove(n);
            }
        });
    }

    /** Sends {@code list-logins} to the bridge bot and caches the answer (also in Settings, to skip it next start). */
    private void refreshLoginsNow(Network n) throws IOException, InterruptedException {
        List<Login> parsed;
        app.unichat.login.BridgeLogin api = bridgeLogin(n);
        if (api != null) {
            // The bridge's own API (local server): no bot chat needed, and it carries each account's state.
            parsed = new ArrayList<>();
            for (app.unichat.login.BridgeLogin.Account a : api.accounts()) parsed.add(new Login(a.id(), a.name(), a.state()));
        } else {
            String roomId = findBotRoom(n);
            if (roomId == null) return;
            int before = botMessageCount(roomId);
            sendCommand(roomId, botCommand(n, "list-logins"));
            String reply = null;
            for (int i = 0; i < 20 && reply == null; i++) {
                Thread.sleep(500);
                reply = botReplyAfter(roomId, before);
            }
            if (reply == null) return;
            parsed = parseLogins(reply);
        }
        logins.put(n, parsed);
        StringBuilder sb = new StringBuilder();
        for (Login l : parsed) {
            sb.append(l.id()).append('\t').append(l.name() == null ? "" : l.name()).append('\t')
                    .append(l.state() == null ? "" : l.state()).append('\n');
        }
        Settings.get().set("matrix.logins." + n.protocolId(), System.currentTimeMillis() + "\n" + sb);
        loginsCachedAt.put(n, System.currentTimeMillis());
        lastAccounts = accounts();
        fire(Listener::accountsChanged);
    }

    /** After the first sync: load cached logins; refresh networks with no cache, no logins, or a cache over 6 h old. */
    /** Cached {@code list-logins} answers, read at start so accounts show before the first sync finishes. */
    private final Map<Network, Long> loginsCachedAt = new ConcurrentHashMap<>();

    private void loadCachedLogins() {
        for (Network n : Network.values()) {
            if (!n.isBridged()) continue;
            String cached = Settings.get().getString("matrix.logins." + n.protocolId(), null);
            if (cached == null) continue;
            String[] lines = cached.split("\n");
            long ts = 0;
            try {
                ts = Long.parseLong(lines[0]);
            } catch (NumberFormatException ignored) {
                // treated as stale
            }
            List<Login> list = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String[] f = lines[i].split("\t", -1);
                if (f.length >= 1 && !f[0].isBlank()) {
                    list.add(new Login(f[0], f.length > 1 && !f[1].isEmpty() ? f[1] : null, f.length > 2 && !f[2].isEmpty() ? f[2] : null));
                }
            }
            logins.put(n, list);
            loginsCachedAt.put(n, ts);
        }
    }

    /** After the first sync: re-check networks with no cache, no logins, or a cache over 6 h old. */
    private void refreshStaleLogins() {
        for (Network n : Network.values()) {
            if (!n.isBridged() || findBotRoom(n) == null) continue;
            List<Login> known = logins.get(n);
            long ts = loginsCachedAt.getOrDefault(n, 0L);
            // An empty cache ("not logged in") is always re-checked: a login may have happened since.
            if (known == null || known.isEmpty() || System.currentTimeMillis() - ts > LOGINS_MAX_AGE_MS) refreshLogins(n);
        }
    }

    private String findBotRoom(Network n) {
        synchronized (proc) {
            for (MatrixRoom r : proc.rooms()) {
                if (r.chat.bridgeBot() && r.chat.network() == n && !r.invite) return r.id;
            }
        }
        return null;
    }

    private int botMessageCount(String roomId) {
        synchronized (proc) {
            MatrixRoom r = proc.room(roomId);
            return r == null ? 0 : (int) r.timeline.stream().filter(m -> !m.outgoing()).count();
        }
    }

    private String botReplyAfter(String roomId, int before) {
        synchronized (proc) {
            MatrixRoom r = proc.room(roomId);
            if (r == null) return null;
            List<Message> incoming = r.timeline.stream().filter(m -> !m.outgoing()).toList();
            return incoming.size() > before ? incoming.get(incoming.size() - 1).body() : null;
        }
    }

    private void sendCommand(String roomId, String text) throws IOException {
        String txn = newTxnId();
        ObjectNode content = MatrixClient.JSON.createObjectNode().put("msgtype", "m.text").put("body", text);
        boolean known;
        synchronized (proc) {
            known = proc.room(roomId) != null;
        }
        if (known) {
            sendText(roomId, text, null).join();
        } else {
            client.put(CLIENT + "/rooms/" + enc(roomId) + "/send/m.room.message/" + enc(txn), content);
        }
    }

    private void addDirect(String otherUser, String roomId) throws IOException {
        ObjectNode cached;
        synchronized (proc) {
            cached = proc.directContentCopy();
        }
        String path = CLIENT + "/user/" + enc(userId) + "/account_data/m.direct";
        JsonNode server = null;
        try {
            server = client.get(path);
        } catch (Exception e) {
            // no m.direct on the server yet (404) or transient failure: fall back to the cached copy
        }
        client.put(path, mergeDirect(server, cached, otherUser, roomId));
    }

    /**
     * Merges m.direct content: union of the server's and the locally cached room lists per user, then
     * appends {@code roomId} under {@code otherUser} if absent. Never drops existing entries.
     */
    static ObjectNode mergeDirect(JsonNode serverContent, JsonNode cachedContent, String otherUser, String roomId) {
        ObjectNode out = MatrixClient.JSON.createObjectNode();
        for (JsonNode src : new JsonNode[] {serverContent, cachedContent}) {
            if (src == null || !src.isObject()) continue;
            src.fields().forEachRemaining(e -> {
                ArrayNode arr = out.has(e.getKey()) && out.get(e.getKey()) instanceof ArrayNode a
                        ? a : out.putArray(e.getKey());
                if (!e.getValue().isArray()) return;
                for (JsonNode r : e.getValue()) if (!containsText(arr, r.asText())) arr.add(r.asText());
            });
        }
        ArrayNode target = out.get(otherUser) instanceof ArrayNode a ? a : out.putArray(otherUser);
        if (!containsText(target, roomId)) target.add(roomId);
        return out;
    }

    private static boolean containsText(ArrayNode arr, String v) {
        for (JsonNode n : arr) if (v.equals(n.asText())) return true;
        return false;
    }

    private void waitForJoin(String roomId, String user) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            try {
                JsonNode m = client.get(CLIENT + "/rooms/" + enc(roomId) + "/state/m.room.member/" + enc(user));
                if ("join".equals(m.path("membership").asText())) return;
            } catch (Exception ignored) {
                // not yet
            }
            Thread.sleep(1000);
        }
    }

    private void waitForRoom(String roomId) throws InterruptedException {
        for (int i = 0; i < 20 && chat(roomId) == null; i++) Thread.sleep(250);
    }

    private static String enc(String s) { return MatrixClient.enc(s); }
}

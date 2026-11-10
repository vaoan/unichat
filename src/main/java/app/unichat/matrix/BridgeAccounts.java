package app.unichat.matrix;

import app.unichat.login.BridgeLogin;
import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.bridge.BridgePorts;
import app.unichat.bridge.BridgeStates;
import app.unichat.service.ChatService.Listener;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.unichat.matrix.BridgeProtocol.*;
import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;
import static app.unichat.matrix.MatrixLogin.userMessage;

/** The bridges and their accounts: what's available, logins, and connecting or disconnecting. */
final class BridgeAccounts {
    private final MatrixCore core;
    private final MatrixClient client;
    private final SyncProcessor proc;
    private final String userId;
    private final BotChat bots;
    private final DiscordServers discord;

    BridgeAccounts(MatrixCore core, BotChat bots, DiscordServers discord) {
        this.core = core;
        this.client = core.client;
        this.proc = core.proc;
        this.userId = core.userId;
        this.bots = bots;
        this.discord = discord;
        this.liveChanged = () -> core.fire(Listener::accountsChanged);
    }

    private static final Pattern LOGIN_ID = Pattern.compile("(?m)^\\s*[*-]\\s*`([^`\\s]+)`");

    private static final long LOGINS_MAX_AGE_MS = 6L * 3600 * 1000;

    /** network -> known logins (empty list = bridge says not logged in); absent = unknown. */
    private final Map<Network, List<Login>> logins = new ConcurrentHashMap<>();

    private final Set<Network> refreshingLogins = ConcurrentHashMap.newKeySet();


    private final Map<Network, Boolean> bridgeAvailable = new ConcurrentHashMap<>();

    private volatile List<Account> lastAccounts = List.of();

    /** The bridges' live reports: accounts are re-read from them on every change (and every 30 s, see tick). */
    private BridgeStates liveStates;

    private final Runnable liveChanged;

    void watchLiveStates() {
        BridgeProvisioner p = provisioner;
        liveStates = p == null ? null : p.states();
        if (liveStates != null) liveStates.addListener(liveChanged);
    }

    void unwatchLiveStates() {
        if (liveStates != null) liveStates.removeListener(liveChanged);
    }

    /** After a sync: tells the app only when the accounts really changed. */
    void publishIfChanged() {
        List<Account> acc = accounts();
        if (!acc.equals(lastAccounts)) {
            lastAccounts = acc;
            core.fire(Listener::accountsChanged);
        }
    }

    /** Probes every bridge bot's profile in parallel; a bot that exists means its bridge is installed. */
    void checkBridges() {
        List<CompletableFuture<Void>> probes = new ArrayList<>();
        for (Network n : Network.values()) {
            if (!n.isBridged()) continue;
            probes.add(CompletableFuture.runAsync(() -> {
                try {
                    client.get(CLIENT + "/profile/" + enc(bots.botId(n)));
                    bridgeAvailable.put(n, true);
                } catch (MatrixException e) {
                    bridgeAvailable.put(n, false);
                } catch (Exception e) {
                    // unknown (offline); retried on next start
                }
            }, core.io));
        }
        CompletableFuture.allOf(probes.toArray(CompletableFuture[]::new)).join();
        lastAccounts = accounts();
        core.fire(Listener::accountsChanged);
    }

    List<Network> availableNetworks() {
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

    private static Account accountOf(Network n, Login l) {
        return new Account(accountId(n, l.id()), n, l.name() == null || l.name().isBlank() ? l.id() : l.name(),
                loginStatus(l.state()), l.state());
    }

    List<Account> accounts() {
        List<Account> out = new ArrayList<>();
        synchronized (proc) {
            // One pass over the rooms (thousands with big Discord servers), not one per network.
            Map<Network, Integer> portalCount = new EnumMap<>(Network.class);
            Map<Network, MatrixRoom> botRooms = new EnumMap<>(Network.class);
            for (MatrixRoom r : proc.rooms()) {
                if (r.invite) continue;
                if (r.chat.bridgeBot()) botRooms.put(r.chat.network(), r);
                else portalCount.merge(r.chat.network(), 1, Integer::sum);
            }
            BridgeProvisioner prov = provisioner;
            BridgeStates live = prov == null ? null : prov.states();
            long now = BridgeStates.now();
            for (Network n : Network.values()) {
                if (!n.isBridged()) continue;
                int portals = portalCount.getOrDefault(n, 0);
                MatrixRoom botRoom = botRooms.get(n);
                List<Login> cachedLogins = logins.get(n);
                List<BridgeStates.State> reported = live == null ? List.of() : live.accounts(n.protocolId());
                if (!reported.isEmpty()) {
                    // The bridge's own live report beats anything inferred from its bot chat.
                    for (BridgeStates.State st : reported) {
                        String name = st.remoteName();
                        if (name.isBlank() && cachedLogins != null) {
                            for (Login l : cachedLogins) if (l.id().equals(st.remoteId()) && l.name() != null) name = l.name();
                        }
                        AccountStatus status = liveStatus(st.event(), st.since(), now);
                        out.add(new Account(accountId(n, st.remoteId()), n, name.isBlank() ? st.remoteId() : name,
                                status, liveDetail(status)));
                    }
                    continue;
                }
                if (cachedLogins != null && !cachedLogins.isEmpty()) {
                    // Known logins don't depend on rooms: show them even before the first sync arrives.
                    for (Login l : cachedLogins) {
                        out.add(accountOf(n, l));
                    }
                    continue;
                }
                if (portals == 0 && botRoom == null) continue;
                BotChatLogin login = inferFromBotChat(botRoom == null ? List.of() : botRoom.timeline, portals, n.displayName());
                String detail = login.status() == AccountStatus.CONNECTED
                        ? I18n.n("account.chats", portals)
                        : I18n.t("account.loginFromBot");
                AccountStatus status = cachedLogins != null && portals == 0 ? AccountStatus.NEEDS_LOGIN : login.status();
                out.add(new Account(n, login.label(), status, detail));
            }
        }
        return out;
    }

    BridgeLogin bridgeLogin(Network n) {
        BridgeProvisioner p = provisioner;
        String url = p == null ? null : p.provisioningUrl(n);
        return url == null ? null : new BridgeLogin(url, core.accessToken, userId);
    }

    void prepareBridge(Network n) throws Exception {
        BridgeProvisioner p = provisioner;
        if (p != null) p.ensure(n);
    }

    void loginFinished(Network n) {
        refreshLogins(n);
    }

    static volatile BridgeProvisioner provisioner;

    CompletableFuture<String> connectNetwork(Network network) {
        if (!network.isBridged()) return CompletableFuture.failedFuture(new MatrixException(0, null, I18n.t("error.networkUnsupported")));
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
                String roomId = bots.room(network);
                if (startsLoginByItself(network)) bots.send(roomId, botCommand(network, loginCommand(network)));
                waitForRoom(roomId);
                return roomId;
            } catch (MatrixException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(new MatrixException(userMessage(e), e));
            }
        }, core.io);
    }

    CompletableFuture<Void> disconnectNetwork(Network network) {
        return core.run(() -> {
            String roomId = bots.find(network);
            if (roomId == null) return;
            String reply = bots.ask(network, "list-logins");
            List<String> ids = new ArrayList<>();
            if (reply != null) {
                Matcher m = LOGIN_ID.matcher(reply);
                while (m.find()) ids.add(m.group(1));
            }
            if (ids.isEmpty()) bots.send(roomId, botCommand(network, "logout"));
            for (String id : ids) bots.send(roomId, botCommand(network, "logout " + id));
            Thread.sleep(1500);
            refreshLoginsNow(network);
        });
    }

    CompletableFuture<Void> disconnectAccount(Account account) {
        String prefix = account.network().protocolId() + ":";
        if (!account.id().startsWith(prefix)) return disconnectNetwork(account.network());
        String loginId = account.id().substring(prefix.length());
        return core.run(() -> {
            String roomId = bots.find(account.network());
            if (roomId == null) return;
            bots.send(roomId, botCommand(account.network(), "logout " + loginId));
            Thread.sleep(1500);
            refreshLoginsNow(account.network());
        });
    }

    CompletableFuture<Void> cancelLogin(Network network, String botChatId) {
        return core.run(() -> bots.send(botChatId, botCommand(network, "cancel")));
    }

    void refreshLogins(Network n) {
        if (!refreshingLogins.add(n)) return;
        core.io.execute(() -> {
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
        BridgeLogin api = bridgeLogin(n);
        if (api != null) {
            // The bridge's own API (local server): no bot chat needed, and it carries each account's state.
            parsed = new ArrayList<>();
            for (BridgeLogin.Account a : api.accounts()) parsed.add(new Login(a.id(), a.name(), a.state()));
        } else {
            String reply = bots.ask(n, statusCommand(n));
            if (reply == null) return;
            parsed = n == Network.DISCORD ? parseDiscordPing(reply) : parseLogins(reply);
        }
        logins.put(n, parsed);
        maybeResyncChats(n, parsed);
        discord.maybePauseArchivedGuilds(n, parsed);
        StringBuilder sb = new StringBuilder();
        for (Login l : parsed) {
            sb.append(l.id()).append('\t').append(l.name() == null ? "" : l.name()).append('\t')
                    .append(l.state() == null ? "" : l.state()).append('\n');
        }
        Settings.get().set("matrix.logins." + n.protocolId(), System.currentTimeMillis() + "\n" + sb);
        loginsCachedAt.put(n, System.currentTimeMillis());
        lastAccounts = accounts();
        core.fire(Listener::accountsChanged);
    }

    private final Map<Network, Long> loginsCachedAt = new ConcurrentHashMap<>();

    /** Cached {@code list-logins} answers, read at start so accounts show before the first sync finishes. */
    void loadCachedLogins() {
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
    void refreshStaleLogins() {
        for (Network n : Network.values()) {
            if (!n.isBridged()) continue;
            List<Login> known = logins.get(n);
            // Cached logins skip refreshLoginsNow, which also checks this; logins made through the bridge API
            // have no bot chat, so this comes before that check.
            if (known != null && !known.isEmpty()) {
                maybeResyncChats(n, known);
                discord.maybePauseArchivedGuilds(n, known);
            }
            if (bots.find(n) == null) continue;
            long ts = loginsCachedAt.getOrDefault(n, 0L);
            // An empty cache ("not logged in") is always re-checked: a login may have happened since.
            if (known == null || known.isEmpty() || System.currentTimeMillis() - ts > LOGINS_MAX_AGE_MS) refreshLogins(n);
        }
    }

    /**
     * Asks the bridge to go through your chat list again ({@code sync-chats}): Telegram only does it once, at
     * login, so chats beyond the old limit never arrive otherwise. Completes with the bridge's answer.
     */
    CompletableFuture<String> syncChats(Network n) {
        if (n != Network.TELEGRAM) return CompletableFuture.failedFuture(new UnsupportedOperationException("syncChats"));
        return core.call(() -> {
            String reply = bots.query(n, "sync-chats", java.time.Duration.ofMinutes(10));
            requireSuccess(reply);
            Settings.get().set("telegram.chatSyncLimit", Integer.toString(BridgePorts.TELEGRAM_CREATE_LIMIT));
            return reply;
        });
    }

    /** Once per limit raise, after Telegram's login is confirmed (in the background; failures retry next start). */
    private void maybeResyncChats(Network n, List<Login> parsed) {
        if (n != Network.TELEGRAM || parsed.isEmpty() || provisioner == null) return;
        if (!needsChatResync(Settings.get().getString("telegram.chatSyncLimit", null), BridgePorts.TELEGRAM_CREATE_LIMIT)) return;
        if (!resyncStarted.add(n)) return;
        resyncAttempt(n, 1);
    }

    /** Right after start the server and bridges are still settling: wait a bit, and retry a failed attempt. */
    private void resyncAttempt(Network n, int attempt) {
        CompletableFuture.delayedExecutor(attempt == 1 ? 20 : 45, java.util.concurrent.TimeUnit.SECONDS, core.io).execute(() ->
                syncChats(n).whenComplete((r, err) -> {
                    if (err == null) return;
                    Throwable root = err;
                    while (root.getCause() != null) root = root.getCause();
                    System.err.println("Telegram chat resync attempt " + attempt + " failed: " + root.getClass().getSimpleName()
                            + ": " + root.getMessage());
                    if (attempt < 3) resyncAttempt(n, attempt + 1);
                }));
    }

    private final Set<Network> resyncStarted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void waitForRoom(String roomId) throws InterruptedException {
        for (int i = 0; i < 20 && core.chat(roomId) == null; i++) Thread.sleep(250);
    }
}

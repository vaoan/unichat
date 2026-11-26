package app.unichat.service;

import app.unichat.model.Network;
import app.unichat.support.I18n;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The demo with "Load older chats" ({@code -Dunichat.demoOlderChats}, for snapshots and tests): canned plans, and a
 * canned run that goes through each stage and brings two old chats per network, read, as the real loader marks them.
 * With {@code hold} the run stays at the first network's chats arriving (a snapshot of the bar mid-run).
 */
public class DemoOlderChats extends MockChatService implements OlderChats {
    private static final List<Network> OFFERED = List.of(Network.TELEGRAM, Network.WHATSAPP, Network.DISCORD,
            Network.GOOGLE_MESSAGES, Network.INSTAGRAM);
    /** The old chats each network's step brings. */
    private static final int BROUGHT = 2;
    /** Forty days ago: history, not news. */
    private static final long OLD = -40L * 24 * 3600;

    private final boolean hold;
    private final Set<Network> done = ConcurrentHashMap.newKeySet();
    private final List<Runnable> loadingListeners = new CopyOnWriteArrayList<>();
    private final List<List<Network>> asked = new CopyOnWriteArrayList<>();
    private volatile @Nullable Loading loading;
    private @Nullable CompletableFuture<Map<Network, Answer>> run;

    /** In memory (tests). */
    public DemoOlderChats() {
        this(PreferencesFile.inMemory(), false);
    }

    public DemoOlderChats(PreferencesFile prefs, boolean hold) {
        super(prefs);
        this.hold = hold;
    }

    /** The demo of {@code -Dunichat.demoOlderChats} ({@code hold}: the run stays mid-way), else the plain one. */
    public static MockChatService of(PreferencesFile prefs) {
        String mode = System.getProperty("unichat.demoOlderChats");
        if (mode == null || "false".equals(mode)) return new MockChatService(prefs);
        return new DemoOlderChats(prefs, "hold".equals(mode));
    }

    /** Each list {@link #load} was given, in order. */
    public List<List<Network>> asked() { return List.copyOf(asked); }

    @Override public List<Plan> plans(Collection<Network> inView) {
        Set<Network> withAccount = ConcurrentHashMap.newKeySet();
        accounts().forEach(a -> withAccount.add(a.network()));
        List<Plan> out = new ArrayList<>();
        for (Network n : OFFERED) {
            if (!inView.contains(n) || !withAccount.contains(n) || done.contains(n)) continue;
            out.add(switch (n) {
                case TELEGRAM -> new Plan(n, 100, true, false, false, null);
                case WHATSAPP -> new Plan(n, -1, false, false, false, null);
                case DISCORD -> new Plan(n, 50, true, true, false, null);
                case INSTAGRAM -> new Plan(n, -1, true, false, true, null);
                default -> new Plan(n, -1, true, false, false, null);
            });
        }
        return out;
    }

    @Override public boolean hasMore(Collection<Network> inView) { return !plans(inView).isEmpty(); }

    @Override public synchronized CompletableFuture<Map<Network, Answer>> load(List<Network> networks) {
        CompletableFuture<Map<Network, Answer>> now = run;
        if (now != null && !now.isDone()) return now;
        asked.add(List.copyOf(networks));
        CompletableFuture<Map<Network, Answer>> f = new CompletableFuture<>();
        run = f;
        step(List.copyOf(networks), 0, new LinkedHashMap<>(), f);
        return f;
    }

    @Override public @Nullable Loading loading() { return loading; }
    @Override public void addLoadingListener(Runnable l) { loadingListeners.add(l); }
    @Override public void removeLoadingListener(Runnable l) { loadingListeners.remove(l); }

    /** Network {@code i}'s stages, one after the other, then the next; the answers once all ran. */
    private void step(List<Network> nets, int i, Map<Network, Answer> answers, CompletableFuture<Map<Network, Answer>> f) {
        if (i == nets.size()) {
            Map<Network, Answer> all = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(answers));
            tell(new Loading(nets.isEmpty() ? Network.TELEGRAM : nets.getLast(), Stage.DONE, 0, i, nets.size(), new LinkedHashMap<>(answers)));
            f.complete(all);
            return;
        }
        Network n = nets.get(i);
        tell(new Loading(n, n == Network.WHATSAPP ? Stage.ASKING : Stage.RESTARTING, 0, i, nets.size(), new LinkedHashMap<>(answers)));
        after(1500, () -> {
            Answer a = answer(n);
            int brought = a instanceof Answer.Loaded(int chats) ? chats : 0;
            for (int k = 1; k <= brought; k++) arrive(n, k);
            fireChats(); // as the sync tells of new rooms, read or not
            tell(new Loading(n, Stage.ARRIVING, brought, i, nets.size(), new LinkedHashMap<>(answers)));
            if (hold) return;
            after(1500, () -> {
                answers.put(n, a);
                done.add(n);
                step(nets, i + 1, answers, f);
            });
        });
    }

    /** What the demo's bridges answer: chats for most, nothing more for Google Messages, and Instagram's bridge not back. */
    private static Answer answer(Network n) {
        return switch (n) {
            case GOOGLE_MESSAGES -> new Answer.NothingMore();
            case INSTAGRAM -> new Answer.NotPossible(I18n.t("olderChats.didntComeBack"));
            default -> new Answer.Loaded(BROUGHT);
        };
    }

    /** An old chat of {@code n} arriving with its history, unread as the bridge brings it, then read, as the loader marks it. */
    private void arrive(Network n, int k) {
        String key = "older-" + n.protocolId() + "-" + k + "-" + asked.size();
        DemoChats.ChatBuilder.dm(this, key, n, I18n.t("demo.older.name", k, n.displayName()))
                .in(OLD - k * 3600L, I18n.t("demo.older.text")).unread(3).done();
        markRead("!" + key + ":demo");
    }

    private void tell(Loading l) {
        loading = l;
        loadingListeners.forEach(Runnable::run);
    }
}

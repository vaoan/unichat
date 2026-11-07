package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.support.I18n;
import javafx.application.Platform;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * What the chat list does with servers/groups beyond showing them: names of channels you aren't in (from each
 * space's hierarchy), joining one (it opens when the bridge has it ready), and the servers you're in that don't come
 * into Unichat yet (Discord), which can be added. Changes are reported through {@code onChanged} on the FX thread.
 */
final class Spaces {
    /** List ids of servers you could add ({@link ChatService#groupOffers}). */
    static final String OFFER_PREFIX = "offer:";

    /** Whether a list row is a server you could add. */
    static boolean isOffer(Chat c) {
        return c.id().startsWith(OFFER_PREFIX);
    }

    /** The {@link ChatService.GroupOffer#id} behind an offer row. */
    static String offerId(Chat c) {
        return c.id().substring(OFFER_PREFIX.length());
    }

    /** How long a joined channel may take to arrive before we stop waiting to open it (tests shorten it). */
    static long joinWaitMs = 30_000;

    private final ChatService svc;
    private final Consumer<Chat> onOpen;
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Set<String> namesAsked = new HashSet<>();
    private final Map<Network, List<ChatService.GroupOffer>> offers = new ConcurrentHashMap<>();
    private final Map<Network, Long> offersAskedAt = new ConcurrentHashMap<>();
    private final Set<Network> offersInFlight = ConcurrentHashMap.newKeySet();
    /** Servers keep arriving (Discord brings them in one by one): the list to add is re-checked this often. */
    static long offersRecheckMs = 15_000;
    private final Set<String> adding = new HashSet<>();
    private String pendingOpen;
    private javafx.animation.PauseTransition pendingTimeout;
    private Consumer<String> toast = t -> { };
    private Runnable onChanged = () -> { };

    Spaces(ChatService svc, Consumer<Chat> onOpen) {
        this.svc = svc;
        this.onOpen = onOpen;
    }

    void setToast(Consumer<String> toast) {
        this.toast = toast;
    }

    void setOnChanged(Runnable r) {
        this.onChanged = r;
    }

    /** Names of channels not joined yet (room id → name), as far as they've been asked. */
    Map<String, String> names() {
        return names;
    }

    /** Asks {@code spaceId}'s hierarchy for its channels' names once. */
    void ensureNames(String spaceId) {
        if (!namesAsked.add(spaceId)) return;
        svc.spaceNames(spaceId).thenAccept(m -> {
            names.putAll(m);
            if (!m.isEmpty()) Platform.runLater(onChanged);
        });
    }

    /** Servers of {@code n} you could add; re-checked now and then, empty until the bridge answers. */
    List<ChatService.GroupOffer> offers(Network n) {
        long now = System.currentTimeMillis();
        if (now - offersAskedAt.getOrDefault(n, 0L) >= offersRecheckMs && offersInFlight.add(n)) {
            offersAskedAt.put(n, now);
            svc.groupOffers(n).whenComplete((l, err) -> {
                offersInFlight.remove(n);
                if (err != null) {
                    System.err.println("Group offers for " + n + " failed: " + err.getClass().getSimpleName());
                    return;
                }
                Platform.runLater(() -> {
                    if (l.equals(offers.getOrDefault(n, List.of()))) return;
                    offers.put(n, l);
                    onChanged.run();
                });
            });
        }
        return offers.getOrDefault(n, List.of());
    }

    boolean adding(String offerId) {
        return adding.contains(offerId);
    }

    /** Brings a server into Unichat; a big one takes minutes (the bridge makes a room per channel). */
    void add(Network n, String offerId, String name) {
        if (!adding.add(offerId)) return;
        toast.accept(I18n.t("spaces.adding", name));
        onChanged.run();
        svc.addGroup(n, offerId).whenComplete((v, err) -> Platform.runLater(() -> {
            adding.remove(offerId);
            if (err != null) {
                toast.accept(I18n.t("spaces.addFailed", name));
            } else {
                offers.computeIfPresent(n, (k, l) -> l.stream().filter(o -> !o.id().equals(offerId)).toList());
                toast.accept(I18n.t("spaces.alreadyIn", name));
            }
            onChanged.run();
        }));
    }

    /** Opens a channel you just joined once the bridge has it ready; call on every list refresh. */
    void checkPending() {
        if (pendingOpen == null) return;
        Chat joined = svc.chat(pendingOpen);
        if (joined != null && !joined.space()) {
            pendingOpen = null;
            if (pendingTimeout != null) pendingTimeout.stop();
            onOpen.accept(joined);
        }
    }

    /** Joins a channel you aren't in, with feedback; it opens when the bridge has it (or just shows up later). */
    void join(String id, String name) {
        if (pendingOpen != null) return;
        pendingOpen = id;
        toast.accept(I18n.t("spaces.joining", name));
        svc.joinChat(id).whenComplete((v, err) -> Platform.runLater(() -> {
            if (err != null) {
                if (id.equals(pendingOpen)) pendingOpen = null;
                toast.accept(I18n.t("spaces.joinFailed", name));
                return;
            }
            onChanged.run();
        }));
        // Don't open it by surprise minutes later: after the wait it just shows up in the list.
        if (pendingTimeout != null) pendingTimeout.stop();
        pendingTimeout = new javafx.animation.PauseTransition(javafx.util.Duration.millis(joinWaitMs));
        pendingTimeout.setOnFinished(e -> {
            if (!id.equals(pendingOpen)) return;
            pendingOpen = null;
            toast.accept(I18n.t("spaces.joined", name));
        });
        pendingTimeout.play();
    }
}

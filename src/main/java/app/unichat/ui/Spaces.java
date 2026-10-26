package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
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
    /** How long a joined channel may take to arrive before we stop waiting to open it (tests shorten it). */
    static long joinWaitMs = 30_000;

    private final ChatService svc;
    private final Consumer<Chat> onOpen;
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Set<String> namesAsked = new HashSet<>();
    private final Map<Network, List<ChatService.GroupOffer>> offers = new ConcurrentHashMap<>();
    private final Set<Network> offersAsked = new HashSet<>();
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

    /** Servers of {@code n} you could add; asked to the bridge the first time, empty until it answers. */
    List<ChatService.GroupOffer> offers(Network n) {
        if (offersAsked.add(n)) {
            svc.groupOffers(n).whenComplete((l, err) -> {
                System.err.println("Group offers for " + n + ": " + (err != null ? "failed " + err.getClass().getSimpleName() : l.size()));
                if (err != null || l.isEmpty()) return;
                Platform.runLater(() -> {
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
        toast.accept("Agregando " + name + "… si es grande puede tardar unos minutos");
        onChanged.run();
        svc.addGroup(n, offerId).whenComplete((v, err) -> Platform.runLater(() -> {
            adding.remove(offerId);
            if (err != null) {
                toast.accept("No se pudo agregar " + name + ". Prueba de nuevo en un momento.");
            } else {
                offers.computeIfPresent(n, (k, l) -> l.stream().filter(o -> !o.id().equals(offerId)).toList());
                toast.accept(name + " ya está en Unichat");
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
        toast.accept("Uniéndote a " + name + "…");
        svc.joinChat(id).whenComplete((v, err) -> Platform.runLater(() -> {
            if (err != null) {
                if (id.equals(pendingOpen)) pendingOpen = null;
                toast.accept("No se pudo unir a " + name + ". Puede que ya no tengas acceso.");
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
            toast.accept("Te uniste a " + name + "; aparecerá en la lista en un momento");
        });
        pendingTimeout.play();
    }
}

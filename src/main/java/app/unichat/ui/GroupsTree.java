package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The Grupos view of the chat list: servers/groups (spaces) with their channels/topics as a tree. Enter or a click
 * opens a chat, expands a server, or joins a channel you aren't in yet (then opens it when the bridge has it ready).
 */
final class GroupsTree extends TreeView<SpaceTree.Node> {
    private final ChatService svc;
    private final Consumer<Chat> onOpen;
    /** Names of children not joined yet, from each space's hierarchy (fetched once per space). */
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Set<String> namesAsked = new HashSet<>();
    private final Set<String> expanded = new HashSet<>();
    private Network network;
    private String accountId;
    private boolean rebuilding;
    private String pendingOpen;
    private javafx.animation.PauseTransition pendingTimeout;
    private Consumer<String> toast = t -> { };
    /** How long a joined channel may take to arrive before we stop waiting to open it (tests shorten it). */
    static long joinWaitMs = 30_000;
    /** Tree ids of servers you're in that don't come into Unichat yet ({@link ChatService#groupOffers}). */
    static final String OFFER_PREFIX = "offer:";
    private String selectedId;
    /** Per network: servers you could add, asked once to the bridge. */
    private final Map<Network, List<ChatService.GroupOffer>> offers = new ConcurrentHashMap<>();
    private final Set<Network> offersAsked = new HashSet<>();
    private final Set<String> adding = new HashSet<>();
    private Runnable onOffersChanged = () -> { };

    GroupsTree(ChatService svc, Consumer<Chat> onOpen) {
        this.svc = svc;
        this.onOpen = onOpen;
        getStyleClass().add("groups-tree");
        setAccessibleText("Grupos");
        setShowRoot(false);
        setRoot(new TreeItem<>());
        setCellFactory(v -> new Cell());
        expanded.add(SpaceTree.OFFERS_ID);
        // Arrowing through the tree is remembered, so a redraw from incoming messages keeps your place.
        getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!rebuilding && b != null && b.getValue() != null) selectedId = b.getValue().id();
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                act(getSelectionModel().getSelectedItem());
                e.consume();
            }
        });
    }

    void setToast(Consumer<String> toast) {
        this.toast = toast;
    }

    /** Called when the servers you could add arrive or change (they can make the Grupos choice appear). */
    void setOnOffersChanged(Runnable r) {
        this.onOffersChanged = r;
    }

    private List<ChatService.GroupOffer> offers(Network n) {
        if (offersAsked.add(n)) {
            svc.groupOffers(n).whenComplete((l, err) -> {
                System.err.println("Group offers for " + n + ": " + (err != null ? "failed " + err.getClass().getSimpleName() : l.size()));
                if (err != null || l.isEmpty()) return;
                Platform.runLater(() -> {
                    offers.put(n, l);
                    onOffersChanged.run();
                });
            });
        }
        return offers.getOrDefault(n, List.of());
    }

    /** Whether {@code n} has any server/group (so the Bandeja | Grupos choice makes sense). */
    boolean hasGroups(Network n, String account) {
        return !offers(n).isEmpty() || SpaceTree.build(svc.chats(), n, account, names).stream().anyMatch(x -> !x.loose());
    }

    void refresh(Network n, String account) {
        this.network = n;
        this.accountId = account;
        List<Chat> chats = svc.chats();
        if (pendingOpen != null) {
            Chat joined = svc.chat(pendingOpen);
            if (joined != null && !joined.space()) {
                pendingOpen = null;
                if (pendingTimeout != null) pendingTimeout.stop();
                selectedId = joined.id();
                onOpen.accept(joined);
            }
        }
        List<SpaceTree.Node> roots = SpaceTree.build(chats, n, account, names);
        askNames(roots);
        List<SpaceTree.Node> offered = offers(n).stream()
                .map(o -> new SpaceTree.Node(OFFER_PREFIX + o.id(), null, o.name(), List.of())).toList();
        if (!offered.isEmpty()) {
            roots = new java.util.ArrayList<>(roots);
            roots.add(new SpaceTree.Node(SpaceTree.OFFERS_ID, null, "Otros servidores", offered));
        }
        TreeItem<SpaceTree.Node> selected = null;
        rebuilding = true;
        getRoot().getChildren().clear();
        for (SpaceTree.Node r : roots) {
            TreeItem<SpaceTree.Node> item = item(r);
            getRoot().getChildren().add(item);
            TreeItem<SpaceTree.Node> s = find(item, selectedId);
            if (s != null) selected = s;
        }
        if (selected != null) getSelectionModel().select(selected);
        rebuilding = false;
    }

    void setSelected(String chatId) {
        selectedId = chatId;
    }

    private TreeItem<SpaceTree.Node> item(SpaceTree.Node n) {
        TreeItem<SpaceTree.Node> item = new TreeItem<>(n);
        for (SpaceTree.Node c : n.children()) item.getChildren().add(item(c));
        item.setExpanded(expanded.contains(n.id()));
        item.expandedProperty().addListener((o, a, b) -> {
            if (b) expanded.add(n.id());
            else expanded.remove(n.id());
        });
        return item;
    }

    private static TreeItem<SpaceTree.Node> find(TreeItem<SpaceTree.Node> item, String id) {
        if (id == null) return null;
        if (id.equals(item.getValue().id())) return item;
        for (TreeItem<SpaceTree.Node> c : item.getChildren()) {
            TreeItem<SpaceTree.Node> f = find(c, id);
            if (f != null) return f;
        }
        return null;
    }

    /** Spaces with children we haven't joined: ask their hierarchy for names once, then redraw. */
    private void askNames(List<SpaceTree.Node> nodes) {
        for (SpaceTree.Node n : nodes) {
            if (n.chat() != null && n.chat().space() && namesAsked.add(n.id())
                    && n.children().stream().anyMatch(c -> !c.joined())) {
                svc.spaceNames(n.id()).thenAccept(m -> {
                    names.putAll(m);
                    if (!m.isEmpty()) Platform.runLater(() -> refresh(network, accountId));
                });
            }
            askNames(n.children());
        }
    }

    private void act(TreeItem<SpaceTree.Node> item) {
        if (item == null || item.getValue() == null) return;
        SpaceTree.Node n = item.getValue();
        if (n.id().startsWith(OFFER_PREFIX)) {
            add(n);
        } else if (n.container()) {
            item.setExpanded(!item.isExpanded());
        } else if (n.joined()) {
            selectedId = n.id();
            onOpen.accept(n.chat());
        } else if (pendingOpen == null) {
            String id = n.id(), name = n.name();
            pendingOpen = id;
            selectedId = id;
            toast.accept("Uniéndote a " + name + "…");
            svc.joinChat(id).whenComplete((v, err) -> Platform.runLater(() -> {
                if (err != null) {
                    if (id.equals(pendingOpen)) pendingOpen = null;
                    toast.accept("No se pudo unir a " + name + ". Puede que ya no tengas acceso.");
                    return;
                }
                refresh(network, accountId);
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

    /** Brings a server into Unichat; a big one takes minutes (the bridge makes a room per channel). */
    private void add(SpaceTree.Node n) {
        String id = n.id().substring(OFFER_PREFIX.length()), name = n.name();
        Network net = network;
        if (!adding.add(id)) return;
        toast.accept("Agregando " + name + "… si es grande puede tardar unos minutos");
        svc.addGroup(net, id).whenComplete((v, err) -> Platform.runLater(() -> {
            adding.remove(id);
            if (err != null) {
                toast.accept("No se pudo agregar " + name + ". Prueba de nuevo en un momento.");
                return;
            }
            offers.computeIfPresent(net, (k, l) -> l.stream().filter(o -> !o.id().equals(id)).toList());
            toast.accept(name + " ya está en Unichat");
            onOffersChanged.run();
        }));
    }

    private String childWord() {
        return network == Network.DISCORD ? "canales" : network == Network.TELEGRAM ? "temas" : "chats";
    }

    private final class Cell extends TreeCell<SpaceTree.Node> {
        private final Avatar avatar = new Avatar(26);
        private final Label name = new Label();
        private final Label badge = new Label();
        private final Label join = new Label("Unirse");
        private final HBox box;

        Cell() {
            getStyleClass().add("group-cell");
            name.getStyleClass().add("group-name");
            badge.getStyleClass().add("badge");
            join.getStyleClass().add("group-join");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(8, avatar, name, spacer, join, badge);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            setOnMouseClicked(e -> {
                // The disclosure arrow toggles by itself; anywhere else on the row acts (opens, joins or expands).
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null
                        && (getDisclosureNode() == null || !getDisclosureNode().isHover())) {
                    act(getTreeItem());
                }
            });
        }

        @Override
        protected void updateItem(SpaceTree.Node n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) {
                setGraphic(null);
                setText(null);
                setAccessibleText(null);
                return;
            }
            name.setText(n.name());
            boolean container = n.container();
            boolean plain = n.loose() || n.offers();
            avatar.setVisible(!plain);
            avatar.setManaged(!plain);
            if (!plain) avatar.set(n.name(), n.id(), n.chat() == null ? null : n.chat().avatarUrl(), svc);
            int unread = SpaceTree.unread(n);
            badge.setText(unread > 99 ? "99+" : Integer.toString(unread));
            badge.setVisible(unread > 0);
            badge.setManaged(unread > 0);
            boolean offer = n.id().startsWith(OFFER_PREFIX);
            join.setText(offer ? (adding.contains(n.id().substring(OFFER_PREFIX.length())) ? "Agregando…" : "Agregar") : "Unirse");
            join.setVisible(!n.joined() && !container);
            join.setManaged(!n.joined() && !container);
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("container"), container);
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("unjoined"), !n.joined() && !container);
            setGraphic(box);
            setText(null);
            StringBuilder a = new StringBuilder(n.name());
            if (container) {
                a.append(", ").append(n.children().size()).append(' ')
                        .append(n.loose() ? "chats" : n.offers() ? "servidores" : childWord());
            }
            if (unread > 0) a.append(", ").append(unread).append(" sin leer");
            if (!n.joined() && !container) a.append(", ").append(join.getText());
            if (container) a.append(getTreeItem() != null && getTreeItem().isExpanded() ? ", desplegado" : ", contraído");
            setAccessibleText(a.toString());
        }
    }
}

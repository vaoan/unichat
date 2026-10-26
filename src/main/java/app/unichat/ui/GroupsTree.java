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
    private String pendingOpen;
    private String selectedId;

    GroupsTree(ChatService svc, Consumer<Chat> onOpen) {
        this.svc = svc;
        this.onOpen = onOpen;
        getStyleClass().add("groups-tree");
        setAccessibleText("Grupos");
        setShowRoot(false);
        setRoot(new TreeItem<>());
        setCellFactory(v -> new Cell());
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                act(getSelectionModel().getSelectedItem());
                e.consume();
            }
        });
    }

    /** Whether {@code n} has any server/group (so the Bandeja | Grupos choice makes sense). */
    boolean hasGroups(Network n) {
        return SpaceTree.build(svc.chats(), n, names).stream().anyMatch(x -> !x.loose());
    }

    void refresh(Network n) {
        this.network = n;
        List<Chat> chats = svc.chats();
        if (pendingOpen != null) {
            Chat joined = svc.chat(pendingOpen);
            if (joined != null && !joined.space()) {
                pendingOpen = null;
                selectedId = joined.id();
                onOpen.accept(joined);
            }
        }
        List<SpaceTree.Node> roots = SpaceTree.build(chats, n, names);
        askNames(roots);
        TreeItem<SpaceTree.Node> selected = null;
        getRoot().getChildren().clear();
        for (SpaceTree.Node r : roots) {
            TreeItem<SpaceTree.Node> item = item(r);
            getRoot().getChildren().add(item);
            TreeItem<SpaceTree.Node> s = find(item, selectedId);
            if (s != null) selected = s;
        }
        if (selected != null) getSelectionModel().select(selected);
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
                    if (!m.isEmpty()) Platform.runLater(() -> refresh(network));
                });
            }
            askNames(n.children());
        }
    }

    private void act(TreeItem<SpaceTree.Node> item) {
        if (item == null || item.getValue() == null) return;
        SpaceTree.Node n = item.getValue();
        if (n.container()) {
            item.setExpanded(!item.isExpanded());
        } else if (n.joined()) {
            selectedId = n.id();
            onOpen.accept(n.chat());
        } else {
            pendingOpen = n.id();
            selectedId = n.id();
            svc.joinChat(n.id()).whenComplete((v, err) -> Platform.runLater(() -> {
                if (err != null) pendingOpen = null;
                refresh(network);
            }));
        }
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
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null && !getItem().container()) {
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
            avatar.setVisible(!n.loose());
            avatar.setManaged(!n.loose());
            if (!n.loose()) avatar.set(n.name(), n.id(), n.chat() == null ? null : n.chat().avatarUrl(), svc);
            int unread = SpaceTree.unread(n);
            badge.setText(unread > 99 ? "99+" : Integer.toString(unread));
            badge.setVisible(unread > 0);
            badge.setManaged(unread > 0);
            join.setVisible(!n.joined() && !container);
            join.setManaged(!n.joined() && !container);
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("container"), container);
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("unjoined"), !n.joined() && !container);
            setGraphic(box);
            setText(null);
            StringBuilder a = new StringBuilder(n.name());
            if (container) {
                a.append(", ").append(n.children().size()).append(' ').append(n.loose() ? "chats" : childWord());
            }
            if (unread > 0) a.append(", ").append(unread).append(" sin leer");
            if (!n.joined() && !container) a.append(", Unirse");
            if (container) a.append(getTreeItem() != null && getTreeItem().isExpanded() ? ", desplegado" : ", contraído");
            setAccessibleText(a.toString());
        }
    }
}

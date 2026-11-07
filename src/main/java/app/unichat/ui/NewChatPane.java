package app.unichat.ui;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Contact;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.support.Errors;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Nuevo chat: pick a network (and account), then any contact from the bridge's contact list or search, or type a
 * phone number / @username. An existing chat opens; otherwise the bridge creates it and it opens when it arrives.
 */
final class NewChatPane extends VBox {
    private static final Duration DEBOUNCE = Duration.millis(300);
    private static final long WAIT_FOR_ROOM_MS = 30_000;

    /** A list row: a contact, or "Escribir a <what you typed>" (resolved when chosen). */
    private record Row(Contact contact, String typed) {
        String label() { return contact != null ? contact.name() : "Escribir a " + typed; }

        String detail() {
            if (contact == null) return "";
            return contact.identifiers().isEmpty() ? "" : contact.identifiers().get(0).replaceFirst("^tel:", "");
        }
    }

    private final ChatService svc;
    private final Consumer<String> openChatId;
    private final Runnable close;
    private final Consumer<String> toast;
    private final FlowPane networks = new FlowPane(6, 6);
    private final FlowPane accountRow = new FlowPane(6, 6);
    private final TextField search = new TextField();
    private final Label status = new Label();
    private final ListView<Row> list = new ListView<>();
    private final PauseTransition debounce = new PauseTransition(DEBOUNCE);
    private Account account;
    private ChatService.ContactSupport support = new ChatService.ContactSupport(false, false);
    private List<Contact> all = List.of();
    private int generation;
    /** Separate from {@code generation} (searches): a search typed while the account loads must not discard it. */
    private int loadGeneration;
    private boolean loading;
    private ChatService.Listener pendingListener;
    private PauseTransition pendingTimeout;

    NewChatPane(ChatService svc, Network preferred, Consumer<String> openChatId, Runnable close, Consumer<String> toast) {
        super(10);
        this.svc = svc;
        this.openChatId = openChatId;
        this.close = close;
        this.toast = toast;
        getStyleClass().addAll("new-chat-pane", "card");
        setMaxWidth(520);
        Label title = new Label("Nuevo chat");
        title.getStyleClass().add("settings-title");

        search.getStyleClass().addAll("search-field", "new-chat-search");
        search.setPromptText("Nombre, número o @usuario");
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        debounce.setOnFinished(e -> runQuery());
        search.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> close.run();
                case ENTER -> {
                    Row r = list.getSelectionModel().getSelectedItem();
                    if (r == null && !list.getItems().isEmpty()) r = list.getItems().get(0);
                    if (debounce.getStatus() == javafx.animation.Animation.Status.RUNNING) {
                        debounce.stop();
                        runQuery();
                        r = list.getItems().isEmpty() ? null : list.getItems().get(0);
                    }
                    if (r != null) choose(r);
                }
                case DOWN -> {
                    list.requestFocus();
                    if (list.getSelectionModel().isEmpty()) list.getSelectionModel().selectFirst();
                }
                default -> { return; }
            }
            e.consume();
        });

        status.getStyleClass().add("settings-help");
        status.setWrapText(true);
        list.getStyleClass().add("new-chat-list");
        list.setAccessibleText("Contactos");
        list.setCellFactory(v -> new Cell());
        list.setPrefHeight(320);
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && list.getSelectionModel().getSelectedItem() != null) {
                choose(list.getSelectionModel().getSelectedItem());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                close.run();
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        networks.setAccessibleText("Red");
        accountRow.setAccessibleText("Cuenta");
        getChildren().addAll(title, networks, accountRow, search, status, list);
        // Closed (Esc, another overlay): a chat still being created must not take over the screen later.
        sceneProperty().addListener((o, a, b) -> { if (b == null) cancelPending(); });

        ToggleGroup group = new ToggleGroup();
        List<Network> connected = connectedNetworks();
        ToggleButton first = null;
        for (Network n : connected) {
            ToggleButton b = new ToggleButton(n.displayName());
            b.getStyleClass().addAll("chip", "new-chat-network");
            b.setToggleGroup(group);
            b.setOnAction(e -> {
                b.setSelected(true);
                chooseNetwork(n);
            });
            networks.getChildren().add(b);
            if (first == null || n == preferred) first = b;
        }
        if (first == null) {
            setStatus("Primero conecta una red en Ajustes → Cuentas.");
            search.setDisable(true);
        } else {
            first.fire();
        }
    }

    void focus() {
        search.requestFocus();
    }

    private List<Network> connectedNetworks() {
        Set<Network> out = new LinkedHashSet<>();
        for (Account a : svc.accounts()) if (a.status() == AccountStatus.CONNECTED && a.network() != Network.MATRIX) out.add(a.network());
        return new ArrayList<>(out);
    }

    private void chooseNetwork(Network n) {
        List<Account> accounts = svc.accounts().stream().filter(a -> a.network() == n && a.status() == AccountStatus.CONNECTED).toList();
        accountRow.getChildren().clear();
        accountRow.setVisible(accounts.size() > 1);
        accountRow.setManaged(accounts.size() > 1);
        if (accounts.size() > 1) {
            ToggleGroup g = new ToggleGroup();
            for (Account a : accounts) {
                ToggleButton b = new ToggleButton(a.label());
                b.getStyleClass().addAll("chip", "new-chat-account");
                b.setToggleGroup(g);
                b.setOnAction(e -> {
                    b.setSelected(true);
                    chooseAccount(a);
                });
                accountRow.getChildren().add(b);
            }
            ((ToggleButton) accountRow.getChildren().get(0)).setSelected(true);
        }
        chooseAccount(accounts.get(0));
    }

    private void chooseAccount(Account a) {
        account = a;
        int gen = ++loadGeneration;
        ++generation;
        loading = true;
        support = new ChatService.ContactSupport(false, false);
        all = List.of();
        list.getItems().clear();
        search.setAccessibleText("Buscar contacto en " + a.network().displayName());
        search.setDisable(false);
        setStatus("Cargando…");
        ChatService.ContactSupport[] found = new ChatService.ContactSupport[1];
        svc.contactSupport(a).thenCompose(s -> {
            found[0] = s;
            return s.list() ? svc.contacts(a) : java.util.concurrent.CompletableFuture.completedFuture(List.<Contact>of());
        }).whenComplete((contacts, err) -> Platform.runLater(() -> {
            if (gen != loadGeneration) return; // another network/account was chosen meanwhile
            loading = false;
            support = found[0] != null ? found[0] : new ChatService.ContactSupport(false, false);
            all = err == null ? contacts : List.of();
            runQuery();
        }));
    }

    private static String unavailable(Network n) {
        if (n == Network.DISCORD) return "En Discord encuentras a la gente en sus servidores: usa la vista Grupos de la lista de chats.";
        return n.displayName() + " no permite buscar contactos desde Unichat. Escríbele desde tu teléfono y el chat aparecerá aquí.";
    }

    private void runQuery() {
        if (account == null) return;
        if (loading) {
            ++generation;
            show(List.of(), "Cargando…");
            return;
        }
        if (!support.list() && !support.search()) {
            ++generation;
            show(List.of(), unavailable(account.network()));
            return;
        }
        String q = search.getText().strip();
        int gen = ++generation;
        List<Row> rows = new ArrayList<>();
        if (looksLikeIdentifier(q)) rows.add(new Row(null, q));
        if (q.isEmpty()) {
            all.forEach(c -> rows.add(new Row(c, null)));
            show(rows, all.isEmpty() && support.search() ? "Escribe un nombre para buscar en " + account.network().displayName() + "." : null);
            return;
        }
        if (!support.search()) {
            filterLocally(q).forEach(c -> rows.add(new Row(c, null)));
            show(rows, rows.isEmpty() ? "Sin resultados" : null);
            return;
        }
        setStatus("Buscando…");
        svc.searchContacts(account, q).whenComplete((found, err) -> Platform.runLater(() -> {
            if (gen != generation) return;
            if (err != null) {
                Throwable cause = Errors.unwrap(err);
                if (cause instanceof UnsupportedOperationException) {
                    support = new ChatService.ContactSupport(support.list(), false);
                    filterLocally(q).forEach(c -> rows.add(new Row(c, null)));
                    show(rows, rows.isEmpty() ? account.network().displayName() + " no permite buscar contactos desde Unichat." : null);
                } else {
                    show(rows, "No se pudo buscar ahora. Intenta de nuevo.");
                }
                return;
            }
            found.forEach(c -> rows.add(new Row(c, null)));
            show(rows, rows.isEmpty() ? "Sin resultados" : null);
        }));
    }

    private List<Contact> filterLocally(String q) {
        String n = ChatFilter.normalize(q);
        return all.stream().filter(c -> ChatFilter.normalize(c.name()).contains(n)
                || c.identifiers().stream().anyMatch(i -> i.contains(q))).toList();
    }

    /** The status line, also given to the search field so a screen reader hears it where the focus is. */
    private void setStatus(String text) {
        status.setText(text);
        search.setAccessibleHelp(text == null || text.isEmpty() ? null : text);
        search.notifyAccessibleAttributeChanged(javafx.scene.AccessibleAttribute.HELP);
    }

    private void show(List<Row> rows, String message) {
        list.getItems().setAll(rows);
        setStatus(message == null ? "" : message);
        status.setVisible(message != null);
        status.setManaged(message != null);
    }

    /** What the bridge gets for a typed phone/@user: no spaces, dashes, dots or parentheses around digits. */
    static String normalizeIdentifier(String q) {
        String t = q.strip();
        return t.startsWith("@") ? t : t.replaceAll("[\\s()\\-.]", "");
    }

    /** A phone number or an @username: offer to write to it even if it's not in the list. */
    static boolean looksLikeIdentifier(String q) {
        String digits = q.replaceAll("[\\s()\\-.]", "");
        return digits.matches("\\+?\\d{7,15}") || q.matches("@[\\p{L}\\p{N}_.]{3,}");
    }

    private void choose(Row r) {
        Account a = account;
        if (a == null || pendingListener != null) return; // already opening one
        setStatus("Abriendo…");
        status.setVisible(true);
        status.setManaged(true);
        java.util.concurrent.CompletableFuture<Contact> contact = r.contact() != null
                ? java.util.concurrent.CompletableFuture.completedFuture(r.contact()) : svc.resolveContact(a, normalizeIdentifier(r.typed()));
        contact.thenCompose(c -> {
            if (c == null) throw new java.util.concurrent.CompletionException(new IllegalArgumentException("not found"));
            return svc.openDm(a, c);
        }).whenComplete((roomId, err) -> Platform.runLater(() -> {
            if (err != null) {
                Throwable cause = Errors.unwrap(err);
                setStatus(cause instanceof IllegalArgumentException
                        ? "No se encontró a " + r.typed() + " en " + a.network().displayName() + "."
                        : "No se pudo abrir el chat. Intenta de nuevo.");
                return;
            }
            if (getScene() != null) openWhenReady(roomId);
        }));
    }

    /** The bridge creates the room; it reaches Unichat through sync a moment later. */
    private void openWhenReady(String roomId) {
        if (svc.chat(roomId) != null) {
            close.run();
            openChatId.accept(roomId);
            return;
        }
        if (getScene() == null) return; // closed while the bridge answered
        setStatus("Creando el chat…");
        ChatService.Listener l = new ChatService.Listener() {
            @Override
            public void chatsChanged() {
                Platform.runLater(() -> {
                    if (pendingListener != this || svc.chat(roomId) == null) return;
                    cancelPending();
                    close.run();
                    openChatId.accept(roomId);
                });
            }
        };
        pendingListener = l;
        svc.addListener(l);
        pendingTimeout = new PauseTransition(Duration.millis(WAIT_FOR_ROOM_MS));
        pendingTimeout.setOnFinished(e -> {
            if (pendingListener != l) return;
            cancelPending();
            close.run();
            toast.accept("El chat se está creando; aparecerá en la bandeja");
        });
        pendingTimeout.play();
    }

    private void cancelPending() {
        if (pendingListener != null) svc.removeListener(pendingListener);
        pendingListener = null;
        if (pendingTimeout != null) pendingTimeout.stop();
        pendingTimeout = null;
    }

    private final class Cell extends ListCell<Row> {
        private final Avatar avatar = new Avatar(30);
        private final Label name = new Label();
        private final Label detail = new Label();
        private final HBox box;

        Cell() {
            name.getStyleClass().add("contact-name");
            detail.getStyleClass().add("contact-detail");
            box = new HBox(10, avatar, new VBox(1, name, detail));
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            getStyleClass().add("contact-row");
            setOnMouseClicked(e -> { if (!isEmpty() && getItem() != null) choose(getItem()); });
        }

        @Override
        protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            avatar.set(r.label(), r.contact() == null ? r.typed() : r.contact().id(), null, null);
            name.setText(r.label());
            detail.setText(r.detail());
            detail.setVisible(!r.detail().isEmpty());
            detail.setManaged(!r.detail().isEmpty());
            setGraphic(box);
            setAccessibleText(r.detail().isEmpty() ? r.label() : r.label() + ", " + r.detail());
        }
    }
}

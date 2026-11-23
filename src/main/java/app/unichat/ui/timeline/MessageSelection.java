package app.unichat.ui.timeline;

import app.unichat.model.Message;
import app.unichat.support.I18n;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Picking several messages to forward or delete at once: a click (or Space, Enter) picks or unpicks one, Shift+click the
 * ones in between, Esc ends. "Reenviar…" or "Eliminar varios…" in a message's menu starts it with that one, for that
 * ({@link Purpose}). While it lasts, a click on a message only picks it (no photo opens, no link), and a bar in the
 * header's buttons' place says how many and forwards or deletes them, in the chat's order, or cancels.
 */
final class MessageSelection {
    static final PseudoClass PICKED = PseudoClass.getPseudoClass("picked");

    /**
     * What picking is for: the bar's button ({@code buttonKey}, its look {@code buttonStyle}), which messages can be
     * picked, and what's done with them. {@code range}: a stretch between two markers ({@link MessageRange}) rather than
     * messages picked one by one: nothing between its ends can be left out.
     */
    record Purpose(String buttonKey, String buttonStyle, Predicate<Message> pickable, Consumer<List<Message>> act, boolean range) {
        Purpose(String buttonKey, String buttonStyle, Predicate<Message> pickable, Consumer<List<Message>> act) {
            this(buttonKey, buttonStyle, pickable, act, false);
        }
    }

    /** The two ends of a range, on its first and last rows: their ✕ takes one away. */
    static final PseudoClass RANGE_START = PseudoClass.getPseudoClass("range-start");
    static final PseudoClass RANGE_END = PseudoClass.getPseudoClass("range-end");
    private final MessageRange range = new MessageRange();
    /**
     * Under the header while picking a range: each end, said, with its ✕ (the keyboard's way to take one away). Its own
     * strip: in the bar beside the buttons it cut them all to "Ca…".
     */
    private final HBox ends = new HBox(16);

    private final ListView<ThreadRows.Row> list;
    private final Consumer<Boolean> showBar;
    private Purpose purpose;
    private final Set<String> picked = new LinkedHashSet<>();
    private final Label count = new Label();
    private final Button send = new Button();
    private final HBox bar;
    private String last;
    private boolean on;

    /** A row of the list that shows whether its message is picked (its look and what a screen reader says). */
    @FunctionalInterface
    interface Shows {
        void showPicked();
    }

    /** Keys that only move while picking; every other one would act on a message or on the hidden message box. */
    private static final Set<KeyCode> MOVING = Set.of(KeyCode.UP, KeyCode.DOWN, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN, KeyCode.HOME,
            KeyCode.END, KeyCode.TAB, KeyCode.F6);

    /** {@code showBar}: the bar in the header's buttons' place (true) or the buttons back (false). */
    MessageSelection(ListView<ThreadRows.Row> list, Consumer<Boolean> showBar) {
        this.list = list;
        this.showBar = showBar;
        count.getStyleClass().add("selection-count");
        send.setOnAction(e -> purpose.act().accept(messages()));
        Button cancel = new Button(I18n.t("select.cancel"));
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> end());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        ends.setAlignment(Pos.CENTER_LEFT);
        ends.getStyleClass().add("range-ends");
        app.unichat.ui.Nodes.show(ends, false);
        for (Button b : List.of(cancel, send)) b.setMinWidth(Region.USE_PREF_SIZE);
        count.setMinWidth(0);
        bar = new HBox(10, count, grow, cancel, send);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("selection-bar");
        for (var type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            list.addEventFilter(type, this::mouse);
        }
        // A message's menu while picking would answer, edit or start over with what's picked.
        list.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            if (on) e.consume();
        });
        // A message deleted or gone meanwhile no longer counts.
        list.getItems().addListener((javafx.collections.ListChangeListener<ThreadRows.Row>) c -> {
            if (on) sync();
        });
    }

    Node bar() { return bar; }

    /** The strip under the header that says a range's ends (shown only while picking one). */
    Node ends() { return ends; }

    boolean active() { return on; }

    boolean picked(Message m) {
        if (!on) return false;
        return purpose.range() ? inRange().contains(m.id()) : picked.contains(m.id());
    }

    /** Whether {@code m} is the range's start (true) or end, as its row shows a marker. */
    boolean rangeStart(Message m) { return on && purpose.range() && m.id().equals(range.start()); }

    boolean rangeEnd(Message m) { return on && purpose.range() && m.id().equals(range.end()); }

    /** Takes the range's start (or end) away: the range is the other alone, and the next click puts it back. */
    void removeRangeEnd(boolean start) {
        if (start) range.removeStart();
        else range.removeEnd();
        sync();
    }

    /** Starts picking for {@code why}, with {@code first} picked. */
    void start(Message first, Purpose why) {
        picked.clear();
        range.clear();
        purpose = why;
        send.setText(I18n.t(why.buttonKey()));
        send.getStyleClass().removeAll("primary-button", "danger-button");
        send.getStyleClass().add(why.buttonStyle());
        on = true;
        app.unichat.ui.Nodes.style(list, "picking", true);
        showBar.accept(true);
        toggle(first, false);
        list.requestFocus();
    }

    /** Picks or unpicks {@code m}, as a click does. */
    void toggle(Message m) { toggle(m, false); }

    void end() {
        if (!on) return;
        on = false;
        picked.clear();
        range.clear();
        last = null;
        list.getSelectionModel().clearSelection(); // the row picking moved through kept its ring, and looked still picked
        app.unichat.ui.Nodes.style(list, "picking", false);
        showBar.accept(false);
        sync();
    }

    /** The picked messages still in the chat and still fit for it (not deleted meanwhile), in the chat's order. */
    List<Message> messages() {
        if (purpose == null) return List.of();
        java.util.Collection<String> in = purpose.range() ? inRange() : picked;
        return list.getItems().stream().filter(r -> r instanceof ThreadRows.MessageRow mr && in.contains(mr.message().id()))
                .map(r -> ((ThreadRows.MessageRow) r).message()).filter(purpose.pickable()).toList();
    }

    /** The ids of the chat's messages, in its order, as the list has them. */
    private List<String> ids() {
        return list.getItems().stream().filter(r -> r instanceof ThreadRows.MessageRow)
                .map(r -> ((ThreadRows.MessageRow) r).message().id()).toList();
    }

    private Set<String> inRange() {
        return new java.util.HashSet<>(range.of(ids()));
    }

    /** The timeline's keys while picking: true when it took {@code e} (all but the ones that move). */
    boolean key(KeyEvent e, Message selected) {
        if (!on || MOVING.contains(e.getCode())) return false;
        if (e.getCode() == KeyCode.ESCAPE) end();
        else if ((e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) && selected != null) toggle(selected, e.isShiftDown());
        e.consume();
        return true;
    }

    private void mouse(MouseEvent e) {
        if (!on || e.getButton() != MouseButton.PRIMARY) return;
        if (e.getTarget() instanceof Node n && inMarker(n)) return; // its ✕ takes that end away
        ListCell<?> cell = e.getTarget() instanceof Node n ? cellOf(n) : null;
        if (cell == null || !(cell.getItem() instanceof ThreadRows.MessageRow mr)) return;
        if (e.getEventType() == MouseEvent.MOUSE_CLICKED) {
            list.requestFocus(); // the press that would have is taken: Space and Enter go on from here
            list.getSelectionModel().select(cell.getIndex());
            toggle(mr.message(), e.isShiftDown());
        }
        e.consume(); // the message's own buttons, pictures and links wait until picking ends
    }

    private static boolean inMarker(Node n) {
        for (Node at = n; at != null; at = at.getParent()) if (at.getStyleClass().contains("range-marker")) return true;
        return false;
    }

    private static ListCell<?> cellOf(Node n) {
        for (Node at = n; at != null; at = at.getParent()) if (at instanceof ListCell<?> c) return c;
        return null;
    }

    /** Picks or unpicks {@code m}; {@code range}: picks every message from the last one picked to it. */
    private void toggle(Message m, boolean range) {
        if (!purpose.pickable().test(m)) return;
        if (purpose.range()) { // a stretch: the click moves an end (or puts one back), never leaves one out
            this.range.click(m.id(), ids());
            last = m.id();
            sync();
            return;
        }
        List<Message> all = list.getItems().stream().filter(r -> r instanceof ThreadRows.MessageRow)
                .map(r -> ((ThreadRows.MessageRow) r).message()).toList();
        int from = last == null ? -1 : indexOf(all, last), to = indexOf(all, m.id());
        if (range && from >= 0 && to >= 0) {
            for (int i = Math.min(from, to); i <= Math.max(from, to); i++) if (purpose.pickable().test(all.get(i))) picked.add(all.get(i).id());
        } else if (!picked.remove(m.id())) {
            picked.add(m.id());
        }
        last = m.id();
        sync();
    }

    private static int indexOf(List<Message> all, String id) {
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(id)) return i;
        return -1;
    }

    /** In the bar, while picking a range: "Desde: …" and "Hasta: …", each with its ✕. */
    private void showEnds() {
        ends.getChildren().clear();
        boolean shown = on && purpose != null && purpose.range();
        app.unichat.ui.Nodes.show(ends, shown);
        if (!shown) return;
        for (boolean start : new boolean[] {true, false}) {
            Message m = message(start ? range.start() : range.end());
            if (m == null) continue;
            String said = I18n.t(start ? "select.rangeFrom" : "select.rangeTo", m.senderName(),
                    app.unichat.ui.Formats.time(m.timestamp(), java.time.ZoneId.systemDefault()));
            Label l = new Label(said);
            l.getStyleClass().add("range-end-label");
            Button x = app.unichat.ui.Icons.iconButton(app.unichat.ui.Icons.CLOSE, I18n.t("select.rangeRemove", said), () -> {
                removeRangeEnd(start);
                list.requestFocus(); // its button is rebuilt: the keyboard goes on from the list, not from nowhere
            });
            ends.getChildren().add(new HBox(2, l, x));
        }
    }

    private Message message(String id) {
        if (id == null) return null;
        for (ThreadRows.Row r : list.getItems()) if (r instanceof ThreadRows.MessageRow mr && mr.message().id().equals(id)) return mr.message();
        return null;
    }

    /** The bar's count and the rows on screen as picked now; the rest show it when drawn. */
    private void sync() {
        int n = messages().size();
        count.setText(I18n.n(purpose != null && purpose.range() ? "select.rangeCount" : "select.count", n));
        showEnds();
        send.setDisable(n == 0);
        for (Node row : list.lookupAll(".row-cell")) if (row instanceof Shows s) s.showPicked();
    }
}

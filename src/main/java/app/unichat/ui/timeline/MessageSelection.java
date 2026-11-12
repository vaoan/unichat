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
 * Picking several messages to forward at once: a click (or Space, Enter) picks or unpicks one, Shift+click the ones in
 * between, Esc ends. While it lasts, a click on a message only picks it (no photo opens, no link), and a bar in the
 * message box's place says how many and forwards them, in the chat's order.
 */
final class MessageSelection {
    static final PseudoClass PICKED = PseudoClass.getPseudoClass("picked");

    private final ListView<ThreadRows.Row> list;
    private final Predicate<Message> pickable;
    private final Consumer<Boolean> showBar;
    private final Set<String> picked = new LinkedHashSet<>();
    private final Label count = new Label();
    private final Button send = new Button(I18n.t("select.forward"));
    private final HBox bar;
    private String last;
    private boolean on;

    /** A row of the list that shows whether its message is picked (its look and what a screen reader says). */
    interface Shows {
        void showPicked();
    }

    /** Keys that only move while picking; every other one would act on a message or on the hidden message box. */
    private static final Set<KeyCode> MOVING = Set.of(KeyCode.UP, KeyCode.DOWN, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN, KeyCode.HOME,
            KeyCode.END, KeyCode.TAB, KeyCode.F6);

    /** {@code showBar}: the bar in the message box's place (true) or the box back (false). */
    MessageSelection(ListView<ThreadRows.Row> list, Predicate<Message> pickable, Consumer<List<Message>> forward, Consumer<Boolean> showBar) {
        this.list = list;
        this.pickable = pickable;
        this.showBar = showBar;
        count.getStyleClass().add("selection-count");
        send.getStyleClass().add("primary-button");
        send.setOnAction(e -> forward.accept(messages()));
        Button cancel = new Button(I18n.t("select.cancel"));
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> end());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
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

    boolean active() { return on; }

    boolean picked(Message m) { return on && picked.contains(m.id()); }

    /** Starts with {@code first} picked. */
    void start(Message first) {
        picked.clear();
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
        last = null;
        app.unichat.ui.Nodes.style(list, "picking", false);
        showBar.accept(false);
        sync();
    }

    /** The picked messages still in the chat and still forwardable (not deleted meanwhile), in the chat's order. */
    List<Message> messages() {
        return list.getItems().stream().filter(r -> r instanceof ThreadRows.MessageRow mr && picked.contains(mr.message().id()))
                .map(r -> ((ThreadRows.MessageRow) r).message()).filter(pickable).toList();
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
        ListCell<?> cell = e.getTarget() instanceof Node n ? cellOf(n) : null;
        if (cell == null || !(cell.getItem() instanceof ThreadRows.MessageRow mr)) return;
        if (e.getEventType() == MouseEvent.MOUSE_CLICKED) {
            list.requestFocus(); // the press that would have is taken: Space and Enter go on from here
            list.getSelectionModel().select(cell.getIndex());
            toggle(mr.message(), e.isShiftDown());
        }
        e.consume(); // the message's own buttons, pictures and links wait until picking ends
    }

    private static ListCell<?> cellOf(Node n) {
        for (Node at = n; at != null; at = at.getParent()) if (at instanceof ListCell<?> c) return c;
        return null;
    }

    /** Picks or unpicks {@code m}; {@code range}: picks every message from the last one picked to it. */
    private void toggle(Message m, boolean range) {
        if (!pickable.test(m)) return;
        List<Message> all = list.getItems().stream().filter(r -> r instanceof ThreadRows.MessageRow)
                .map(r -> ((ThreadRows.MessageRow) r).message()).toList();
        int from = last == null ? -1 : indexOf(all, last), to = indexOf(all, m.id());
        if (range && from >= 0 && to >= 0) {
            for (int i = Math.min(from, to); i <= Math.max(from, to); i++) if (pickable.test(all.get(i))) picked.add(all.get(i).id());
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

    /** The bar's count and the rows on screen as picked now; the rest show it when drawn. */
    private void sync() {
        int n = messages().size();
        count.setText(I18n.n("select.count", n));
        send.setDisable(n == 0);
        for (Node row : list.lookupAll(".row-cell")) if (row instanceof Shows s) s.showPicked();
    }
}

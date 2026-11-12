package app.unichat.ui.timeline;

import app.unichat.ui.Icons;
import app.unichat.model.Message;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

import java.util.function.BooleanSupplier;

/**
 * Where the message list is looking, decided after every layout and before the frame is drawn (a frame drawn wrong and
 * fixed in the next is what a person sees as the list jumping): following the newest message, with its end at the
 * bottom edge; or reading older ones, with the message read held on the same pixel whatever changes around it (the box
 * growing, someone typing, a message arriving, a picture loading). Only the person moves it (the wheel or trackpad, the
 * keys, the scrollbar, a jump to a quoted message).
 */
final class TimelineScroll {
    private final ListView<ThreadRows.Row> list;
    private final ObservableList<ThreadRows.Row> rows;
    private final BooleanSupplier chatOpen;
    private final Runnable atTop;
    private final FollowIndicator indicator;
    /** The newest message when the person went up to older ones: what arrives after it is counted as new below. */
    private String seenId;
    /**
     * True while the viewport follows new messages. Only the person going towards older messages (wheel or trackpad up,
     * the keys up, dragging the scrollbar) unpins it, at once; it pins again when the newest message is wholly in view,
     * measured in pixels after the layout. (A time window after the wheel and a "near the bottom" of 0.2 % of the whole
     * chat, hundreds of pixels in a long one, pulled the person back down after a small scroll up.)
     */
    private boolean pinnedBottom = true;
    /** The scrollbar is being pressed (its thumb dragged, its track clicked): where it ends up is the person's choice. */
    private boolean dragging;
    /** The person moved the list since the last layout: what's in view now is the new place to hold. */
    private boolean moved;
    /** Reading older messages: the message held in place and where its top was, in the list's own coordinates. */
    private String anchorId;
    private double anchorY;

    /** {@code atTop} runs each time the list reaches its first row. {@code owner}'s scene is watched for layouts. */
    TimelineScroll(ListView<ThreadRows.Row> list, ObservableList<ThreadRows.Row> rows, Node owner, BooleanSupplier chatOpen, Runnable atTop) {
        this.list = list;
        this.rows = rows;
        this.chatOpen = chatOpen;
        this.atTop = atTop;
        indicator = new FollowIndicator(this::scrollToBottom);
        rows.addListener((javafx.collections.ListChangeListener<ThreadRows.Row>) c -> {
            if (!pinnedBottom) indicator.show(false, newBelow());
        });
        list.addEventFilter(ScrollEvent.SCROLL, e -> {
            moved = true;
            if (e.getDeltaY() > 0) unpin(); // towards older messages
        });
        list.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            moved = true;
            switch (e.getCode()) {
                case PAGE_UP, HOME, UP -> unpin();
                default -> { }
            }
        });
        list.skinProperty().addListener((o, a, s) -> Platform.runLater(this::hookScrollBar));
        Runnable afterLayout = this::afterLayout;
        owner.sceneProperty().addListener((o, a, sc) -> {
            if (a != null) a.removePostLayoutPulseListener(afterLayout);
            if (sc != null) sc.addPostLayoutPulseListener(afterLayout);
        });
    }

    /** The indicator at the list's bottom right ({@link FollowIndicator}). */
    Node indicator() { return indicator.node(); }

    /** The "up to date" dot, for the chat's header. */
    Node following() { return indicator.live(); }

    Button jumpDown() { return indicator.jumpDown(); }

    boolean pinned() { return pinnedBottom; }

    /** A chat just opened: it starts at its newest message. */
    void pin() {
        pinnedBottom = true;
        anchorId = null;
        indicator.show(true, 0);
    }

    private void unpin() {
        if (pinnedBottom) seenId = newestId();
        pinnedBottom = false;
        indicator.show(false, newBelow());
    }

    private String newestId() {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i) instanceof ThreadRows.MessageRow mr) return mr.message().id();
        }
        return null;
    }

    /** Messages listed after the one that was newest when the person went up. */
    private int newBelow() {
        int seen = seenId == null ? -1 : indexOf(seenId);
        if (seen < 0) return 0;
        int n = 0;
        for (int i = seen + 1; i < rows.size(); i++) if (rows.get(i) instanceof ThreadRows.MessageRow) n++;
        return n;
    }

    /** After every layout, before the frame is drawn. */
    private void afterLayout() {
        if (!chatOpen.getAsBoolean() || rows.isEmpty() || !(list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow)) return;
        if (pinnedBottom) {
            keepBottom(flow);
        } else if (!dragging && newestGap(flow) != null && newestGap(flow) <= 1) {
            // Down to the newest message by any means (the wheel, the keys, the thumb): following it again.
            pin();
        } else {
            if (moved || anchorId == null) holdWhatsInView(flow);
            else keepAnchor(flow);
        }
        moved = false;
    }

    /**
     * Measuring and moving, until it holds: a correction lays the rows out again, and a row whose content changed (an
     * edit, a picture) only then takes its real height, which can undo the correction. A few rounds always settle it.
     */
    private static final int ROUNDS = 3;

    /** The newest message's end on the bottom edge, also when it's taller than the window. */
    private void keepBottom(VirtualFlow<?> flow) {
        for (int i = 0; i < 2 * ROUNDS; i++) {
            Double gap = newestGap(flow);
            if (gap != null && gap <= 1) return; // in view (space under it only when the chat is shorter than the window)
            if (gap != null) flow.scrollPixels(gap); // its end, not its top, when taller than the window
            else if (i == 0) list.scrollTo(rows.size() - 1); // near it, by the list's estimate of the rows' heights
            // Still not drawn: the estimate thought the list already ended there (a file row, a short window: the
            // newest stayed below for good on Linux). Moving by pixels measures the real rows on the way.
            else flow.scrollPixels(flow.getHeight());
            list.layout();
        }
    }

    /**
     * What's in view now is what's read: the message whose top is nearest above the middle of the view, so a picture
     * growing at the top, or the box growing at the bottom, moves neither it nor what's around it.
     */
    private void holdWhatsInView(VirtualFlow<?> flow) {
        anchorId = null;
        IndexedCell<?> first = flow.getFirstVisibleCell(), last = flow.getLastVisibleCell();
        if (first == null || last == null) return;
        for (int i = first.getIndex(); i <= last.getIndex(); i++) {
            IndexedCell<?> c = flow.getVisibleCell(i);
            if (c == null || !(c.getItem() instanceof ThreadRows.MessageRow mr)) continue;
            double y = top(c, flow);
            if (y > flow.getHeight() / 2 && anchorId != null) return;
            if (y >= 0 || i == last.getIndex()) {
                anchorId = mr.message().id();
                anchorY = y;
            }
        }
    }

    /** The message read back on the pixel it was on, whatever grew or shrank around it. */
    private void keepAnchor(VirtualFlow<?> flow) {
        for (int round = 0; round < 2 * ROUNDS; round++) {
            IndexedCell<?> cell = cellOf(flow, anchorId);
            if (cell == null) {
                int i = indexOf(anchorId);
                if (i < 0) {
                    holdWhatsInView(flow);
                    return;
                }
                // Out of view (history loaded above it): its top at the top first, laid out, and the next round
                // moves it down to its place. Moving by pixels before that layout undid the jump (it stayed out of view).
                flow.scrollToTop(i);
            } else {
                double off = top(cell, flow) - anchorY;
                if (Math.abs(off) < 0.5) return;
                flow.scrollPixels(off);
            }
            list.layout();
        }
    }

    private static IndexedCell<?> cellOf(VirtualFlow<?> flow, String id) {
        IndexedCell<?> first = flow.getFirstVisibleCell(), last = flow.getLastVisibleCell();
        if (first == null || last == null) return null;
        for (int i = first.getIndex(); i <= last.getIndex(); i++) {
            IndexedCell<?> c = flow.getVisibleCell(i);
            if (c != null && c.getItem() instanceof ThreadRows.MessageRow mr && mr.message().id().equals(id)) return c;
        }
        return null;
    }

    private int indexOf(String id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ThreadRows.MessageRow mr && mr.message().id().equals(id)) return i;
        }
        return -1;
    }

    /** How far the newest row's bottom is below the viewport's (px), or null when it isn't drawn. */
    private Double newestGap(VirtualFlow<?> flow) {
        IndexedCell<?> last = flow.getLastVisibleCell();
        if (last == null || last.getIndex() != rows.size() - 1) return null;
        return top(last, flow) + last.getHeight() - flow.getHeight();
    }

    /** Where {@code cell}'s top is drawn, from the top of the list's viewport. */
    private static double top(IndexedCell<?> cell, VirtualFlow<?> flow) {
        return cell.localToScene(0, 0).getY() - flow.localToScene(0, 0).getY();
    }

    private void hookScrollBar() {
        for (Node n : list.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                sb.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> dragging = true);
                sb.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> dragging = false);
                sb.valueProperty().addListener((o, a, v) -> {
                    if (dragging) {
                        moved = true;
                        if (v.doubleValue() < a.doubleValue()) unpin();
                    }
                    if (v.doubleValue() <= sb.getMin() + 0.0001) atTop.run();
                });
            }
        }
    }

    /** Down to the newest message and following it; the layouts after (cell heights become known) keep it in view. */
    void scrollToBottom() {
        pin();
        if (rows.isEmpty()) return;
        list.scrollTo(rows.size() - 1);
    }

    /**
     * Older messages arrived. {@code followed}: the list was following the bottom when they were asked for. Otherwise
     * the message read stays where it was (the anchor), as it does whatever else is added above.
     */
    void afterOlder(boolean followed) {
        if (followed && pinnedBottom) scrollToBottom();
    }

    /** Selects a message and shows it, as when clicking the message a reply quotes. */
    void jumpTo(Message target) {
        int i = indexOf(target.id());
        if (i < 0) return;
        // Stop following the bottom, or the next layout brings the list straight back down; where it lands is held.
        unpin();
        moved = true;
        list.getSelectionModel().select(i);
        list.scrollTo(Math.max(0, i - 2));
    }
}

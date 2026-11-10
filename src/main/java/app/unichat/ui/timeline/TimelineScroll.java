package app.unichat.ui.timeline;

import app.unichat.ui.Icons;
import app.unichat.model.Message;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;

import java.util.function.BooleanSupplier;

/** Where the message list is looking: following the newest message or not, the way back down, and staying put as rows change. */
final class TimelineScroll {
    private final ListView<ThreadRows.Row> list;
    private final ObservableList<ThreadRows.Row> rows;
    private final BooleanSupplier chatOpen;
    private final Runnable atTop;
    private final Button jumpDown;
    private ScrollBar vbar;
    /**
     * True while the viewport follows new messages. Only the person going towards older messages (wheel or trackpad up,
     * the keys up, dragging the scrollbar) unpins it, at once; it pins again when the newest message is wholly in view,
     * measured in pixels. The scrollbar also moves when a photo finishes loading and a bubble grows: that decides
     * nothing. (A time window after the wheel and a "near the bottom" of 0.2 % of the whole chat, hundreds of pixels in
     * a long one, pulled the person back down after a small scroll up.)
     */
    private boolean pinnedBottom = true;
    /** The scrollbar's thumb is being dragged: where it ends up is the person's choice. */
    private boolean dragging;

    /** {@code atTop} runs each time the list reaches its first row. {@code owner}'s scene is watched for layouts. */
    TimelineScroll(ListView<ThreadRows.Row> list, ObservableList<ThreadRows.Row> rows, Node owner, BooleanSupplier chatOpen, Runnable atTop) {
        this.list = list;
        this.rows = rows;
        this.chatOpen = chatOpen;
        this.atTop = atTop;
        jumpDown = Icons.iconButton(Icons.LOW, I18n.t("timeline.jumpDown"), this::scrollToBottom);
        jumpDown.getStyleClass().add("jump-down");
        jumpDown.setVisible(false);
        list.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() > 0) unpin(); // towards older messages
        });
        list.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case PAGE_UP, HOME, UP -> unpin();
                default -> { }
            }
        });
        list.skinProperty().addListener((o, a, s) -> Platform.runLater(this::hookScrollBar));
        list.widthProperty().addListener((o, a, w) -> {
            if (pinnedBottom && chatOpen.getAsBoolean()) scrollToBottom();
        });
        // After every layout: if we follow the bottom and something grew (media loaded, text wrapped), stay there.
        Runnable keepBottom = this::keepBottom;
        owner.sceneProperty().addListener((o, a, sc) -> {
            if (a != null) a.removePostLayoutPulseListener(keepBottom);
            if (sc != null) sc.addPostLayoutPulseListener(keepBottom);
        });
    }

    Button jumpDown() { return jumpDown; }

    boolean pinned() { return pinnedBottom; }

    /** A chat just opened: it starts at its newest message. */
    void pin() {
        pinnedBottom = true;
    }

    private void keepBottom() {
        if (!pinnedBottom || !chatOpen.getAsBoolean() || rows.isEmpty()) return;
        if (!lastRowFullyVisible()) list.scrollTo(rows.size() - 1);
    }

    private void unpin() {
        pinnedBottom = false;
    }

    private boolean lastRowFullyVisible() {
        if (!(list.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow)) return true;
        javafx.scene.control.IndexedCell<?> last = flow.getLastVisibleCell();
        if (last == null) return true;
        if (last.getIndex() != rows.size() - 1) return false;
        double bottom = last.localToScene(last.getBoundsInLocal()).getMaxY();
        return bottom <= flow.localToScene(flow.getBoundsInLocal()).getMaxY() + 1;
    }

    private void hookScrollBar() {
        for (Node n : list.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                vbar = sb;
                sb.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> dragging = true);
                sb.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> dragging = false);
                sb.valueProperty().addListener((o, a, v) -> {
                    if (dragging && v.doubleValue() < a.doubleValue()) unpin();
                    // Down to the newest message by any means (the wheel, the keys, the thumb): following it again.
                    if (!pinnedBottom && lastRowFullyVisible()) pinnedBottom = true;
                    jumpDown.setVisible(!pinnedBottom);
                    if (v.doubleValue() <= sb.getMin() + 0.0001) atTop.run();
                });
            }
        }
    }

    /** Down to the newest message and following it; the layouts after (cell heights become known) keep it in view. */
    void scrollToBottom() {
        pinnedBottom = true;
        jumpDown.setVisible(false);
        if (rows.isEmpty()) return;
        list.scrollTo(rows.size() - 1);
    }

    /** The message to stay on when older ones arrive above it. */
    Message firstMessage() {
        return rows.stream().filter(r -> r instanceof ThreadRows.MessageRow)
                .map(r -> ((ThreadRows.MessageRow) r).message()).findFirst().orElse(null);
    }

    /** Older messages arrived. {@code followed}: the list was following the bottom when they were asked for. */
    void afterOlder(boolean followed, Message firstBefore) {
        if (followed && pinnedBottom) {
            scrollToBottom();
        } else if (firstBefore != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof ThreadRows.MessageRow mr && mr.message() == firstBefore) {
                    list.scrollTo(Math.max(0, i - 1));
                    break;
                }
            }
        }
    }

    /** Selects a message and shows it, as when clicking the message a reply quotes. */
    void jumpTo(Message target) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ThreadRows.MessageRow mr && mr.message() == target) {
                // Stop following the bottom, or the next layout pulse (keepBottom) brings the list straight back down.
                pinnedBottom = false;
                list.getSelectionModel().select(i);
                list.scrollTo(Math.max(0, i - 2));
                jumpDown.setVisible(true);
                return;
            }
        }
    }
}

package app.unichat.ui.timeline;

import app.unichat.support.I18n;
import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Icons;
import app.unichat.ui.Nodes;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Supplier;

/**
 * Above the message box, what the open chat has waiting to be corrected before it goes ({@link SendQueue}): a row each,
 * with a wheel, what it says, "send without correcting" and "cancel" (icons). As many as there are: past about three the list
 * scrolls instead of pushing the chat up. Hidden with none.
 */
final class SendQueueView extends ScrollPane {
    /** How much of a text a row shows. */
    private static final int PREVIEW = 60;
    /** How many rows it shows before it scrolls. */
    private static final double MAX_ROWS = 3.5;

    private final SendQueue queue;
    private final Supplier<String> chatId;
    private final VBox rows = new VBox(4);
    /** Each waiting text's row, kept while it waits: rebuilt rows lost the keyboard's focus at every change. */
    private final java.util.Map<Long, HBox> shown = new java.util.HashMap<>();
    private final FxCoalescer redraw = new FxCoalescer(this::redraw);
    private final Runnable onChange = redraw::request;

    SendQueueView(SendQueue queue, Supplier<String> chatId) {
        this.queue = queue;
        this.chatId = chatId;
        setContent(rows);
        setFitToWidth(true);
        getStyleClass().add("send-queue");
        setMaxHeight(USE_PREF_SIZE); // never stretched past it by what holds it (it was, in a full run)
        queue.addListener(onChange);
        redraw();
    }

    /** The open chat changed, or what it waits for: drawn again on the UI thread. */
    void refresh() {
        redraw.request();
    }

    /** Stops listening: the window is being rebuilt. */
    void dispose() {
        queue.removeListener(onChange);
    }

    /**
     * As tall as its rows up to about three, then it scrolls: measured at each layout on a row itself (it grows with the
     * text), never kept from an earlier one (a height bound to the list's kept a row's while it was still being laid
     * out, and the list showed all eight).
     */
    @Override
    protected double computePrefHeight(double width) {
        if (rows.getChildren().isEmpty()) return super.computePrefHeight(width);
        double inner = (width < 0 ? getWidth() : width) - snappedLeftInset() - snappedRightInset();
        double row = rows.getChildren().getFirst().prefHeight(inner) + rows.getSpacing();
        return Math.min(rows.prefHeight(inner), MAX_ROWS * row) + snappedTopInset() + snappedBottomInset();
    }

    private void redraw() {
        String chat = chatId.get();
        List<SendQueue.Pending> waiting = chat == null ? List.of() : queue.waiting(chat);
        java.util.Set<Long> now = new java.util.HashSet<>();
        for (SendQueue.Pending p : waiting) now.add(p.n());
        shown.keySet().removeIf(n -> !now.contains(n));
        rows.getChildren().removeIf(r -> !shown.containsValue(r)); // only what went or was cancelled goes
        for (int i = 0; i < waiting.size(); i++) {
            HBox r = shown.get(waiting.get(i).n());
            if (r == null) {
                r = row(waiting.get(i));
                shown.put(waiting.get(i).n(), r);
            }
            if (i >= rows.getChildren().size() || rows.getChildren().get(i) != r) {
                rows.getChildren().remove(r);
                rows.getChildren().add(i, r);
            }
        }
        Nodes.show(this, !waiting.isEmpty());
    }

    private HBox row(SendQueue.Pending p) {
        String preview = p.text().length() > PREVIEW ? p.text().substring(0, PREVIEW) + "…" : p.text();
        ProgressIndicator wheel = new ProgressIndicator();
        wheel.getStyleClass().add("message-fixing-wheel");
        Label what = new Label(I18n.t("sendQueue.waiting", preview.replace('\n', ' ')), wheel);
        what.getStyleClass().add("send-queue-text");
        what.setMinWidth(0);
        what.setMaxWidth(Double.MAX_VALUE);
        VBox texts = new VBox(1, what);
        if (p.wouldAnswer() != null) { // which message it'd answer is never a surprise
            Label answers = new Label(I18n.t("sendQueue.wouldAnswer", p.wouldAnswer()));
            answers.getStyleClass().add("send-queue-answers");
            answers.setMinWidth(0);
            texts.getChildren().add(answers);
        }
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        // Icons, each named in full for the screen reader and its tooltip: words were cut short at 160 % text.
        Button now = Icons.iconButton(Icons.SEND, I18n.t("sendQueue.sendNowFor", preview), () -> queue.sendNow(p));
        Button cancel = Icons.iconButton(Icons.CLOSE, I18n.t("sendQueue.cancelFor", preview), () -> queue.cancel(p));
        for (Button b : new Button[] {now, cancel}) b.setMinWidth(USE_PREF_SIZE);
        HBox row = new HBox(4, texts, now, cancel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("send-queue-row");
        return row;
    }
}

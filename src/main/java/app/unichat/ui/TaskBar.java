package app.unichat.ui;

import app.unichat.support.I18n;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The one look of the bars on top of the window that follow work going on (sending files, forwarding, protecting a
 * photo, exploring a chat's past), so they read alike: a picture or icon | what's done, and the file and chat (a link)
 * under it, at a fixed width | the bar, with the step it's at under it, taking the rest | the numbers (a big one, a small
 * one) at a fixed width | its buttons. Columns that never push each other: names and steps change all the time. Each
 * bar fills them in; one left empty is hidden.
 */
public abstract class TaskBar extends HBox {
    /** The picture or icon's side. */
    protected static final int LEAD = 36;

    protected final StackPane lead = new StackPane();
    protected final Label what = new Label();
    /** Beside the chat's link: the file going, say. */
    protected final Label detail = new Label();
    protected final Hyperlink where = new Hyperlink();
    protected final ProgressBar bar = new ProgressBar(0);
    protected final Label stage = new Label();
    protected final Label big = new Label();
    protected final Label small = new Label();
    private final HBox detailRow = new HBox(4, detail, where);
    private final VBox numbers = new VBox(0, big, small);
    private final HBox actions = new HBox(4);

    /** {@code kind}: the bar's own style class. */
    protected TaskBar(String kind) {
        super(10);
        getStyleClass().addAll("audio-bar", "task-bar", kind);
        setAlignment(Pos.CENTER_LEFT);
        lead.getStyleClass().add("upload-thumb");
        lead.setMinSize(LEAD, LEAD);
        lead.setPrefSize(LEAD, LEAD);
        lead.setMaxSize(LEAD, LEAD);

        what.getStyleClass().add("audio-who");
        detail.getStyleClass().add("audio-time");
        where.getStyleClass().add("upload-where");
        for (Region r : new Region[] {what, detail, where, stage}) r.setMinWidth(0);
        detail.setMaxWidth(260);
        detailRow.setAlignment(Pos.CENTER_LEFT);
        VBox texts = new VBox(0, what, detailRow);
        texts.setAlignment(Pos.CENTER_LEFT);
        fixedEm(texts, 24);

        stage.getStyleClass().add("audio-time");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMinWidth(80);
        VBox middle = new VBox(3, bar, stage);
        middle.setAlignment(Pos.CENTER_LEFT);
        middle.setMinWidth(80);
        HBox.setHgrow(middle, Priority.ALWAYS);

        big.getStyleClass().add("upload-percent");
        small.getStyleClass().add("audio-time");
        numbers.setAlignment(Pos.CENTER_LEFT);
        fixedEm(numbers, 10);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(lead, texts, middle, numbers, actions);
    }

    /** Its buttons, at the end. */
    protected void actions(Node... buttons) {
        actions.getChildren().setAll(buttons);
    }

    /** Hides what has nothing to say (the file and chat line, the numbers), after the texts are set. */
    protected void tidy() {
        Nodes.show(detailRow, !detail.getText().isEmpty() || !where.getText().isEmpty());
        Nodes.show(detail, !detail.getText().isEmpty());
        Nodes.show(where, !where.getText().isEmpty());
        Nodes.show(stage, !stage.getText().isEmpty());
        Nodes.show(small, !small.getText().isEmpty());
        Nodes.show(numbers, !big.getText().isEmpty() || !small.getText().isEmpty());
    }

    /** Exactly {@code em} times the text size wide, following the text size as it changes. */
    private void fixedEm(Region r, double em) {
        javafx.beans.binding.DoubleBinding w = javafx.beans.binding.Bindings.createDoubleBinding(
                () -> em * what.getFont().getSize(), what.fontProperty());
        r.minWidthProperty().bind(w);
        r.prefWidthProperty().bind(w);
        r.maxWidthProperty().bind(w);
    }

    /** "37 %": whole numbers, and 100 only when everything is done (99.6 % reads 99 %). */
    public static String percent(double fraction) {
        return (int) Math.floor(Math.max(0, Math.min(1, fraction)) * 100) + " %";
    }

    /** "faltan ~1 h 20 min", "faltan ~12 min", "falta menos de un minuto"; "" when unknown. */
    public static String eta(long ms) {
        if (ms < 0) return "";
        long min = Math.round(ms / 60_000.0);
        if (min < 1) return I18n.t("uploadBar.etaSoon");
        return min < 60 ? I18n.t("uploadBar.etaMinutes", min) : I18n.t("uploadBar.etaHours", min / 60, min % 60);
    }
}

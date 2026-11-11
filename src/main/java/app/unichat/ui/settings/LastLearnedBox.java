package app.unichat.ui.settings;

import app.unichat.assist.StyleDiff;
import app.unichat.assist.WritingStyle;
import app.unichat.support.I18n;
import app.unichat.ui.Formats;
import app.unichat.ui.Labels;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/**
 * Ajustes → Escribir con IA's "Lo último que aprendió": when learning last changed how the person writes, the traits it
 * added and removed, a way to undo it, and how many corrections wait for the next draft.
 */
final class LastLearnedBox {
    private final WritingStyle style;
    private final Consumer<String> restored;
    private final Label when = new Label();
    private final Label added = Labels.help(""), removed = Labels.help("");
    private final Button undo = new Button();
    private final Label pending = new Label();
    private final Node node;

    /** {@code restored}: told the file's text after an undo, to show it. */
    LastLearnedBox(WritingStyle style, Consumer<String> restored) {
        this.style = style;
        this.restored = restored;
        when.getStyleClass().add("settings-label");
        when.setWrapText(true);
        when.setMinHeight(Region.USE_PREF_SIZE);
        pending.getStyleClass().add("settings-label");
        pending.setWrapText(true);
        pending.setMinHeight(Region.USE_PREF_SIZE);
        undo.setText(I18n.t("settings.ai.undoLast"));
        undo.setAccessibleHelp(I18n.t("settings.ai.undoLastHelp"));
        undo.getStyleClass().add("secondary-button");
        undo.setOnAction(e -> undo());
        Label undoHelp = Labels.help(I18n.t("settings.ai.undoLastHelp"));
        VBox last = row(when, added, removed, undoHelp, undo);
        VBox waiting = row(pending, Labels.help(I18n.t("settings.ai.pendingHelp")));
        node = SettingsForm.group(I18n.t("settings.ai.lastLearned"), last, waiting);
        refresh();
    }

    Node node() { return node; }

    /** Shows the file's history as it is now. */
    void refresh() {
        WritingStyle.LastLearned last = style.lastLearned();
        if (last == null) {
            when.setText(I18n.t("settings.ai.lastLearnedNone"));
            show(added, null, List.of());
            show(removed, null, List.of());
        } else {
            when.setText(stamp(last.when()));
            StyleDiff changes = last.changes();
            show(added, changes.isEmpty() ? I18n.t("settings.ai.lastNoChange")
                    : changes.added().isEmpty() ? null : I18n.t("settings.ai.lastAdded"), changes.added());
            show(removed, changes.removed().isEmpty() ? null : I18n.t("settings.ai.lastRemoved"), changes.removed());
        }
        undo.setDisable(last == null);
        pending.setText(I18n.n("settings.ai.pending", style.pending().size()));
    }

    private void undo() {
        if (!style.undoLast()) return;
        restored.accept(style.text());
        refresh();
    }

    private static String stamp(Instant t) {
        ZoneId zone = ZoneId.systemDefault();
        return I18n.t("settings.ai.lastLearnedWhen",Formats.daySeparator(t, Instant.now(), zone), Formats.time(t, zone));
    }

    /** {@code title} and a line per trait; hidden when there's no title. */
    private static void show(Label l, String title, List<String> traits) {
        StringBuilder b = new StringBuilder(title == null ? "" : title);
        for (String t : traits) b.append("\n• ").append(t);
        l.setText(b.toString());
        l.setVisible(title != null);
        l.setManaged(title != null);
    }

    private static VBox row(Node... children) {
        VBox v = new VBox(4, children);
        v.getStyleClass().add("settings-row");
        return v;
    }
}

package app.unichat.ui.timeline;

import app.unichat.autosave.SavedText;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import app.unichat.ui.Clipboards;
import app.unichat.ui.Sheets;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * "Ver lo que decía…": what a deleted or edited message said, from the copy the person's computers keep of the chat's
 * texts (auto-save's "Textos"), each version with when and who, to read or copy; or that there's no copy of it. In the
 * window's overlay, as the delete sheet.
 */
public final class SavedTextSheet {
    private static final double WIDTH = 440;

    private final Consumer<Node> show;
    private final Runnable hide;
    private final Consumer<String> toast;

    public SavedTextSheet(Consumer<Node> show, Runnable hide, Consumer<String> toast) {
        this.show = show;
        this.hide = hide;
        this.toast = toast;
    }

    /** Shows the sheet at once, saying it's looking, then what {@code said} brings. */
    void show(CompletableFuture<List<SavedText>> said) {
        Label title = new Label(I18n.t("savedText.title"));
        title.getStyleClass().add("sheet-title");
        title.setWrapText(true);
        VBox body = new VBox(12, text(I18n.t("savedText.looking")));
        Button close = new Button(I18n.t("info.close"));
        close.getStyleClass().add("primary-button");
        close.setDefaultButton(true);
        close.setCancelButton(true);
        close.setOnAction(e -> hide.run());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        VBox card = new VBox(12, title, body, new HBox(10, grow, close));
        card.getStyleClass().addAll("card", "folder-dialog", "saved-text-sheet");
        show.accept(Sheets.scrolling(card, WIDTH));
        Platform.runLater(close::requestFocus);
        said.whenComplete((versions, err) -> Platform.runLater(() -> {
            body.getChildren().setAll(err != null ? List.of(text(I18n.t("savedText.failed", Errors.message(err))))
                    : versions.isEmpty() ? List.of(text(I18n.t("savedText.none"))) : versions(versions));
            close.setAccessibleHelp(body.getChildren().stream().map(SavedTextSheet::spoken).reduce("", (a, b) -> (a + " " + b).strip()));
        }));
    }

    /**
     * Each version: when (as sent or written, when sent; a correction or an edit, when it was seen) and who, what it is
     * (written before the AI corrected it, corrected with AI, edited), its text, and a way to copy it.
     */
    private List<Node> versions(List<SavedText> versions) {
        List<Node> out = new ArrayList<>();
        List<SavedText.Mark> marks = SavedText.marks(versions);
        for (int i = 0; i < versions.size(); i++) {
            SavedText t = versions.get(i);
            SavedText.Mark mark = marks.get(i);
            boolean asSent = mark == SavedText.Mark.SENT || mark == SavedText.Mark.WRITTEN;
            String when = when(asSent ? t.at() : t.seen());
            Label meta = text(mark == SavedText.Mark.SENT ? I18n.t("savedText.version", when, t.from())
                    : I18n.t("savedText.marked", when, t.from(), I18n.t("autosave.texts." + mark.name())));
            meta.getStyleClass().add("help-text");
            Label said = text(t.text());
            Button copy = new Button(I18n.t("common.copy"));
            copy.getStyleClass().add("secondary-button");
            copy.setAccessibleText(I18n.t("common.copy") + ": " + meta.getText());
            copy.setOnAction(e -> {
                Clipboards.copy(t.text());
                toast.accept(I18n.t("savedText.copied"));
            });
            out.add(new VBox(6, meta, said, copy));
        }
        return out;
    }

    private static Label text(String s) {
        Label l = new Label(s);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    /** What a screen reader says of a part, the focus on "Cerrar": the texts it holds. */
    private static String spoken(Node n) {
        if (n instanceof Label l) return l.getText();
        if (n instanceof VBox v) return v.getChildren().stream().filter(c -> c instanceof Label).map(c -> ((Label) c).getText())
                .reduce("", (a, b) -> (a + ". " + b).replaceFirst("^\\. ", ""));
        return "";
    }

    private static String when(Instant at) {
        return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withLocale(I18n.locale()).format(at.atZone(ZoneId.systemDefault()));
    }
}

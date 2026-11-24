package app.unichat.ui.settings;

import app.unichat.cloud.RecoveryCheck;
import app.unichat.support.I18n;
import app.unichat.support.SafeFile;
import app.unichat.ui.Labels;
import app.unichat.ui.Nodes;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static app.unichat.ui.settings.SettingsForm.actions;
import static app.unichat.ui.settings.SettingsForm.stacked;

/**
 * A recovery code, shown once (it is never kept, so it can't be shown again): copied, saved as a text file or printed
 * through the system's own dialogs; then two of its groups asked back, so the person is known to have kept it.
 */
final class RecoveryCodeSheet extends VBox {
    /** Where "Guardar como .txt" writes: the file the system's save dialog gives (null: cancelled). Tests give one. */
    static Function<@Nullable Window, @Nullable File> saveTo = RecoveryCodeSheet::chooseFile; // NOPMD: MutableStaticState, a seam tests swap
    /** "Imprimir": through the system's print dialog; whether it was printed. Tests replace it. */
    static BiPredicate<@Nullable Window, String> printer = RecoveryCodeSheet::print; // NOPMD: MutableStaticState, a seam tests swap
    /** The system's clipboard as "Copiar" uses it: the code put there, and emptied after while it still holds it. */
    interface Clip {
        void put(String text);

        /** What it holds as text now; null for none. */
        @Nullable String now();

        void clear();
    }

    /** The system's clipboard; tests give one of their own. */
    static Clip clipboard = new Clip() { // NOPMD: MutableStaticState, a seam tests swap
        @Override public void put(String text) {
            var c = new ClipboardContent();
            c.putString(text);
            Clipboard.getSystemClipboard().setContent(c);
        }

        @Override public String now() {
            return Clipboard.getSystemClipboard().getString();
        }

        @Override public void clear() {
            Clipboard.getSystemClipboard().clear();
        }
    };

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String code;
    private final int[] asked = new int[2];
    private final Label said = Labels.help("");
    private final Runnable kept;
    private final Runnable cancelled;

    /**
     * @param kept the person typed back the groups asked
     * @param cancelled they stopped before: the code may not be kept
     */
    RecoveryCodeSheet(String code, Runnable kept, Runnable cancelled) {
        this.code = code;
        this.kept = kept;
        this.cancelled = cancelled;
        getStyleClass().add("recovery-code-sheet");
        asked[0] = 1 + RANDOM.nextInt(RecoveryCheck.GROUPS);
        do {
            asked[1] = 1 + RANDOM.nextInt(RecoveryCheck.GROUPS);
        } while (asked[1] == asked[0]);
        java.util.Arrays.sort(asked);
        Nodes.show(said, false);
        showCode();
        // However the sheet goes (kept, cancelled, the page drawn again or closed), the code doesn't stay on the clipboard.
        sceneProperty().addListener((o, was, now) -> { if (now == null) forgetCopy(); });
    }

    /** The clipboard emptied if it still holds the code: something the person copied since is theirs. */
    private void forgetCopy() {
        if (code.equals(clipboard.now())) clipboard.clear();
    }

    /** The groups asked back, from 1 (for tests). */
    int[] asked() {
        return asked.clone();
    }

    private void showCode() {
        // Two lines of four groups: one line was cut at 160 % text. Typed back, the line break is forgiven as a space is.
        TextArea field = new TextArea(lines(code));
        field.setEditable(false); // selectable, never changed by a stray key
        field.setPrefRowCount(2);
        field.setWrapText(false);
        field.getStyleClass().add("vault-code");
        field.setAccessibleText(I18n.t("settings.vault.code.title"));
        Button copy = secondary(I18n.t("settings.vault.code.copy"), () -> {
            clipboard.put(code);
            say(I18n.t("settings.vault.code.copied"));
        });
        Button save = secondary(I18n.t("settings.vault.code.save"), this::save);
        Button print = secondary(I18n.t("settings.vault.code.print"), () ->
            say(I18n.t(printer.test(Nodes.window(this), text()) ? "settings.vault.code.printed" : "settings.vault.code.notPrinted")));
        Button done = new Button(I18n.t("settings.vault.code.keptIt"));
        done.getStyleClass().add("primary-button");
        done.setOnAction(e -> askBack());
        Button cancel = secondary(I18n.t("folders.dialog.cancel"), this::cancel);
        getChildren().setAll(stacked(I18n.t("settings.vault.code.title"), I18n.t("settings.vault.code.help"),
                new VBox(8, field, new FlowPane(8, 6, copy, save, print), said)),
            actions(new FlowPane(8, 6, done, cancel)));
    }

    /** The code as two lines of four groups. */
    static String lines(String code) {
        String[] groups = code.split("-");
        int half = groups.length / 2;
        return String.join("-", java.util.Arrays.copyOfRange(groups, 0, half)) + "-\n"
            + String.join("-", java.util.Arrays.copyOfRange(groups, half, groups.length));
    }

    /** Two groups typed back, checked as the code is when typed (case, O for 0…). */
    private void askBack() {
        TextField first = group(asked[0]);
        TextField second = group(asked[1]);
        Label wrong = Labels.help(I18n.t("settings.vault.code.wrongGroups"));
        wrong.getStyleClass().add("login-error");
        Nodes.show(wrong, false);
        Button check = new Button(I18n.t("settings.vault.code.check"));
        check.getStyleClass().add("primary-button");
        check.setDefaultButton(true);
        check.setOnAction(e -> {
            if (RecoveryCheck.matches(code, asked[0], first.getText()) && RecoveryCheck.matches(code, asked[1], second.getText())) {
                forgetCopy();
                kept.run();
            } else {
                Nodes.show(wrong, true);
                first.requestFocus();
            }
        });
        Button back = secondary(I18n.t("settings.vault.code.showAgain"), this::showCode);
        Button cancel = secondary(I18n.t("folders.dialog.cancel"), this::cancel);
        getChildren().setAll(stacked(I18n.t("settings.vault.code.askTitle"), I18n.t("settings.vault.code.ask", asked[0], asked[1]),
                new VBox(8, new FlowPane(8, 6, first, second), wrong)),
            actions(new FlowPane(8, 6, check, back, cancel)));
        first.requestFocus();
    }

    private void cancel() {
        forgetCopy();
        cancelled.run();
    }

    private static TextField group(int i) {
        TextField f = new TextField();
        f.setPrefColumnCount(6);
        f.setPromptText(I18n.t("settings.vault.code.group", i));
        f.setAccessibleText(I18n.t("settings.vault.code.group", i));
        f.getStyleClass().add("vault-code-group");
        return f;
    }

    /** What's saved and printed: the code with a line saying what it is. */
    private String text() {
        return I18n.t("settings.vault.code.file", code);
    }

    private void save() {
        File file = saveTo.apply(Nodes.window(this));
        if (file == null) return;
        try {
            SafeFile.replace(file.toPath(), text().getBytes(StandardCharsets.UTF_8), true); // the code: nobody else reads it
            say(I18n.t("settings.vault.code.saved", file.getName()));
        } catch (IOException e) {
            say(I18n.t("settings.vault.code.notSaved"));
        }
    }

    private static @Nullable File chooseFile(@Nullable Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("settings.vault.code.save"));
        chooser.setInitialFileName(I18n.t("settings.vault.code.fileName"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("settings.vault.code.fileType"), "*.txt"));
        return chooser.showSaveDialog(owner);
    }

    private static boolean print(@Nullable Window owner, String text) {
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) return false;
        if (!job.showPrintDialog(owner)) {
            job.cancelJob();
            return false;
        }
        // A Text lays itself out: a Label outside a scene has no skin, and printed nothing.
        javafx.scene.text.Text page = new javafx.scene.text.Text(text);
        page.setFont(javafx.scene.text.Font.font("monospace", 14));
        page.setWrappingWidth(job.getJobSettings().getPageLayout().getPrintableWidth());
        boolean printed = job.printPage(page);
        return printed && job.endJob();
    }

    private void say(String text) {
        said.setText(text);
        Nodes.show(said, true);
    }

    private static Button secondary(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("secondary-button");
        b.setOnAction(e -> action.run());
        return b;
    }

    /** Nothing of the code in a log. */
    @Override
    public String toString() {
        return "RecoveryCodeSheet[…]";
    }
}

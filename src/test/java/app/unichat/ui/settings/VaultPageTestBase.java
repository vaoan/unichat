package app.unichat.ui.settings;

import app.unichat.cloud.Cloud;
import app.unichat.cloud.VaultFixture;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import java.util.concurrent.ExecutionException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiPredicate;
import java.util.function.Function;


/** What the vault page's tests share: the page alone in a window over an account in memory, and how they look and click. */
abstract class VaultPageTestBase { // NOPMD: AbstractClassWithoutAbstractMethod, a base for the vault page's tests, never run alone
    protected static final String PASSWORD = "una contraseña larga", LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55",
        LATE = "5f0e7d7c-4b5a-4a4c-9e95-0d6b1a5c6f88";

    @TempDir
    Path dir;
    protected VaultFixture account;
    protected Stage stage;
    protected Function<javafx.stage.Window, File> saveWas;
    protected BiPredicate<javafx.stage.Window, String> printWas;
    protected RecoveryCodeSheet.Clip clipboardWas;
    /** What the system's clipboard holds now, as the sheet put it there or the person copied over it. */
    protected String clip;
    protected final List<String> copied = new ArrayList<>(), printed = new ArrayList<>();

    @BeforeAll
    static void toolkit() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void start() throws Exception {
        account = new VaultFixture(dir);
        saveWas = RecoveryCodeSheet.saveTo;
        printWas = RecoveryCodeSheet.printer;
        clipboardWas = RecoveryCodeSheet.clipboard;
        RecoveryCodeSheet.saveTo = w -> dir.resolve("codigo.txt").toFile();
        RecoveryCodeSheet.printer = (w, text) -> printed.add(text);
        RecoveryCodeSheet.clipboard = new RecoveryCodeSheet.Clip() {
            @Override public void put(String text) {
                copied.add(text);
                clip = text;
            }

            @Override public String now() {
                return clip;
            }

            @Override public void clear() {
                clip = null;
            }
        };
    }

    @AfterEach
    @SuppressFBWarnings(value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", justification = "set in @BeforeEach (or the JavaFX start) before any test uses it")
    void stop() throws Exception {
        app.unichat.support.Seen.reset(true);
        if (stage != null) FxWait.fx(() -> { stage.close(); return null; });
        RecoveryCodeSheet.saveTo = saveWas;
        RecoveryCodeSheet.printer = printWas;
        RecoveryCodeSheet.clipboard = clipboardWas;
        CloudPage.use(null);
        account.close();
    }

    /** The section alone in a window of its own (a new one each time: the page as it's opened again). */
    @SuppressFBWarnings(value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", justification = "set in @BeforeEach (or the JavaFX start) before any test uses it")
    protected void page() throws InterruptedException {
        if (stage != null) FxWait.fx(() -> { stage.close(); return null; });
        stage = FxWait.fx(() -> {
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(new ScrollPane(new VBox(VaultPage.build(account.cloud()))), 720, 900));
            s.show();
            return s;
        });
    }

    @SuppressFBWarnings(value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", justification = "set in @BeforeEach (or the JavaFX start) before any test uses it")
    protected Parent root() {
        return stage.getScene().getRoot();
    }

    /** Every node showing under the window, as a screen reader or the Tab key would find them. */
    protected List<Node> showing() {
        List<Node> out = new ArrayList<>();
        walk(((ScrollPane) root()).getContent(), out);
        return out;
    }

    private static void walk(Node n, List<Node> out) {
        if (!n.isVisible() || !n.isManaged()) return;
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) walk(c, out);
    }

    protected ButtonBase button(String text) {
        return showing().stream().filter(n -> n instanceof ButtonBase b && text.equals(b.getText())).map(n -> (ButtonBase) n).findFirst()
            .orElseThrow(() -> new AssertionError("no button " + text));
    }

    protected boolean hasButton(String text) {
        return showing().stream().anyMatch(n -> n instanceof ButtonBase b && text.equals(b.getText()));
    }

    protected TextInputControl field(String accessible) {
        return showing().stream().filter(n -> n instanceof TextInputControl t && accessible.equals(t.getAccessibleText()))
            .map(n -> (TextInputControl) n).findFirst().orElseThrow(() -> new AssertionError("no field " + accessible));
    }

    protected boolean says(String text) {
        return showing().stream().anyMatch(n -> n instanceof Labeled l && l.getText() != null && l.getText().contains(text));
    }

    protected void waitFor(String what) {
        FxWait.until("the page to say \"" + what + "\"", () -> says(what));
    }

    protected void click(String text) throws InterruptedException {
        FxWait.fx(() -> { button(text).fire(); return null; });
    }

    protected void type(String accessible, String text) throws InterruptedException {
        FxWait.fx(() -> { field(accessible).setText(text); return null; });
    }

    /** The code on screen, as one would copy it from there (two lines, read as one). */
    protected String codeShown() throws InterruptedException {
        FxWait.until("a code shown", () -> root().lookup(".text-area.vault-code") instanceof TextArea t && !t.isEditable());
        return FxWait.fx(() -> ((TextArea) root().lookup(".text-area.vault-code")).getText().replace("\n", ""));
    }

    protected RecoveryCodeSheet sheet() throws InterruptedException {
        return FxWait.fx(() -> (RecoveryCodeSheet) root().lookup(".recovery-code-sheet"));
    }

    /** Groups asked back typed from {@code code}, or wrong ones. */
    protected void typeGroups(String code, boolean right) throws InterruptedException {
        click(I18n.t("settings.vault.code.keptIt"));
        int[] asked = sheet().asked();
        String[] groups = code.split("-");
        type(I18n.t("settings.vault.code.group", asked[0]), right ? groups[asked[0] - 1].toLowerCase(java.util.Locale.ROOT) : "ZZZZ");
        type(I18n.t("settings.vault.code.group", asked[1]), groups[asked[1] - 1]);
        click(I18n.t("settings.vault.code.check"));
    }

    protected void waitOpen() {
        waitFor(I18n.t("settings.vault.open"));
    }

    protected Cloud.VaultState round() throws InterruptedException, ExecutionException {
        return account.cloud().syncNow().get().vault();
    }
}

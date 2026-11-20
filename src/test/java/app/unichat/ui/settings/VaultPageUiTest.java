package app.unichat.ui.settings;

import app.unichat.cloud.Cloud;
import app.unichat.cloud.VaultFixture;
import app.unichat.gif.GifProviders;
import app.unichat.service.MockChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import app.unichat.ui.Theme;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ajustes → Nube → Bóveda over an account in memory: making the vault (its code shown, copied, saved, printed, asked
 * back), opening it on a new computer or going on without it, a wrong secret, the computers and taking one out with
 * the key changed, a computer whose key changed, forgetting it here; by keyboard, named, and at 160 %.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class VaultPageUiTest {
    private static final String PASSWORD = "una contraseña larga", LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55",
        LATE = "5f0e7d7c-4b5a-4a4c-9e95-0d6b1a5c6f88";

    @TempDir
    Path dir;
    private VaultFixture account;
    private Stage stage;
    private Function<javafx.stage.Window, File> saveWas;
    private BiPredicate<javafx.stage.Window, String> printWas;
    private Consumer<String> clipboardWas;
    private final List<String> copied = new ArrayList<>(), printed = new ArrayList<>();

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
        RecoveryCodeSheet.clipboard = copied::add;
    }

    @AfterEach
    void stop() throws Exception {
        if (stage != null) FxWait.fx(() -> { stage.close(); return null; });
        RecoveryCodeSheet.saveTo = saveWas;
        RecoveryCodeSheet.printer = printWas;
        RecoveryCodeSheet.clipboard = clipboardWas;
        CloudPage.use(null);
        account.close();
    }

    /** The section alone in a window of its own (a new one each time: the page as it's opened again). */
    private void page() throws Exception {
        if (stage != null) FxWait.fx(() -> { stage.close(); return null; });
        stage = FxWait.fx(() -> {
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(new ScrollPane(new VBox(VaultPage.build(account.cloud()))), 720, 900));
            s.show();
            return s;
        });
    }

    private Parent root() {
        return stage.getScene().getRoot();
    }

    /** Every node showing under the window, as a screen reader or the Tab key would find them. */
    private List<Node> showing() {
        List<Node> out = new ArrayList<>();
        walk(((ScrollPane) root()).getContent(), out);
        return out;
    }

    private static void walk(Node n, List<Node> out) {
        if (!n.isVisible() || !n.isManaged()) return;
        out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) walk(c, out);
    }

    private ButtonBase button(String text) {
        return showing().stream().filter(n -> n instanceof ButtonBase b && text.equals(b.getText())).map(n -> (ButtonBase) n).findFirst()
            .orElseThrow(() -> new AssertionError("no button " + text));
    }

    private boolean hasButton(String text) {
        return showing().stream().anyMatch(n -> n instanceof ButtonBase b && text.equals(b.getText()));
    }

    private TextInputControl field(String accessible) {
        return showing().stream().filter(n -> n instanceof TextInputControl t && accessible.equals(t.getAccessibleText()))
            .map(n -> (TextInputControl) n).findFirst().orElseThrow(() -> new AssertionError("no field " + accessible));
    }

    private boolean says(String text) {
        return showing().stream().anyMatch(n -> n instanceof Labeled l && l.getText() != null && l.getText().contains(text));
    }

    private void waitFor(String what) throws Exception {
        FxWait.until("the page to say \"" + what + "\"", () -> says(what));
    }

    private void click(String text) throws Exception {
        FxWait.fx(() -> { button(text).fire(); return null; });
    }

    private void type(String accessible, String text) throws Exception {
        FxWait.fx(() -> { field(accessible).setText(text); return null; });
    }

    /** The code on screen, as one would copy it from there (two lines, read as one). */
    private String codeShown() throws Exception {
        FxWait.until("a code shown", () -> root().lookup(".text-area.vault-code") instanceof TextArea t && !t.isEditable());
        return FxWait.fx(() -> ((TextArea) root().lookup(".text-area.vault-code")).getText().replace("\n", ""));
    }

    private RecoveryCodeSheet sheet() throws Exception {
        return FxWait.fx(() -> (RecoveryCodeSheet) root().lookup(".recovery-code-sheet"));
    }

    /** Groups asked back typed from {@code code}, or wrong ones. */
    private void typeGroups(String code, boolean right) throws Exception {
        click(I18n.t("settings.vault.code.keptIt"));
        int[] asked = sheet().asked();
        String[] groups = code.split("-");
        type(I18n.t("settings.vault.code.group", asked[0]), right ? groups[asked[0] - 1].toLowerCase(java.util.Locale.ROOT) : "ZZZZ");
        type(I18n.t("settings.vault.code.group", asked[1]), groups[asked[1] - 1]);
        click(I18n.t("settings.vault.code.check"));
    }

    private void waitOpen() throws Exception {
        waitFor(I18n.t("settings.vault.open"));
    }

    private Cloud.VaultState round() throws Exception {
        return account.cloud().syncNow().get().vault();
    }

    @Test
    void aNewComputerCanGoOnWithoutTheVault() throws Exception {
        String code = account.otherComputerMadeIt(null);
        page();
        waitFor(I18n.t("settings.vault.locked"));
        assertTrue(FxWait.fx(() -> hasButton(I18n.t("settings.vault.open.with.RECOVERY"))), "offered next to opening it");
        click(I18n.t("settings.vault.decline"));
        FxWait.until("declined", () -> hasButton(I18n.t("settings.vault.openIt")));
        assertFalse(FxWait.fx(() -> hasButton(I18n.t("settings.vault.decline"))));
        assertEquals(Cloud.VaultState.DECLINED, round());

        page(); // opened again later
        FxWait.until("still declined", () -> hasButton(I18n.t("settings.vault.openIt")));
        assertFalse(FxWait.fx(() -> hasButton(I18n.t("settings.vault.decline"))), "never asked again");
        assertTrue(FxWait.fx(() -> says(I18n.t("settings.vault.declined"))));
        click(I18n.t("settings.vault.openIt"));
        type(I18n.t("settings.vault.open.field.RECOVERY"), code);
        click(I18n.t("settings.vault.open.with.RECOVERY"));
        waitOpen();
        assertEquals(Cloud.VaultState.OPEN, round());
    }

    @Test
    void withoutAVaultTheKeysAreEnteredByHandAsToday() throws Exception {
        CloudPage.use(account.cloud());
        String local = Settings.get().getString("server.local", null);
        try {
            Settings.get().setLocalServer(true); // where Telegram's keys are typed
            assertEquals(Cloud.VaultState.NONE, round());
            keysByHand("none");
            account.otherComputerMadeIt(null);
            account.cloud().declineVault(true).get();
            assertEquals(Cloud.VaultState.DECLINED, round());
            keysByHand("declined");
        } finally {
            Settings.get().set("server.local", local);
        }
    }

    /** Telegram's keys and a GIF service's key typed in Ajustes and kept here, as before the vault. */
    private void keysByHand(String where) throws Exception {
        GifProviders.Known gif = GifProviders.known().get(0);
        MockChatService svc = new MockChatService();
        try {
            Stage owner = FxWait.fx(() -> {
                Stage o = new Stage();
                o.setOpacity(0);
                o.show();
                SettingsWindow.show(o, svc, () -> { }, () -> { }, id -> { }, t -> { });
                return o;
            });
            FxWait.fx(() -> { SettingsWindow.select("networks"); return null; });
            FxWait.until("Telegram's keys", () -> SettingsWindow.current().getScene().getRoot().lookup(".telegram-api-id") != null);
            TextField id = FxWait.fx(() -> (TextField) SettingsWindow.current().getScene().getRoot().lookup(".telegram-api-id"));
            assertTrue(FxWait.fx(() -> id.isEditable() && !id.isDisabled()), where + ": Telegram's keys typed here");
            FxWait.fx(() -> { SettingsWindow.select("gifs"); return null; });
            String keyOf = I18n.t("settings.gifs.keyOf", gif.name());
            FxWait.until("the GIF key's field", () -> SettingsWindow.current().getScene().getRoot().lookupAll(".password-field").stream()
                .anyMatch(p -> keyOf.equals(p.getAccessibleText())));
            PasswordField key = FxWait.fx(() -> SettingsWindow.current().getScene().getRoot().lookupAll(".password-field").stream()
                .map(n -> (PasswordField) n).filter(p -> keyOf.equals(p.getAccessibleText())).findFirst().orElseThrow());
            FxWait.fx(() -> { key.setText("clave-" + where); return null; });
            assertEquals("clave-" + where, Settings.get().getString("gif." + gif.id() + ".key", null), where + ": kept here as before");
            FxWait.fx(() -> { SettingsWindow.current().close(); owner.close(); return null; });
        } finally {
            svc.stop();
        }
    }

    @Test
    void creatingShowsTheCodeAndAsksForTwoGroupsBack() throws Exception {
        page();
        FxWait.until("no vault yet", () -> hasButton(I18n.t("settings.vault.create")));
        click(I18n.t("settings.vault.create"));
        String code = codeShown();
        assertEquals(1, FxWait.fx(() -> (int) ((TextArea) root().lookup(".text-area.vault-code")).getText().chars().filter(ch -> ch == '\n').count()),
            "two lines, so 160 % text never cuts it");
        assertEquals(1, account.computers().size(), "made, with this computer in it");

        click(I18n.t("settings.vault.code.copy"));
        assertEquals(List.of(code), copied);
        click(I18n.t("settings.vault.code.save"));
        assertTrue(Files.readString(dir.resolve("codigo.txt")).contains(code), "saved as a text file");
        click(I18n.t("settings.vault.code.print"));
        assertTrue(printed.size() == 1 && printed.get(0).contains(code), "printed through the system's dialog");
        waitFor(I18n.t("settings.vault.code.printed"));

        typeGroups(code, false);
        waitFor(I18n.t("settings.vault.code.wrongGroups"));
        assertTrue(account.cloud().vaultView().get().codeUnconfirmed(), "not taken as kept");
        FxWait.fx(() -> { button(I18n.t("settings.vault.code.showAgain")).fire(); return null; });
        typeGroups(code, true);
        waitOpen();
        assertFalse(account.cloud().vaultView().get().codeUnconfirmed());
        assertTrue(FxWait.fx(() -> says(I18n.t("settings.vault.way.RECOVERY"))), "the ways in");
        click(I18n.t("settings.vault.password.add"));
        type(I18n.t("settings.vault.password.field"), PASSWORD);
        type(I18n.t("settings.vault.password.again"), PASSWORD);
        click(I18n.t("settings.vault.password.add"));
        FxWait.until("the password listed", () -> says(I18n.t("settings.vault.way.PASSWORD")) && !hasButton(I18n.t("settings.vault.password.add")));
        assertEquals(Set.of("PASSWORD", "RECOVERY"), account.ways());
    }

    @Test
    void stoppingBeforeTheGroupsSaysTheCodeMustBeKeptAndOffersANewOne() throws Exception {
        page();
        FxWait.until("no vault yet", () -> hasButton(I18n.t("settings.vault.create")));
        click(I18n.t("settings.vault.create"));
        String first = codeShown();
        click(I18n.t("folders.dialog.cancel"));
        waitFor(I18n.t("settings.vault.code.notKept"));
        click(I18n.t("settings.vault.newCode"));
        FxWait.until("a new code", () -> root().lookup(".text-area.vault-code") instanceof TextArea t && !t.getText().replace("\n", "").equals(first));
        typeGroups(codeShown(), true);
        waitOpen();
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.code.notKept"))));
    }

    @Test
    void aNewComputerOpensWithThePassword() throws Exception {
        account.otherComputerMadeIt(PASSWORD);
        page();
        waitFor(I18n.t("settings.vault.locked"));
        assertTrue(FxWait.fx(() -> field(I18n.t("settings.vault.open.field.PASSWORD")) instanceof PasswordField), "typed unseen");
        type(I18n.t("settings.vault.open.field.PASSWORD"), PASSWORD);
        click(I18n.t("settings.vault.open.with.PASSWORD"));
        waitOpen();
        assertTrue(account.computers().containsKey(VaultFixture.HERE));
        assertEquals(Cloud.VaultState.OPEN, round());
    }

    @Test
    void aWrongPasswordSaysSoAndChangesNothing() throws Exception {
        account.otherComputerMadeIt(PASSWORD);
        page();
        waitFor(I18n.t("settings.vault.locked"));
        Map<String, byte[]> before = account.files();
        type(I18n.t("settings.vault.open.field.PASSWORD"), "otra contraseña cualquiera");
        click(I18n.t("settings.vault.open.with.PASSWORD"));
        assertEquals("", FxWait.fx(() -> field(I18n.t("settings.vault.open.field.PASSWORD")).getText()), "emptied once it went");
        waitFor(I18n.t("cloud.error.vaultWrongSecret"));
        assertTrue(account.same(before), "nothing written");
        assertEquals(Cloud.VaultState.LOCKED, round());
        type(I18n.t("settings.vault.open.field.PASSWORD"), PASSWORD); // at once
        click(I18n.t("settings.vault.open.with.PASSWORD"));
        waitOpen();
    }

    @Test
    void aNewComputerOpensWithTheRecoveryCode() throws Exception {
        String code = account.otherComputerMadeIt(PASSWORD);
        page();
        waitFor(I18n.t("settings.vault.locked"));
        type(I18n.t("settings.vault.open.field.RECOVERY"), code.toLowerCase(java.util.Locale.ROOT).replace('-', ' '));
        FxWait.fx(() -> { field(I18n.t("settings.vault.open.field.RECOVERY")).fireEvent(new ActionEvent()); return null; }); // Enter
        waitOpen();
        assertEquals(Cloud.VaultState.OPEN, round());
    }

    @Test
    void aComputerRemovedFromTheVaultSaysSoAndOpensAgain() throws Exception {
        String code = account.otherComputerMadeIt(null);
        account.cloud().openVault(app.unichat.cloud.VaultView.Way.RECOVERY, code.toCharArray()).get();
        account.cloud().syncNow().get();
        account.removeHere();
        assertEquals(Cloud.VaultState.REMOVED, round());
        page();
        waitFor(I18n.t("settings.vault.removed"));
        assertFalse(FxWait.fx(() -> hasButton(I18n.t("settings.vault.decline"))));
        type(I18n.t("settings.vault.open.field.RECOVERY"), code);
        click(I18n.t("settings.vault.open.with.RECOVERY"));
        waitOpen();
    }

    /** This computer opened the vault another made, with a password and a laptop besides. */
    private String openedWithOthers() throws Exception {
        String code = account.otherComputerMadeIt(PASSWORD);
        account.anotherComputer(LAPTOP, "Portátil");
        account.cloud().openVault(app.unichat.cloud.VaultView.Way.RECOVERY, code.toCharArray()).get();
        assertEquals(Cloud.VaultState.OPEN, round()); // the others seen, and pinned, here
        return code;
    }

    @Test
    void computersAreListedAndOneCanBeRemoved() throws Exception {
        openedWithOthers();
        page();
        waitOpen();
        assertTrue(FxWait.fx(() -> says("Escritorio") && says("Portátil") && says(I18n.t("settings.vault.computer.here", ""))));
        assertTrue(FxWait.fx(() -> says(I18n.t("settings.vault.computer.seen", "Portátil", ""))), "and when first seen");
        clickNamed(I18n.t("settings.vault.computer.removeOf", "Portátil"));
        waitFor(I18n.t("settings.vault.change.receive"));
        assertTrue(FxWait.fx(() -> says("Escritorio") && says(I18n.t("settings.vault.change.removing", "Portátil"))), "who gets the new key, and who not");
        assertTrue(account.computers().containsKey(LAPTOP), "nothing before it's confirmed");
        click(I18n.t("settings.vault.change.confirmRemove"));
        codeShown();
        assertEquals(Set.of(VaultFixture.HERE, VaultFixture.OTHER), account.computers().keySet());
        waitFor(I18n.t("settings.vault.change.dropped", I18n.t("settings.vault.way.PASSWORD")));
        click(I18n.t("settings.vault.change.addAgain"));
        FxWait.until("the password asked again", () -> hasButton(I18n.t("settings.vault.password.add")) && says(I18n.t("settings.vault.password.help")));
    }

    @Test
    void aComputerWhoseKeyChangedGetsNothingUntilThePersonSaysItsTheirs() throws Exception {
        openedWithOthers();
        account.anotherComputer(VaultFixture.OTHER, "Escritorio"); // set up again: another key under the same machine
        page();
        waitFor(I18n.t("settings.vault.change.keyChanged", "Escritorio"));
        click(I18n.t("settings.vault.changeKey"));
        waitFor(I18n.t("settings.vault.change.refused", "Escritorio"));
        click(I18n.t("settings.vault.change.confirm"));
        codeShown();
        waitFor(I18n.t("settings.vault.change.refusedAfter", "Escritorio"));
        assertTrue(account.cloud().vaultView().get().computers().stream().filter(c -> c.machine().equals(VaultFixture.OTHER))
            .findFirst().orElseThrow().keyChanged(), "still refused");
    }

    @Test
    void aComputerWhoseKeyChangedIsAcceptedOnlyOnceThePersonSaysItsTheirs() throws Exception {
        openedWithOthers();
        account.anotherComputer(VaultFixture.OTHER, "Escritorio");
        page();
        waitFor(I18n.t("settings.vault.change.keyChanged", "Escritorio"));
        clickNamed(I18n.t("settings.vault.change.acceptOf", "Escritorio"));
        waitFor(I18n.t("settings.vault.change.acceptAsk", "Escritorio"));
        click(I18n.t("folders.dialog.cancel"));
        assertTrue(account.cloud().vaultView().get().computers().stream().filter(c -> c.machine().equals(VaultFixture.OTHER))
            .findFirst().orElseThrow().keyChanged(), "nothing without a yes");
        clickNamed(I18n.t("settings.vault.change.acceptOf", "Escritorio"));
        click(I18n.t("settings.vault.change.acceptYes"));
        // In the list the page is drawn again at once, so the "accepted" line may never be seen: wait for where it ends,
        // the question gone with it (while it shows, the warning doesn't).
        FxWait.until("accepted and drawn again", () -> says(I18n.t("settings.vault.open"))
            && !says(I18n.t("settings.vault.change.acceptAsk", "Escritorio")) && !says(I18n.t("settings.vault.change.accepted", "Escritorio"))
            && !says(I18n.t("settings.vault.change.keyChanged", "Escritorio")));
        assertFalse(account.cloud().vaultView().get().computers().stream().filter(c -> c.machine().equals(VaultFixture.OTHER))
            .findFirst().orElseThrow().keyChanged(), "accepted");
        click(I18n.t("settings.vault.changeKey"));
        waitFor(I18n.t("settings.vault.change.receive"));
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.change.refused", "Escritorio"))), "it gets the new key");
        click(I18n.t("settings.vault.change.confirm"));
        codeShown();
        FxWait.settled();
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.change.refusedAfter", "Escritorio"))));
    }

    /** Fires the button a screen reader names {@code accessible}. */
    private void clickNamed(String accessible) throws Exception {
        FxWait.fx(() -> {
            showing().stream().filter(n -> n instanceof ButtonBase b && accessible.equals(b.getAccessibleText())).map(n -> (ButtonBase) n)
                .findFirst().orElseThrow(() -> new AssertionError("no button named " + accessible)).fire();
            return null;
        });
    }

    @Test
    void aClosedWindowNoLongerHearsTheCloud() throws Exception {
        CloudPage.use(account.cloud());
        Stage s = FxWait.fx(() -> {
            Stage st = new Stage();
            st.setOpacity(0);
            st.setScene(new Scene(new ScrollPane(new VBox(CloudPage.build(t -> { }))), 720, 900));
            st.show();
            return st;
        });
        String before = I18n.t("settings.cloud.notYet");
        FxWait.until("the status line", () -> s.getScene().getRoot().lookupAll(".label").stream()
            .anyMatch(n -> before.equals(((Labeled) n).getText())));
        FxWait.fx(() -> { s.close(); return null; });
        account.cloud().syncNow().get();
        FxWait.settled();
        assertTrue(FxWait.fx(() -> s.getScene().getRoot().lookupAll(".label").stream().anyMatch(n -> before.equals(((Labeled) n).getText()))),
            "a round after it closed changes nothing in it");
    }

    @Test
    void aChangedKeyAcceptedInTheConfirmationGetsTheNewKey() throws Exception {
        String code = openedWithOthers();
        account.anotherComputer(VaultFixture.OTHER, "Escritorio"); // set up again: another key under the same machine
        page();
        waitOpen();
        click(I18n.t("settings.vault.changeKey"));
        waitFor(I18n.t("settings.vault.change.refused", "Escritorio"));
        clickNamed(I18n.t("settings.vault.change.acceptOf", "Escritorio"));
        click(I18n.t("settings.vault.change.acceptYes"));
        waitFor(I18n.t("settings.vault.change.accepted", "Escritorio"));
        click(I18n.t("settings.vault.change.confirm"));
        codeShown();
        FxWait.settled();
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.change.refusedAfter", "Escritorio"))), "not refused");
        assertTrue(account.offeredTheKey(VaultFixture.OTHER), "offered the new key: it opens on its next round");
        assertTrue(code != null);
    }

    @Test
    void aNewCodeThatCouldntBeNotedAsKeptAfterAChangeSaysSo() throws Exception {
        openedWithOthers();
        page();
        waitOpen();
        click(I18n.t("settings.vault.changeKey"));
        waitFor(I18n.t("settings.vault.change.receive"));
        click(I18n.t("settings.vault.change.confirm"));
        String code = codeShown();
        app.unichat.cloud.CloudAccount.forget(); // the account gone meanwhile: nothing can be noted
        typeGroups(code, true);
        waitFor(I18n.t("settings.vault.code.keptFailed", "").substring(0, 20));
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.code.keptLine"))));
    }

    @Test
    void aComputerAddedAfterThePageWasDrawnShowsUpFlaggedInTheConfirmation() throws Exception {
        openedWithOthers();
        page();
        waitOpen();
        account.anotherComputer(LATE, "Tardío"); // while the page shows: no round has seen it
        assertFalse(FxWait.fx(() -> says("Tardío")), "not drawn yet");
        click(I18n.t("settings.vault.changeKey"));
        waitFor(I18n.t("settings.vault.change.unseen", "Tardío"));
        click(I18n.t("settings.vault.change.confirm"));
        codeShown();
        FxWait.settled();
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.change.refusedAfter", "Tardío"))), "listed and confirmed: it gets the key");
    }

    @Test
    void aComputerAppearingBetweenConfirmingAndChangingGetsNothingAndIsNamed() throws Exception {
        openedWithOthers();
        page();
        waitOpen();
        click(I18n.t("settings.vault.changeKey"));
        waitFor(I18n.t("settings.vault.change.receive"));
        account.anotherComputer(LATE, "Tardío"); // after the list was shown
        click(I18n.t("settings.vault.change.confirm"));
        codeShown();
        waitFor(I18n.t("settings.vault.change.refusedAfter", "Tardío"));
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.change.refusedAfter", "Portátil"))), "the ones listed got it");
    }

    @Test
    void aCodeThatCouldntBeNotedAsKeptSaysSo() throws Exception {
        page();
        FxWait.until("no vault yet", () -> hasButton(I18n.t("settings.vault.create")));
        click(I18n.t("settings.vault.create"));
        String code = codeShown();
        app.unichat.cloud.CloudAccount.forget(); // the account gone meanwhile: nothing can be noted
        typeGroups(code, true);
        waitFor(I18n.t("settings.vault.code.keptFailed", "").substring(0, 20));
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.code.keptLine"))));
    }

    @Test
    void forgettingItHereAsksOnce() throws Exception {
        openedWithOthers();
        page();
        waitOpen();
        click(I18n.t("settings.vault.forget"));
        waitFor(I18n.t("settings.vault.forgetTitle"));
        assertTrue(account.computers().containsKey(VaultFixture.HERE), "only once confirmed");
        click(I18n.t("settings.vault.forgetYes"));
        waitFor(I18n.t("settings.vault.locked"));
        assertFalse(account.computers().containsKey(VaultFixture.HERE));
    }

    @Test
    void everyControlIsReachedByKeyboardAndNamed() throws Exception {
        List<String> bad = new ArrayList<>();
        page();
        FxWait.until("no vault yet", () -> hasButton(I18n.t("settings.vault.create")));
        bad.addAll(FxWait.fx(() -> unreachable("none")));
        click(I18n.t("settings.vault.create"));
        codeShown();
        bad.addAll(FxWait.fx(() -> unreachable("code")));
        click(I18n.t("settings.vault.code.keptIt"));
        bad.addAll(FxWait.fx(() -> unreachable("groups")));
        account.cloud().forgetHere().get();
        page();
        waitFor(I18n.t("settings.vault.locked"));
        bad.addAll(FxWait.fx(() -> unreachable("locked")));
        account.close();
        account = new VaultFixture(dir.resolve("again"));
        openedWithOthers();
        page();
        waitOpen();
        bad.addAll(FxWait.fx(() -> unreachable("open")));
        click(I18n.t("settings.vault.changeKey"));
        waitFor(I18n.t("settings.vault.change.receive"));
        bad.addAll(FxWait.fx(() -> unreachable("change")));
        assertTrue(bad.isEmpty(), "out of the keyboard's reach or unnamed:\n" + String.join("\n", bad));
    }

    /** Each control showing that Tab doesn't reach or that a screen reader can't name. */
    private List<String> unreachable(String where) {
        List<String> out = new ArrayList<>();
        for (Node n : showing()) {
            if (!(n instanceof ButtonBase) && !(n instanceof TextInputControl)) continue;
            String name = n.getAccessibleText() != null ? n.getAccessibleText() : n instanceof Labeled l ? l.getText() : null;
            if (!n.isFocusTraversable() || n.isDisabled()) out.add(where + ": not reached: " + name);
            if (name == null || name.isBlank()) out.add(where + ": no name: " + n);
        }
        return out;
    }

    @Test
    void itFitsAt160Percent() throws Exception {
        CloudPage.use(account.cloud());
        String scale = Settings.get().getString("ui.fontScale", null);
        MockChatService svc = new MockChatService();
        List<String> bad = new ArrayList<>();
        try {
            for (double fontScale : new double[] {1.0, 1.6}) {
                Settings.get().setFontScale(fontScale);
                bad.addAll(squeezedOnCloudPage(svc, "none @" + fontScale, null));
            }
            Settings.get().setFontScale(1.6);
            bad.addAll(squeezedOnCloudPage(svc, "code", I18n.t("settings.vault.create")));
            account.cloud().forgetHere().get();
            account.close();
            account = new VaultFixture(dir.resolve("fits"));
            CloudPage.use(account.cloud());
            String code = account.otherComputerMadeIt(PASSWORD);
            Settings.get().setFontScale(1.6);
            bad.addAll(squeezedOnCloudPage(svc, "locked", null));
            account.anotherComputer(LAPTOP, "Un portátil con un nombre bastante largo");
            account.cloud().openVault(app.unichat.cloud.VaultView.Way.RECOVERY, code.toCharArray()).get();
            account.cloud().syncNow().get();
            bad.addAll(squeezedOnCloudPage(svc, "open", null));
            bad.addAll(squeezedOnCloudPage(svc, "confirmation", I18n.t("settings.vault.changeKey")));
        } finally {
            Settings.get().set("ui.fontScale", scale);
            svc.stop();
        }
        assertTrue(bad.isEmpty(), "cut or squeezed:\n" + String.join("\n", bad));
    }

    /** The squeezed texts on Nube with the vault drawn, after pressing {@code press} if given (a step: the code, a confirmation). */
    private List<String> squeezedOnCloudPage(MockChatService svc, String where, String press) throws Exception {
        Stage owner = FxWait.fx(() -> {
            Stage o = new Stage();
            o.setOpacity(0);
            o.show();
            SettingsWindow.show(o, svc, () -> { }, () -> { }, id -> { }, t -> { });
            Theme.applyAll();
            SettingsWindow.select("cloud");
            return o;
        });
        FxWait.until("the vault drawn", () -> SettingsWindow.current().getScene().getRoot().lookup(".vault-page") instanceof VBox v
            && !v.getChildren().isEmpty() && v.lookupAll(".button").size() > 0);
        if (press != null) {
            FxWait.fx(() -> {
                ((ButtonBase) SettingsWindow.current().getScene().getRoot().lookupAll(".button").stream()
                    .filter(n -> press.equals(((ButtonBase) n).getText())).findFirst().orElseThrow()).fire();
                return null;
            });
            String receive = I18n.t("settings.vault.change.receive");
            FxWait.until("the step shown", () -> SettingsWindow.current().getScene().getRoot().lookup(".recovery-code-sheet") != null
                || SettingsWindow.current().getScene().getRoot().lookupAll(".label").stream().anyMatch(n -> receive.equals(((Labeled) n).getText())));
        }
        FxWait.settled();
        List<String> bad = FxWait.fx(() -> SettingsLayoutUiTest.squeezed(SettingsWindow.current().getScene().getRoot(), where));
        FxWait.fx(() -> { SettingsWindow.current().close(); owner.close(); return null; });
        return bad;
    }
}

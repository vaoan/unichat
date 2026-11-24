package app.unichat.ui.settings;

import app.unichat.cloud.Cloud;
import app.unichat.cloud.VaultFixture;
import app.unichat.gif.GifProviders;
import app.unichat.service.MockChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import app.unichat.ui.Theme;
import javafx.event.ActionEvent;
import javafx.scene.Node;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ajustes → Nube → Bóveda over an account in memory: making the vault (its code shown, copied, saved, printed, asked
 * back), opening it on a new computer or going on without it, a wrong secret, the computers and taking one out with
 * the key changed, a computer whose key changed, forgetting it here; by keyboard, named, and at 160 %.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class VaultPageUiTest extends VaultPageTestBase {
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
            account.cloud().vault().declineVault(true).get();
            assertEquals(Cloud.VaultState.DECLINED, round());
            keysByHand("declined");
        } finally {
            Settings.get().set("server.local", local);
        }
    }

    /** Telegram's keys and a GIF service's key typed in Ajustes and kept here, as before the vault. */
    private void keysByHand(String where) throws InterruptedException {
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
            FxWait.onFx(() -> SettingsWindow.select("networks"));
            FxWait.until("Telegram's keys", () -> SettingsWindow.current().getScene().getRoot().lookup(".telegram-api-id") != null);
            TextField id = FxWait.fx(() -> (TextField) SettingsWindow.current().getScene().getRoot().lookup(".telegram-api-id"));
            assertTrue(FxWait.fx(() -> id.isEditable() && !id.isDisabled()), where + ": Telegram's keys typed here");
            FxWait.onFx(() -> SettingsWindow.select("gifs"));
            String keyOf = I18n.t("settings.gifs.keyOf", gif.name());
            FxWait.until("the GIF key's field", () -> SettingsWindow.current().getScene().getRoot().lookupAll(".password-field").stream()
                .anyMatch(p -> keyOf.equals(p.getAccessibleText())));
            PasswordField key = FxWait.fx(() -> SettingsWindow.current().getScene().getRoot().lookupAll(".password-field").stream()
                .map(n -> (PasswordField) n).filter(p -> keyOf.equals(p.getAccessibleText())).findFirst().orElseThrow());
            FxWait.onFx(() -> key.setText("clave-" + where));
            assertEquals("clave-" + where, Settings.get().getString("gif." + gif.id() + ".key", null), where + ": kept here as before");
            FxWait.onFx(() -> {
                SettingsWindow.current().close();
                owner.close();
            });
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
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("codigo.txt"))),
                "nobody else on this computer reads it");
        }
        click(I18n.t("settings.vault.code.print"));
        assertTrue(printed.size() == 1 && printed.get(0).contains(code), "printed through the system's dialog");
        waitFor(I18n.t("settings.vault.code.printed"));

        typeGroups(code, false);
        waitFor(I18n.t("settings.vault.code.wrongGroups"));
        assertTrue(account.cloud().vault().vaultView().get().codeUnconfirmed(), "not taken as kept");
        FxWait.onFx(() -> button(I18n.t("settings.vault.code.showAgain")).fire());
        assertEquals(code, clip, "still copied while it's being kept");
        typeGroups(code, true);
        waitOpen();
        assertNull(clip, "the clipboard emptied once the code is known kept");
        assertFalse(account.cloud().vault().vaultView().get().codeUnconfirmed());
        assertTrue(FxWait.fx(() -> says(I18n.t("settings.vault.way.RECOVERY"))), "the ways in");
        click(I18n.t("settings.vault.password.add"));
        type(I18n.t("settings.vault.password.field"), PASSWORD);
        type(I18n.t("settings.vault.password.again"), PASSWORD);
        click(I18n.t("settings.vault.password.add"));
        FxWait.until("the password listed", () -> says(I18n.t("settings.vault.way.PASSWORD")) && !hasButton(I18n.t("settings.vault.password.add")));
        assertEquals(Set.of("PASSWORD", "RECOVERY"), account.ways());
    }

    /**
     * A vault made while media went up: the round's progress kept telling the state from before it, and the page read
     * the vault again at each tick (hours of a day's check: a request a tick, and the page drawn again under the person).
     */
    @Test
    void aVaultMadeWhileMediaGoesUpIsntReadAgainAtEachProgressTick() throws Exception {
        account.cloud().syncNow().get(); // connected: the page follows the rounds
        account.photos(6);
        VaultFixture.Held first = account.holdPhoto(0);
        VaultFixture.Held last = account.holdPhoto(5);
        var media = account.cloud().syncNow();
        first.arrived();
        page();
        FxWait.until("no vault yet", () -> hasButton(I18n.t("settings.vault.create")));
        click(I18n.t("settings.vault.create"));
        typeGroups(codeShown(), true);
        waitOpen();
        FxWait.settled();
        account.vaultJobsDone();
        long before = account.vaultReads();
        first.release();
        last.arrived(); // photos 1 to 4 went, each told as it went
        FxWait.settled();
        account.vaultJobsDone();
        assertEquals(0, account.vaultReads() - before, "no reload for a state the page already shows");
        assertTrue(FxWait.fx(() -> says(I18n.t("settings.vault.open"))), "still the open vault");
        last.release();
        assertEquals(Cloud.VaultState.OPEN, media.get().vault());
    }

    @Test
    void stoppingBeforeTheGroupsSaysTheCodeMustBeKeptAndOffersANewOne() throws Exception {
        page();
        FxWait.until("no vault yet", () -> hasButton(I18n.t("settings.vault.create")));
        click(I18n.t("settings.vault.create"));
        String first = codeShown();
        click(I18n.t("settings.vault.code.copy"));
        click(I18n.t("folders.dialog.cancel"));
        waitFor(I18n.t("settings.vault.code.notKept"));
        assertNull(clip, "the sheet gone: the code never left on the clipboard");
        click(I18n.t("settings.vault.newCode"));
        FxWait.until("a new code", () -> root().lookup(".text-area.vault-code") instanceof TextArea t && !t.getText().replace("\n", "").equals(first));
        typeGroups(codeShown(), true);
        waitOpen();
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.code.notKept"))));
    }

    @Test
    void whatThePersonCopiedOverTheCodeIsLeftOnTheClipboard() throws Exception {
        page();
        FxWait.until("no vault yet", () -> hasButton(I18n.t("settings.vault.create")));
        click(I18n.t("settings.vault.create"));
        String code = codeShown();
        click(I18n.t("settings.vault.code.copy"));
        clip = "algo suyo"; // pasted somewhere, then something else copied
        typeGroups(code, true);
        waitOpen();
        assertEquals("algo suyo", clip, "never theirs to empty");
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
        FxWait.onFx(() -> field(I18n.t("settings.vault.open.field.RECOVERY")).fireEvent(new ActionEvent()));  // Enter
        waitOpen();
        assertEquals(Cloud.VaultState.OPEN, round());
    }

    @Test
    void aComputerRemovedFromTheVaultSaysSoAndOpensAgain() throws Exception {
        String code = account.otherComputerMadeIt(null);
        account.cloud().vault().openVault(app.unichat.cloud.VaultView.Way.RECOVERY, code.toCharArray()).get();
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
    private String openedWithOthers() throws Exception { // NOPMD: SignatureDeclareThrowsException, it passes on Vault.Exists, which only Exception names outside cloud
        String code = account.otherComputerMadeIt(PASSWORD);
        account.anotherComputer(LAPTOP, "Portátil");
        account.cloud().vault().openVault(app.unichat.cloud.VaultView.Way.RECOVERY, code.toCharArray()).get();
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

    /**
     * A reload answered after a step began draws nothing: "Listo" asked for the vault again, "Agregar de nuevo" opened the
     * password form meanwhile, and the late answer drew the open page over it.
     */
    @Test
    void aLateReloadNeverDrawsOverAStepBegunSince() throws Exception {
        openedWithOthers();
        page();
        waitOpen();
        click(I18n.t("settings.vault.changeKey"));
        waitFor(I18n.t("settings.vault.change.receive"));
        click(I18n.t("settings.vault.change.confirm"));
        codeShown();
        waitFor(I18n.t("settings.vault.change.dropped", I18n.t("settings.vault.way.PASSWORD")));
        account.cloud().syncNow().get(); // the change's own round over: the next look at the vault is the reload's
        FxWait.settled();
        Runnable release = account.holdVaultReadOnce();
        click(I18n.t("settings.vault.change.done")); // the reload, held
        click(I18n.t("settings.vault.change.addAgain"));
        FxWait.until("the password asked", () -> hasButton(I18n.t("settings.vault.password.add")));
        release.run();
        account.cloud().vault().vaultView().get(); // the worker is one: the held answer came before this one
        FxWait.settled();
        assertTrue(FxWait.fx(() -> hasButton(I18n.t("settings.vault.password.add")) && says(I18n.t("settings.vault.password.help"))),
            "the form still there, not the open page drawn over it");
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
        assertTrue(account.cloud().vault().vaultView().get().computers().stream().filter(c -> c.machine().equals(VaultFixture.OTHER))
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
        assertTrue(account.cloud().vault().vaultView().get().computers().stream().filter(c -> c.machine().equals(VaultFixture.OTHER))
            .findFirst().orElseThrow().keyChanged(), "nothing without a yes");
        clickNamed(I18n.t("settings.vault.change.acceptOf", "Escritorio"));
        click(I18n.t("settings.vault.change.acceptYes"));
        // In the list the page is drawn again at once, so the "accepted" line may never be seen: wait for where it ends,
        // the question gone with it (while it shows, the warning doesn't).
        FxWait.until("accepted and drawn again", () -> says(I18n.t("settings.vault.open"))
            && !says(I18n.t("settings.vault.change.acceptAsk", "Escritorio")) && !says(I18n.t("settings.vault.change.accepted", "Escritorio"))
            && !says(I18n.t("settings.vault.change.keyChanged", "Escritorio")));
        assertFalse(account.cloud().vault().vaultView().get().computers().stream().filter(c -> c.machine().equals(VaultFixture.OTHER))
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
    private void clickNamed(String accessible) throws InterruptedException {
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
        FxWait.onFx(s::close);
        account.cloud().syncNow().get();
        FxWait.settled();
        assertTrue(FxWait.fx(() -> s.getScene().getRoot().lookupAll(".label").stream().anyMatch(n -> before.equals(((Labeled) n).getText()))),
            "a round after it closed changes nothing in it");
    }

    /** Nobody can see Unichat: the page stops hearing the cloud. Seen again, it shows what happened meanwhile at once. */
    @Test
    void theCloudsStatusChangedWhileUnseenShowsOnceSeenAgain() throws Exception {
        CloudPage.use(account.cloud());
        Stage s = FxWait.fx(() -> {
            Stage st = new Stage();
            st.setOpacity(0);
            st.setScene(new Scene(new ScrollPane(new VBox(CloudPage.build(t -> { }))), 720, 900));
            st.show();
            return st;
        });
        String before = I18n.t("settings.cloud.notYet");
        java.util.concurrent.Callable<Boolean> saysBefore = () -> s.getScene().getRoot().lookupAll(".label").stream()
            .anyMatch(n -> before.equals(((Labeled) n).getText()));
        FxWait.until("the status line", saysBefore);
        unseen();
        account.cloud().syncNow().get();
        FxWait.settled();
        assertTrue(FxWait.fx(saysBefore::call), "unseen: nothing heard");
        app.unichat.support.Seen.reset(true);
        FxWait.until("seen again: the round that went meanwhile", () -> !saysBefore.call());
        FxWait.onFx(s::close);
    }

    @Test
    void theVaultChangedWhileUnseenShowsOnceSeenAgain() throws Exception {
        String code = account.otherComputerMadeIt(null);
        account.cloud().vault().openVault(app.unichat.cloud.VaultView.Way.RECOVERY, code.toCharArray()).get();
        account.cloud().syncNow().get();
        page();
        waitOpen();
        unseen();
        account.removeHere();
        assertEquals(Cloud.VaultState.REMOVED, round());
        FxWait.settled();
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.removed"))), "unseen: nothing heard");
        app.unichat.support.Seen.reset(true);
        waitFor(I18n.t("settings.vault.removed"));
    }

    /** Nobody can see Unichat, and the pages have heard it ({@code Showing} hears it on the FX thread). */
    private static void unseen() throws InterruptedException {
        app.unichat.support.Seen.reset(false);
        FxWait.fx(() -> null);
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
        assertNotNull(code);
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
    void turningOffAsksTwice() throws Exception {
        openedWithOthers();
        page();
        waitOpen();
        click(I18n.t("settings.vault.turnOff"));
        waitFor(I18n.t("settings.vault.turnOffTitle"));
        assertTrue(FxWait.fx(() -> !says(I18n.t("settings.vault.turnOffWhat"))), "the first time, only whether");
        assertTrue(account.vaultThere(), "nothing yet");
        click(I18n.t("settings.vault.turnOffNext"));
        waitFor(I18n.t("settings.vault.turnOffWhat"));
        assertTrue(account.vaultThere(), "nor after the first yes");
        String what = I18n.t("settings.vault.turnOffWhat");
        assertTrue(what.contains("llaves") && what.contains("mensajes fijados") && what.contains("transcripciones"),
            "says what stays on each computer: " + what);
        click(I18n.t("folders.dialog.cancel"));
        waitOpen();
        assertTrue(account.vaultThere(), "cancelled: the vault as it was");

        click(I18n.t("settings.vault.turnOff"));
        click(I18n.t("settings.vault.turnOffNext"));
        click(I18n.t("settings.vault.turnOffYes"));
        FxWait.until("the vault off", () -> hasButton(I18n.t("settings.vault.create")));
        assertFalse(account.vaultThere(), "deleted from the account");
        assertEquals(Cloud.VaultState.NONE, round());
    }

    @Test
    void turningOffThatFailsSaysSoAndTriesAgain() throws Exception {
        openedWithOthers();
        page();
        waitOpen();
        account.failOnce(app.unichat.cloud.CloudAccount.SETTINGS_FILE);
        click(I18n.t("settings.vault.turnOff"));
        click(I18n.t("settings.vault.turnOffNext"));
        click(I18n.t("settings.vault.turnOffYes"));
        FxWait.until("the failure told", () -> says(I18n.t("settings.vault.turnOffFailed")) && hasButton(I18n.t("settings.vault.retry")));
        assertTrue(account.vaultThere(), "the vault left as it was");
        click(I18n.t("settings.vault.retry"));
        FxWait.until("the vault off", () -> hasButton(I18n.t("settings.vault.create")));
        assertFalse(account.vaultThere());
    }

    @Test
    void aVaultFolderThatWontGoIsStillOffAndSaysWhatsLeft() throws Exception {
        openedWithOthers();
        page();
        waitOpen();
        account.failVaultDeleteOnce();
        click(I18n.t("settings.vault.turnOff"));
        click(I18n.t("settings.vault.turnOffNext"));
        click(I18n.t("settings.vault.turnOffYes"));
        FxWait.until("off, and what's left told", () -> hasButton(I18n.t("settings.vault.create"))
            && says(I18n.t("cloud.error.vaultLeftovers")));
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.turnOffFailed"))), "never \"left as it was\"");
        assertEquals(Cloud.VaultState.NONE, round());
        FxWait.settled();
        assertTrue(FxWait.fx(() -> says(I18n.t("cloud.error.vaultLeftovers"))), "a round redrawing the page keeps it");
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
        account.cloud().vault().forgetHere().get();
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
        page();
        waitOpen();
        click(I18n.t("settings.vault.turnOff"));
        waitFor(I18n.t("settings.vault.turnOffTitle"));
        bad.addAll(FxWait.fx(() -> unreachable("turn off")));
        click(I18n.t("settings.vault.turnOffNext"));
        waitFor(I18n.t("settings.vault.turnOffWhat"));
        bad.addAll(FxWait.fx(() -> unreachable("turn off, what happens")));
        account.failOnce(app.unichat.cloud.CloudAccount.SETTINGS_FILE);
        click(I18n.t("settings.vault.turnOffYes"));
        FxWait.until("the failure told", () -> hasButton(I18n.t("settings.vault.retry")));
        bad.addAll(FxWait.fx(() -> unreachable("turn off failed")));
        Runnable release = account.holdOnce(app.unichat.cloud.CloudAccount.SETTINGS_FILE);
        click(I18n.t("settings.vault.retry"));
        waitFor(I18n.t("settings.vault.turnOffWorking"));
        bad.addAll(FxWait.fx(() -> unreachable("turning off")));
        release.run();
        FxWait.until("the vault off", () -> hasButton(I18n.t("settings.vault.create")));
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
                bad.addAll(squeezedOnCloudPage(svc, "none @" + fontScale));
            }
            Settings.get().setFontScale(1.6);
            bad.addAll(squeezedOnCloudPage(svc, "code", I18n.t("settings.vault.create")));
            account.cloud().vault().forgetHere().get();
            account.close();
            account = new VaultFixture(dir.resolve("fits"));
            CloudPage.use(account.cloud());
            String code = account.otherComputerMadeIt(PASSWORD);
            Settings.get().setFontScale(1.6);
            bad.addAll(squeezedOnCloudPage(svc, "locked"));
            account.anotherComputer(LAPTOP, "Un portátil con un nombre bastante largo");
            account.cloud().vault().openVault(app.unichat.cloud.VaultView.Way.RECOVERY, code.toCharArray()).get();
            account.cloud().syncNow().get();
            bad.addAll(squeezedOnCloudPage(svc, "open"));
            bad.addAll(squeezedOnCloudPage(svc, "confirmation", I18n.t("settings.vault.changeKey")));
            bad.addAll(squeezedOnCloudPage(svc, "turning off", I18n.t("settings.vault.turnOff"), I18n.t("settings.vault.turnOffNext")));
            String[] through = {I18n.t("settings.vault.turnOff"), I18n.t("settings.vault.turnOffNext"), I18n.t("settings.vault.turnOffYes")};
            account.failOnce(app.unichat.cloud.CloudAccount.SETTINGS_FILE);
            bad.addAll(squeezedUntil(svc, "turning off failed", I18n.t("settings.vault.turnOffFailed"), through));
            Runnable release = account.holdOnce(app.unichat.cloud.CloudAccount.SETTINGS_FILE);
            bad.addAll(squeezedUntil(svc, "turning off, working", I18n.t("settings.vault.turnOffWorking"), through));
            release.run();
            FxWait.until("the vault off", () -> !account.vaultThere());
        } finally {
            Settings.get().set("ui.fontScale", scale);
            svc.stop();
        }
        assertTrue(bad.isEmpty(), "cut or squeezed:\n" + String.join("\n", bad));
    }

    /** The squeezed texts on Nube with the vault drawn, after pressing {@code press} if given (a step: the code, a confirmation). */
    private List<String> squeezedOnCloudPage(MockChatService svc, String where, String... presses) throws InterruptedException {
        return squeezedUntil(svc, where, null, presses);
    }

    /** The same, measured once a label saying {@code until} shows (null: a code, or a confirmation). */
    private List<String> squeezedUntil(MockChatService svc, String where, String until, String... presses) throws InterruptedException {
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
            && !v.getChildren().isEmpty() && !v.lookupAll(".button").isEmpty());
        for (String press : presses) {
            FxWait.fx(() -> {
                ((ButtonBase) SettingsWindow.current().getScene().getRoot().lookupAll(".button").stream()
                    .filter(n -> press.equals(((ButtonBase) n).getText())).findFirst().orElseThrow()).fire();
                return null;
            });
        }
        if (presses.length > 0) {
            Set<String> steps = until != null ? Set.of(until) : Set.of(I18n.t("settings.vault.change.receive"), I18n.t("settings.vault.turnOffWhat"));
            FxWait.until("the step shown", () -> SettingsWindow.current().getScene().getRoot().lookup(".recovery-code-sheet") != null
                || SettingsWindow.current().getScene().getRoot().lookupAll(".label").stream().anyMatch(n -> steps.contains(((Labeled) n).getText())));
        }
        FxWait.settled();
        List<String> bad = FxWait.fx(() -> SettingsLayoutUiTest.squeezed(SettingsWindow.current().getScene().getRoot(), where));
        FxWait.onFx(() -> {
            SettingsWindow.current().close();
            owner.close();
        });
        return bad;
    }
}

package app.unichat.ui.settings;

import app.unichat.cloud.Cloud;
import app.unichat.cloud.VaultView;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ajustes → Nube → Bóveda when the account changed under the vault: a generation nobody here vouches for, the vault's
 * file gone without the vault turned off, a vault this computer can't open to be deleted, and forgetting it here while
 * its code isn't kept.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class VaultStatesUiTest extends VaultPageTestBase {
    @Test
    void aChangeNobodyVouchesForIsSaidAndAWayInStillOpensIt() throws Exception {
        String code = account.otherComputerMadeIt(null);
        account.cloud().vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()).get();
        assertEquals(Cloud.VaultState.OPEN, round());
        account.raiseGenerationByHand();
        assertEquals(Cloud.VaultState.UNRECOGNIZED, round());
        page();
        waitFor(I18n.t("settings.vault.unrecognized"));
        assertTrue(FxWait.fx(() -> !hasButton(I18n.t("settings.vault.decline"))), "nothing to decline: it's this computer's vault");
        type(I18n.t("settings.vault.open.field.RECOVERY"), code);
        click(I18n.t("settings.vault.open.with.RECOVERY"));
        waitOpen();
        assertEquals(Cloud.VaultState.OPEN, round());
    }

    @Test
    void aVaultFileGoneByHandSendsNothingUntilThePersonSaysAskedTwice() throws Exception {
        String code = account.otherComputerMadeIt(null);
        account.cloud().vault().openVault(VaultView.Way.RECOVERY, code.toCharArray()).get();
        assertEquals(Cloud.VaultState.OPEN, round());
        account.deleteVaultFileByHand();
        assertEquals(Cloud.VaultState.MISSING, round());
        page();
        waitFor(I18n.t("settings.vault.missing"));
        assertNotNull(Settings.get().getString("cloud.vault.key"), "its key kept meanwhile");
        click(I18n.t("settings.vault.missingLeave"));
        waitFor(I18n.t("settings.vault.missingAsk"));
        click(I18n.t("folders.dialog.cancel"));
        waitFor(I18n.t("settings.vault.missing"));
        assertEquals(Cloud.VaultState.MISSING, round(), "cancelled: nothing changed");
        click(I18n.t("settings.vault.missingLeave"));
        click(I18n.t("settings.vault.turnOffNext"));
        waitFor(I18n.t("settings.vault.missingWhat"));
        click(I18n.t("settings.vault.missingYes"));
        FxWait.until("no vault", () -> hasButton(I18n.t("settings.vault.create")));
        assertEquals(Cloud.VaultState.NONE, round(), "back to plain, as the person said");
    }

    @Test
    void aComputerThatDeclinedIsAskedBeforeSendingPlainWhenTheVaultsFileIsGone() throws Exception {
        account.otherComputerMadeIt(null);
        account.cloud().vault().declineVault(true).get();
        assertEquals(Cloud.VaultState.DECLINED, round());
        account.deleteVaultFileByHand();
        assertEquals(Cloud.VaultState.MISSING, round(), "it holds no key to believe the vault was turned off");
        page();
        waitFor(I18n.t("settings.vault.missing"));
        click(I18n.t("settings.vault.missingLeave"));
        waitFor(I18n.t("settings.vault.missingAsk"));
        click(I18n.t("settings.vault.turnOffNext"));
        waitFor(I18n.t("settings.vault.missingWhat"));
        click(I18n.t("settings.vault.missingYes"));
        FxWait.until("no vault", () -> hasButton(I18n.t("settings.vault.create")));
        assertEquals(Cloud.VaultState.NONE, round(), "back to plain, as the person said");
    }

    @Test
    void forgettingTheOnlyComputerWhileTheCodeIsntKeptWarns() throws Exception {
        page();
        FxWait.until("no vault yet", () -> hasButton(I18n.t("settings.vault.create")));
        click(I18n.t("settings.vault.create"));
        codeShown();
        click(I18n.t("folders.dialog.cancel"));
        waitFor(I18n.t("settings.vault.code.notKept"));
        click(I18n.t("settings.vault.forget"));
        waitFor(I18n.t("settings.vault.forgetOnly"));
        click(I18n.t("folders.dialog.cancel"));
        FxWait.until("the vault's page again", () -> hasButton(I18n.t("settings.vault.newCode")) && !says(I18n.t("settings.vault.forgetOnly")));
        click(I18n.t("settings.vault.newCode"));
        typeGroups(codeShown(), true);
        waitOpen();
        click(I18n.t("settings.vault.forget"));
        waitFor(I18n.t("settings.vault.forgetHelp"));
        assertFalse(FxWait.fx(() -> says(I18n.t("settings.vault.forgetOnly"))), "the code kept: nothing to warn of");
    }

    @Test
    void aVaultThisComputerCantOpenIsDeletedAskedTwice() throws Exception {
        account.otherComputerMadeIt(null);
        page();
        waitFor(I18n.t("settings.vault.locked"));
        click(I18n.t("settings.vault.deleteLocked"));
        waitFor(I18n.t("settings.vault.deleteLockedAsk"));
        click(I18n.t("settings.vault.turnOffNext"));
        waitFor(I18n.t("settings.vault.deleteLockedWhat"));
        assertTrue(account.vaultThere(), "nothing deleted before the second yes");
        click(I18n.t("settings.vault.deleteLockedYes"));
        FxWait.until("no vault", () -> hasButton(I18n.t("settings.vault.create")));
        assertFalse(account.vaultThere());
        assertEquals(Cloud.VaultState.NONE, round());
    }
}

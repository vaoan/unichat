package app.unichat.ui.settings;

import app.unichat.cloud.Cloud;
import app.unichat.cloud.VaultView;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

package app.unichat.ui.settings;

import app.unichat.Present;
import app.unichat.cloud.CloudAccount;
import app.unichat.cloud.VaultFixture;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Ajustes → Nube talks to the session's cloud as it is now: the page can outlive the cloud it was built with. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class CloudPageUiTest extends VaultPageTestBase {
    @TempDir
    Path later;

    @Test
    void aCloudChangedWhileThePageShowsIsTheOneItSyncs() throws Exception {
        CloudPage.use(account.cloud());
        stage = FxWait.fx(() -> {
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(new ScrollPane(new VBox(CloudPage.build(t -> { }))), 720, 900));
            s.show();
            return s;
        });
        waitFor(I18n.t("settings.cloud.disconnect"));
        try (VaultFixture next = new VaultFixture(later)) { // another session's cloud, its account in memory too
            String url = Present.of(CloudAccount.saved()).url();
            CloudPage.use(next.cloud());
            click(I18n.t("settings.cloud.disconnect"));
            click(I18n.t("settings.cloud.service.OTHER"));
            type(I18n.t("settings.cloud.url"), url);
            type(I18n.t("settings.cloud.user"), "u");
            type(I18n.t("settings.cloud.secret"), "p");
            click(I18n.t("settings.cloud.connect"));
            // Connected, it syncs once: the first cloud to have synced is the one it went to.
            FxWait.until("a round after connecting", () -> account.cloud().status().lastSync() != null
                    || next.cloud().status().lastSync() != null);
            assertNotNull(next.cloud().status().lastSync(), "the cloud of now synced");
            assertNull(account.cloud().status().lastSync(), "not the one the page was built with");
            waitFor(I18n.t("settings.cloud.syncNow")); // drawn again for the cloud of now
        }
    }
}

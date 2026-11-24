package app.unichat.ui.login;

import app.unichat.login.BridgeLogin;
import app.unichat.model.Network;
import app.unichat.service.BridgeAccess;
import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import app.unichat.ui.Theme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signing in through a bridge of the local server: what the pane says when the bridge's login is gone once prepared. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AccountsPaneBridgeUiTest {
    @BeforeAll
    static void toolkit() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
    }

    /** A local server whose bridge has a login until it's prepared, and none after (it stopped, it moved). */
    private static final class LostBridge extends MockChatService implements BridgeAccess {
        private volatile boolean prepared;

        @Override
        public @Nullable BridgeLogin bridgeLogin(Network n) {
            return prepared ? null : new BridgeLogin("http://127.0.0.1:9/_matrix/provision", "t", "@yo:localhost");
        }

        @Override
        public void prepareBridge(Network n) {
            prepared = true;
        }

        @Override
        public void loginFinished(Network n) {
            // never reached: no login finishes here
        }

        @Override
        public CompletableFuture<List<DiscordGuild>> discordGuilds() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Void> setDiscordGuildBridged(String guildId, boolean bridged) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void aBridgeWhoseLoginIsGoneOncePreparedSaysSo() throws Exception {
        LostBridge svc = new LostBridge();
        Stage[] stage = new Stage[1];
        AccountsPane pane = FxWait.fx(() -> {
            stage[0] = new Stage();
            stage[0].setOpacity(0);
            AccountsPane p = new AccountsPane(svc, t -> { }, id -> { });
            Scene sc = new Scene(p, 700, 600);
            Theme.register(sc);
            stage[0].setScene(sc);
            stage[0].show();
            return p;
        });
        try {
            FxWait.onFx(() -> pane.startLogin(Network.WHATSAPP));
            String failed = "No se pudo continuar con WhatsApp.";
            FxWait.until("the pane says it couldn't go on", () -> pane.lookupAll(".label").stream()
                    .anyMatch(n -> String.valueOf(((Label) n).getText()).startsWith(failed)));
            assertTrue(FxWait.fx(() -> pane.lookupAll(".label").stream()
                    .anyMatch(n -> String.valueOf(((Label) n).getText()).startsWith(failed))));
        } finally {
            FxWait.onFx(() -> {
                pane.dispose();
                stage[0].close();
            });
            svc.stop();
        }
    }
}

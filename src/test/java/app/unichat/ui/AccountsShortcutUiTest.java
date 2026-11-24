package app.unichat.ui;

import app.unichat.service.MockChatService;
import app.unichat.support.I18n;
import app.unichat.ui.desktop.Notifier;
import app.unichat.ui.settings.SettingsWindow;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** "Add an account" opens Settings on the accounts page by its English id, whatever the UI language. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AccountsShortcutUiTest {
    @Test
    void showAccountsOpensTheAccountsPageInEitherLanguage() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        String before = I18n.locale().getLanguage();
        MockChatService svc = new MockChatService();
        try {
            for (String lang : new String[] {"en", "es"}) {
                I18n.use(lang);
                Stage[] stage = new Stage[1];
                MainView view = FxWait.fx(() -> {
                    stage[0] = new Stage();
                    stage[0].setOpacity(0);
                    MainView v = new MainView(svc, stage[0], new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, u -> { });
                    stage[0].setScene(new Scene(v, 1000, 700));
                    stage[0].show();
                    return v;
                });
                FxWait.settled();
                FxWait.onFx(() -> {
                    view.showSettings();
                    SettingsWindow.select("about");
                });
                FxWait.fx(view::showAccounts);
                FxWait.until("the accounts page in " + lang, () -> "accounts".equals(SettingsWindow.currentPage()));
                assertEquals("accounts", FxWait.fx(SettingsWindow::currentPage));
                assertNotNull(FxWait.fx(SettingsWindow::accountsPage), lang);
                FxWait.fx(() -> {
                    SettingsWindow.current().close();
                    view.dispose();
                    stage[0].close();
                    return null;
                });
            }
        } finally {
            I18n.use(before);
            svc.stop();
        }
    }
}

package app.unichat.ui;

import app.unichat.login.BrowserSession;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountsPaneCookieTest {
    @Test
    void missingMessageNamesCookiesAndNetwork() {
        assertEquals("Al archivo le falta: auth_token, ct0. Exporta las cookies con la sesión de "
                + Network.X.displayName() + " abierta.",
                AccountsPane.missingMessage(Network.X, List.of("auth_token", "ct0")));
    }

    @Test
    void sessionLabelIncludesBrowserAndProfile() {
        assertEquals("Usar tu sesión de Instagram de Firefox (default)",
                AccountsPane.sessionLabel("Instagram", new BrowserSession("Firefox", "default", null)));
        assertEquals("Usar tu sesión de Instagram de Chrome",
                AccountsPane.sessionLabel("Instagram", new BrowserSession("Chrome", "", null)));
    }
}

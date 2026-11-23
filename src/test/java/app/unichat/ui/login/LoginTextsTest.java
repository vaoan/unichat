package app.unichat.ui.login;

import app.unichat.login.BrowserSession;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginTextsTest {
    @Test
    void missingMessageNamesCookiesAndNetwork() {
        assertEquals("Al archivo le falta: auth_token, ct0. Exporta las cookies con la sesión de "
                + Network.X.displayName() + " abierta.",
                LoginTexts.missingMessage(Network.X, List.of("auth_token", "ct0")));
    }

    /** The replies as the mautrix bridges write them (their sources), always in English: only those words are read. */
    @Test
    void loginRepliesAreReadByTheirEnglishTokens() {
        assertTrue(LoginTexts.loggedIn("Successfully logged in as +573001112233"));
        assertTrue(LoginTexts.loggedIn("Successfully logged into Acme as ana@acme.com"));
        assertTrue(LoginTexts.failed("Login failed: login cancelled while waiting for approval: context canceled"));
        assertTrue(LoginTexts.failed("Pairing timed out, please try again"));
        assertTrue(LoginTexts.failed("Invalid phone number, please try again"));
        assertTrue(LoginTexts.asksInput("Enter the login code Telegram sent you", Network.TELEGRAM));
        assertTrue(LoginTexts.asksInput("Incorrect password, please try again.", Network.TELEGRAM));
        assertFalse(LoginTexts.asksInput("Scan the QR code on your Signal app to log in", Network.SIGNAL));
        assertTrue(LoginTexts.asksInput("Enter a JSON object with your cookies", Network.INSTAGRAM), "off a QR network, always");
        // No bridge says these: a Spanish word is no sign of anything.
        assertFalse(LoginTexts.loggedIn("Sesión iniciada como Ana"));
        assertFalse(LoginTexts.asksInput("Escribe la contraseña", Network.TELEGRAM));
        assertFalse(LoginTexts.asksInput("Escribe el código", Network.WHATSAPP));
    }

    @Test
    void sessionLabelIncludesBrowserAndProfile() {
        assertEquals("Usar tu sesión de Instagram de Firefox (default)",
                LoginTexts.sessionLabel("Instagram", new BrowserSession("Firefox", "default", null)));
        assertEquals("Usar tu sesión de Instagram de Chrome",
                LoginTexts.sessionLabel("Instagram", new BrowserSession("Chrome", "", null)));
    }
}

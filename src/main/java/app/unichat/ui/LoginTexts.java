package app.unichat.ui;

import app.unichat.login.BrowserSession;
import app.unichat.model.Network;
import app.unichat.support.Errors;

import java.util.List;

/** What the login screens say, and which way each network signs in. */
final class LoginTexts {
    private LoginTexts() {}

    static String method(Network n) {
        return switch (n) {
            case WHATSAPP, SIGNAL, TELEGRAM, DISCORD -> "Escanea un código QR con tu teléfono";
            case INSTAGRAM, MESSENGER, X, LINKEDIN -> "Usa tu sesión del navegador o inicia sesión aquí";
            case GOOGLE_MESSAGES -> "Tu sesión de Google + confirmación en el teléfono";
            case SLACK -> "Tu correo y el código que te llega";
            default -> "";
        };
    }

    /** What to do on the phone / in the browser, in Spanish. */
    static String instructions(Network n) {
        return switch (n) {
            case WHATSAPP -> "En tu teléfono abre WhatsApp → Ajustes → Dispositivos vinculados → Vincular un dispositivo, y escanea el código.";
            case SIGNAL -> "En tu teléfono abre Signal → Ajustes → Dispositivos vinculados → +, y escanea el código.";
            case TELEGRAM -> "En tu teléfono abre Telegram → Ajustes → Dispositivos → Vincular dispositivo de escritorio, y escanea el código.";
            case DISCORD -> "En la app de Discord del teléfono toca tu perfil → Escanear código QR.";
            case INSTAGRAM, MESSENGER, X, LINKEDIN -> "1. Abre " + site(n) + " en tu navegador con tu sesión iniciada.\n"
                    + "2. Abre las herramientas de desarrollador (F12) → pestaña Red, y recarga la página.\n"
                    + "3. Clic derecho en cualquier petición a " + site(n) + " → Copiar → Copiar como cURL.\n"
                    + "4. Pega el texto abajo y pulsa Enviar.";
            case GOOGLE_MESSAGES -> "1. Abre messages.google.com en tu navegador con tu cuenta de Google.\n"
                    + "2. F12 → Red → recarga → clic derecho en una petición → Copiar como cURL, pégalo abajo.\n"
                    + "3. Después confirma el emoji que aparece en tu teléfono.";
            case SLACK -> "Escribe abajo el correo de tu cuenta de Slack. Luego te llegará un código: pégalo aquí también.";
            default -> "Sigue las indicaciones del puente.";
        };
    }

    private static String site(Network n) {
        return switch (n) {
            case INSTAGRAM -> "instagram.com";
            case MESSENGER -> "facebook.com";
            case X -> "x.com";
            case LINKEDIN -> "linkedin.com";
            default -> n.displayName();
        };
    }

    /** Card caption for a browser session, e.g. "Usar tu sesión de Instagram de Firefox (default)". */
    static String sessionLabel(String net, BrowserSession s) {
        String profile = s.profile() == null || s.profile().isBlank() ? "" : " (" + s.profile() + ")";
        return "Usar tu sesión de " + net + " de " + s.browser() + profile;
    }

    /** Message shown when a cookies.txt lacks required cookies. */
    static String missingMessage(Network n, List<String> missing) {
        return "Al archivo le falta: " + String.join(", ", missing)
                + ". Exporta las cookies con la sesión de " + n.displayName() + " abierta.";
    }

    static boolean qrNetwork(Network n) {
        return switch (n) {
            case WHATSAPP, SIGNAL, TELEGRAM, DISCORD -> true;
            default -> false;
        };
    }

    static String promptFor(Network n) {
        return switch (n) {
            case SLACK -> "Correo o código de verificación";
            case INSTAGRAM, MESSENGER, X, LINKEDIN, GOOGLE_MESSAGES -> "Pega aquí el cURL o las cookies";
            default -> "Código o contraseña que pida el puente";
        };
    }

    /** The deepest cause: what these screens show comes from a bridge's login step, wrapped in whatever carried it here. */
    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return Errors.message(t);
    }
}

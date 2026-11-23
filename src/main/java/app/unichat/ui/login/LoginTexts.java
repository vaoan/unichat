package app.unichat.ui.login;

import app.unichat.login.BrowserSession;
import app.unichat.model.Network;
import app.unichat.support.Errors;
import app.unichat.support.I18n;

import java.util.List;
import java.util.Locale;

/** What the login screens say, and which way each network signs in. */
final class LoginTexts {
    private LoginTexts() {}

    static String method(Network n) {
        return switch (n) {
            case WHATSAPP, SIGNAL, TELEGRAM, DISCORD -> I18n.t("login.method.qr");
            case INSTAGRAM, MESSENGER, X, LINKEDIN -> I18n.t("login.method.browser");
            case GOOGLE_MESSAGES -> I18n.t("login.method.google");
            case SLACK -> I18n.t("login.method.slack");
            default -> "";
        };
    }

    /** What to do on the phone / in the browser. */
    static String instructions(Network n) {
        return switch (n) {
            case WHATSAPP -> I18n.t("login.instr.whatsapp");
            case SIGNAL -> I18n.t("login.instr.signal");
            case TELEGRAM -> I18n.t("login.instr.telegram");
            case DISCORD -> I18n.t("login.instr.discord");
            case INSTAGRAM, MESSENGER, X, LINKEDIN -> I18n.t("login.instr.curl", site(n));
            case GOOGLE_MESSAGES -> I18n.t("login.instr.googleMessages");
            case SLACK -> I18n.t("login.instr.slack");
            default -> I18n.t("login.instr.default");
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

    /** Card caption for a browser session, e.g. "Use your Instagram session from Firefox (default)". */
    static String sessionLabel(String net, BrowserSession s) {
        String profile = s.profile() == null || s.profile().isBlank() ? "" : s.profile();
        return profile.isEmpty() ? I18n.t("login.session.label", net, s.browser())
                : I18n.t("login.session.labelProfile", net, s.browser(), profile);
    }

    /** Message shown when a cookies.txt lacks required cookies. */
    static String missingMessage(Network n, List<String> missing) {
        return I18n.t("login.cookies.missing", String.join(", ", missing), n.displayName());
    }

    static boolean qrNetwork(Network n) {
        return switch (n) {
            case WHATSAPP, SIGNAL, TELEGRAM, DISCORD -> true;
            default -> false;
        };
    }

    /**
     * Whether a bridge's reply says the login went through. The mautrix bridges answer only in English (their login
     * steps' texts), so only their words are read: "Successfully logged in as …", "Login failed: …", "… timed out".
     */
    static boolean loggedIn(String reply) {
        return reply.toLowerCase(Locale.ROOT).contains("successfully logged in");
    }

    /** Whether a bridge's reply says the login failed, was cancelled or ran out of time. */
    static boolean failed(String reply) {
        String b = reply.toLowerCase(Locale.ROOT);
        return b.contains("login failed") || b.contains("login cancelled") || b.contains("invalid") || b.contains("timed out");
    }

    /** Whether a bridge's reply asks for something typed: always off a QR network, a password or a code (not the QR's) on one. */
    static boolean asksInput(String reply, Network n) {
        String b = reply.toLowerCase(Locale.ROOT);
        return !qrNetwork(n) || b.contains("password") || (b.contains("code") && !b.contains("qr"));
    }

    static String promptFor(Network n) {
        return switch (n) {
            case SLACK -> I18n.t("login.prompt.slack");
            case INSTAGRAM, MESSENGER, X, LINKEDIN, GOOGLE_MESSAGES -> I18n.t("login.prompt.curl");
            default -> I18n.t("login.prompt.default");
        };
    }

    /** The deepest cause: what these screens show comes from a bridge's login step, wrapped in whatever carried it here. */
    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return Errors.message(t);
    }
}

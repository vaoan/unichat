package app.unichat.ui.login;

import app.unichat.model.Network;

/** Pure helpers for the in-app login window. */
public final class LoginUrls {
    private LoginUrls() {}

    /** Login page opened first for a cookie network. */
    public static String startUrl(Network network) {
        return switch (network) {
            case INSTAGRAM -> "https://www.instagram.com/accounts/login/";
            case MESSENGER -> "https://www.facebook.com/login/";
            case X -> "https://x.com/login";
            case LINKEDIN -> "https://www.linkedin.com/login";
            case GOOGLE_MESSAGES -> "https://messages.google.com/web/authentication";
            default -> throw new IllegalArgumentException("Not a cookie network: " + network);
        };
    }

    /** A current desktop Chrome UA; JavaFX's default one makes sites refuse to load. */
    public static String userAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    }
}

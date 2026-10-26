package app.unichat.login;

import java.util.regex.Pattern;

/**
 * Discord's account token, taken from discord.com after the user logs in inside Unichat's window (where they can
 * solve Discord's captcha, which the bridge's QR login can't show). Handed to the bridge with
 * {@code login-token user <token>}; the bridge deletes that message. Never logged.
 */
public final class DiscordToken {
    private DiscordToken() {}

    /**
     * Runs in the page. Discord removes {@code window.localStorage}, but a fresh iframe still reaches the same
     * storage; the token is stored as a JSON string. Null until the user is logged in.
     */
    public static final String EXTRACT_JS = """
            (function () {
              try {
                var f = document.createElement('iframe');
                f.style.display = 'none';
                document.body.appendChild(f);
                var raw = f.contentWindow.localStorage.getItem('token');
                f.remove();
                return raw ? JSON.parse(raw) : null;
              } catch (e) { return null; }
            })()""";

    /** user-id.timestamp.hmac (base64url parts), or the older "mfa." form. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{20,}|mfa\\.[A-Za-z0-9_-]{40,}");

    public static boolean isToken(String s) {
        return s != null && TOKEN.matcher(s).matches();
    }

    public static final String LOGIN_URL = "https://discord.com/login";
}

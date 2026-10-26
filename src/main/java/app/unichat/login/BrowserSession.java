package app.unichat.login;

/** A session found in one browser profile on this machine. Cookie values are never printed. */
public record BrowserSession(String browser, String profile, SessionCookies cookies) {
}

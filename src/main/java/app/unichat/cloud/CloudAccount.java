package app.unichat.cloud;

import app.unichat.settings.Settings;

/**
 * The person's storage account: which service, its WebDAV address, the login and the password or access key. Kept on
 * this computer only, beside the Matrix session ({@code cloud.} keys never leave it, {@link Settings}).
 */
public record CloudAccount(Service service, String url, String user, String secret) {
    /** The services with a known address; any other WebDAV is {@link #OTHER}, its address typed by the person. */
    public enum Service {
        /** Signed in through the browser ({@link PCloudLogin}): works with two-step verification; {@code url} is its API host. */
        PCLOUD(null),
        /** Signed in through the browser ({@link DropboxLogin}); {@code secret} is the lasting permission. */
        DROPBOX(null),
        ICEDRIVE("https://webdav.icedrive.io"),
        OTHER(null);

        public final String url;

        Service(String url) {
            this.url = url;
        }
    }

    /** Where in the account everything goes. */
    public static final String ROOT = "Unichat";
    public static final String MEDIA = ROOT + "/Multimedia";
    public static final String SETTINGS_FILE = ROOT + "/Ajustes/unichat-settings.json";

    /** The account saved on this computer, or null when none is connected. */
    public static CloudAccount saved() {
        Settings s = Settings.get();
        String url = s.getString("cloud.url", null), user = s.getString("cloud.user", null), secret = s.getString("cloud.secret", null);
        if (url == null || user == null || secret == null) return null;
        return new CloudAccount(s.getEnum("cloud.service", Service.OTHER), url, user, secret);
    }

    public void save() {
        Settings s = Settings.get();
        s.set("cloud.service", service.name());
        s.set("cloud.url", url);
        s.set("cloud.user", user);
        s.set("cloud.secret", secret);
    }

    /** Disconnected: the login goes; what's in the account and on this computer stays. */
    public static void forget() {
        Settings s = Settings.get();
        for (String k : new String[] {"cloud.service", "cloud.url", "cloud.user", "cloud.secret", "cloud.settingsBase"}) s.set(k, null);
    }

    /** pCloud and Dropbox through their own APIs (signed in in the browser); every other service through WebDAV. */
    public Storage storage() {
        return switch (service) {
            case PCLOUD -> new PCloudApi(url, secret);
            case DROPBOX -> new DropboxApi(DropboxLogin.appKey(), secret);
            case ICEDRIVE, OTHER -> new WebDav(url, user, secret);
        };
    }

    /** Upload what chats save (on unless turned off). */
    public static boolean media() { return Settings.get().getBool("cloud.media", true); }

    public static void setMedia(boolean on) { Settings.get().setBool("cloud.media", on); }

    /** Keep the settings the same on every computer connected to the account (on unless turned off). */
    public static boolean settings() { return Settings.get().getBool("cloud.settings", true); }

    public static void setSettings(boolean on) { Settings.get().setBool("cloud.settings", on); }

    @Override public String toString() {
        return "CloudAccount[" + service + ", " + url + ", " + user + "]"; // never the secret
    }
}

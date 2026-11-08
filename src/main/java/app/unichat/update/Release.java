package app.unichat.update;

/** A published version: its tag ("v1.2.0") and the page to download it from. */
public record Release(String version, String url) {
    /** The version as people read it: "1.2.0", without the tag's "v". */
    public String shown() {
        return version.length() > 1 && (version.charAt(0) == 'v' || version.charAt(0) == 'V') ? version.substring(1) : version;
    }
}

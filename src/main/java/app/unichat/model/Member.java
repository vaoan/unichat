package app.unichat.model;

/** A participant of a chat. {@code avatarUrl} may be an mxc://, http(s):// or file: URL, or null. */
public record Member(String id, String name, String avatarUrl) {
    public String displayName() { return name == null || name.isBlank() ? id : name; }
}

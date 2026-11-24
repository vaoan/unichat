package app.unichat.model;

import org.jspecify.annotations.Nullable;

/** A participant of a chat. {@code avatarUrl} may be an mxc://, http(s):// or file: URL, or null. */
public record Member(String id, @Nullable String name, @Nullable String avatarUrl) {
    public String displayName() { return name == null || name.isBlank() ? id : name; }
}

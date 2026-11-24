package app.unichat.model;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A message pinned in a chat, with what it said and who sent it when it was pinned: shown on the chat's bar however
 * far back it is, loaded or not. A time is null when the file holding it didn't have it.
 */
public record PinnedMessage(String messageId, String sender, String preview, @Nullable Instant sentAt,
                            @Nullable Instant pinnedAt) {}

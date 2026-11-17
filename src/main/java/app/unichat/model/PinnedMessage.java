package app.unichat.model;

import java.time.Instant;

/**
 * A message pinned in a chat, with what it said and who sent it when it was pinned: shown on the chat's bar however
 * far back it is, loaded or not.
 */
public record PinnedMessage(String messageId, String sender, String preview, Instant sentAt, Instant pinnedAt) {}

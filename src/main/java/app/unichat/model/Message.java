package app.unichat.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One message of a chat. Mutable: services update it in place (edits, reactions, status)
 * and then fire {@link app.unichat.service.ChatService.Listener#messagesChanged}.
 */
public final class Message {
    private final String id;
    private final String chatId;
    private final String senderId;
    private String senderName;
    private final boolean outgoing;
    private final Instant timestamp;
    private MessageType type = MessageType.TEXT;
    private String body = "";
    private String mediaUrl;
    private String fileName;
    private String mimeType;
    private long fileSize;
    private int width;
    private int height;
    private String replyToId;
    private boolean edited;
    private boolean deleted;
    private MessageStatus status = MessageStatus.SENT;
    /** emoji -> user ids that reacted with it, in insertion order. */
    private final Map<String, Set<String>> reactions = new LinkedHashMap<>();

    public Message(String id, String chatId, String senderId, String senderName, boolean outgoing, Instant timestamp) {
        this.id = id;
        this.chatId = chatId;
        this.senderId = senderId;
        this.senderName = senderName;
        this.outgoing = outgoing;
        this.timestamp = timestamp;
    }

    public String id() { return id; }
    public String chatId() { return chatId; }
    public String senderId() { return senderId; }
    public String senderName() { return senderName == null || senderName.isBlank() ? senderId : senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public boolean outgoing() { return outgoing; }
    public Instant timestamp() { return timestamp; }
    public MessageType type() { return type; }
    public void setType(MessageType type) { this.type = type; }
    public String body() { return body; }
    public void setBody(String body) { this.body = body == null ? "" : body; }
    public String mediaUrl() { return mediaUrl; }
    public void setMediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; }
    /** The event's original {@code info} (e.g. fi.mau.bridged_sticker), kept so a sticker can be sent again natively. */
    private com.fasterxml.jackson.databind.JsonNode mediaInfo;
    public com.fasterxml.jackson.databind.JsonNode mediaInfo() { return mediaInfo; }
    public void setMediaInfo(com.fasterxml.jackson.databind.JsonNode info) { this.mediaInfo = info; }

    public String fileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    /** MIME type of the media (e.g. {@code image/webp}), or null when unknown. */
    public String mimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long fileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public int width() { return width; }
    public int height() { return height; }
    public void setSize(int width, int height) { this.width = width; this.height = height; }
    public String replyToId() { return replyToId; }
    public void setReplyToId(String replyToId) { this.replyToId = replyToId; }
    public boolean edited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }
    public boolean deleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public MessageStatus status() { return status; }
    public void setStatus(MessageStatus status) { this.status = status; }
    public Map<String, Set<String>> reactions() { return reactions; }

    public boolean isMedia() {
        return type == MessageType.IMAGE || type == MessageType.VIDEO || type == MessageType.AUDIO
                || type == MessageType.FILE || type == MessageType.STICKER;
    }

    /** Adds or removes {@code userId}'s reaction; returns true if it is now present. */
    public boolean toggleReaction(String emoji, String userId) {
        Set<String> users = reactions.computeIfAbsent(emoji, k -> new LinkedHashSet<>());
        boolean added = users.add(userId);
        if (!added) users.remove(userId);
        if (users.isEmpty()) reactions.remove(emoji);
        return added;
    }

    /** One-line text for chat list previews and notifications. */
    public String preview() {
        if (deleted) return "Mensaje eliminado";
        return switch (type) {
            case IMAGE -> "📷 Foto";
            case VIDEO -> "🎬 Video";
            case AUDIO -> "🎤 Audio";
            case FILE -> "📎 " + (fileName == null ? "Archivo" : fileName);
            case STICKER -> "Sticker";
            case EMOTE -> "* " + senderName() + " " + body;
            default -> body.replace('\n', ' ');
        };
    }
}

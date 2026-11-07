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
    private String externalUrl;
    /** Where the message lives on its network, when the bridge says (an Instagram reel's page). */
    public String externalUrl() { return externalUrl; }
    public void setExternalUrl(String url) { this.externalUrl = url; }

    /** Past this, a video waits to be opened: it may be a whole clip, and it would download by itself. */
    private static final long INLINE_MAX_BYTES = 2L * 1024 * 1024;
    private static final long INLINE_MAX_MS = 15_000;

    /**
     * Whether a video plays in the timeline by itself (muted, looping), like a GIF: GIFs that bridges deliver as video,
     * stickers that came as video, and short clips, going by the size and duration the bridge reports.
     */
    public boolean playsInline() {
        if (mediaUrl == null) return false;
        if (type == MessageType.STICKER) return mimeType != null && mimeType.startsWith("video/");
        if (type != MessageType.VIDEO || mediaInfo == null) return false;
        if (mediaInfo.path("fi.mau.gif").asBoolean(false)) return true;
        long ms = mediaInfo.path("duration").asLong(0);
        return fileSize > 0 && fileSize <= INLINE_MAX_BYTES && ms <= INLINE_MAX_MS;
    }

    /** A reel that came only as its picture and a link: the bridge has no video to give, its page does. */
    public boolean isReelLink() {
        return type == MessageType.IMAGE && externalUrl != null
                && externalUrl.matches("https://(www\\.)?(instagram|facebook)\\.com/(reel|reels)/.*");
    }

    /** How long the audio or video lasts, as the bridge reports it; 0 when it doesn't. */
    public long durationMs() {
        return mediaInfo == null ? 0 : mediaInfo.path("duration").asLong(0);
    }

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

    /** Changes when anything drawn from this message does. Rows ask it for every message each time one arrives, so it stays cheap. */
    public int revision() {
        int reacted;
        try {
            reacted = reactions.hashCode();
        } catch (java.util.ConcurrentModificationException changing) {
            reacted = System.identityHashCode(new Object()); // the sync thread is changing them: it will say so again
        }
        return java.util.Objects.hash(type, body, senderName, mediaUrl, fileName, mimeType, fileSize, width, height, replyToId, edited,
                deleted, status, reacted, html, previewHtml, htmlText, externalUrl, mediaInfo);
    }

    private final Map<String, String> reactionNames = new java.util.HashMap<>();

    /** Custom emoji reactions have a picture's address for a key, and maybe a name (Discord's ":orclove:"). */
    public void setReactionName(String key, String name) { reactionNames.put(key, name); }

    /** What to call a reaction in words; never its address. */
    public String reactionLabel(String key) {
        if (!isCustomReaction(key)) return key;
        return reactionNames.getOrDefault(key, "emoji personalizado");
    }

    public static boolean isCustomReaction(String key) { return key != null && key.startsWith("mxc://"); }

    private String html;
    private String previewHtml;
    private String htmlText;
    /** The message as HTML, when it came so ({@code ui.HtmlView} draws it); {@link #body()} remains its plain text. */
    public String html() { return html; }
    public void setHtml(String html) { this.html = html; }
    /** Link previews, as HTML, shown under the message. */
    public String previewHtml() { return previewHtml; }
    public void setPreviewHtml(String previewHtml) { this.previewHtml = previewHtml; }
    /** In words, what the HTML shows that the body doesn't say (an embed); null if nothing. */
    public String htmlText() { return htmlText; }
    public void setHtmlText(String htmlText) { this.htmlText = htmlText; }

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
            default -> body.isBlank() && htmlText != null ? htmlText : body.replace('\n', ' ');
        };
    }
}

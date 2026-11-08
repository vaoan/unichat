package app.unichat.ui;

import app.unichat.model.Message;

import java.util.Locale;

/**
 * Which picture a file in the chat shows instead of its icon, as Telegram does: the thumbnail the sender attached (a
 * video's, a PDF's), else the file itself when it's a picture (a photo sent as a document). Pure, tested.
 */
final class FilePreview {
    /** A picture bigger than this is only downloaded when opened: with direct media a "thumbnail" is the whole file. */
    static final long MAX_IMAGE_BYTES = 15L * 1024 * 1024;

    private FilePreview() {
    }

    /** The URL to show in the file's chip, or null for the icon. */
    static String url(Message m) {
        if (m.mediaInfo() != null) {
            String thumb = m.mediaInfo().path("thumbnail_url").asText("");
            if (thumb.startsWith("mxc://")) return thumb;
        }
        if (m.mediaUrl() == null || !isPicture(m)) return null;
        return m.fileSize() > MAX_IMAGE_BYTES ? null : m.mediaUrl();
    }

    /**
     * The same file whatever message holds it: our copy while it goes up and the server's once it's there share name
     * and size (and the attached thumbnail, when there is one).
     */
    static String key(Message m) {
        return m.fileName() + "|" + m.fileSize() + "|" + (m.mediaInfo() == null ? "" : m.mediaInfo().path("thumbnail_url").asText(""));
    }

    /** By the MIME it came with, else by the name: documents often come as application/octet-stream. */
    static boolean isPicture(Message m) {
        String mime = m.mimeType() == null ? "" : m.mimeType().toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/")) return !mime.contains("heic") && !mime.contains("heif") && !mime.contains("svg");
        String name = m.fileName() == null ? "" : m.fileName().toLowerCase(Locale.ROOT);
        return name.matches(".*\\.(jpe?g|png|gif|webp|bmp)$");
    }
}

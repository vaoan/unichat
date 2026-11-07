package app.unichat.model;

import java.util.Locale;

/** Media types, as bridges and servers write them. */
public final class MimeTypes {
    private MimeTypes() {}

    /** The extension for a type, whatever parameters it carries ({@code audio/ogg; codecs=opus}); "" when unknown. */
    public static String extension(String mimeType) {
        if (mimeType == null) return "";
        int parameters = mimeType.indexOf(';');
        String type = (parameters < 0 ? mimeType : mimeType.substring(0, parameters)).strip().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            case "video/x-m4v" -> ".m4v";
            case "audio/ogg" -> ".ogg";
            case "audio/mpeg" -> ".mp3";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }
}

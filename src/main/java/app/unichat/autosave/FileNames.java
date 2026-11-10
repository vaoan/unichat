package app.unichat.autosave;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.MimeTypes;
import app.unichat.support.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** Names for saved files and folders that are safe on any system, and never step outside the folder. Pure. */
public final class FileNames {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern RESERVED = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

    private FileNames() {}

    /**
     * A message's file: the original name without any folder part, or one made from the kind and the message time
     * ("imagen-20261026-100405.jpg"). Stickers always get the made-up name.
     */
    public static String forMessage(Message m, ZoneId zone) {
        String name = m.fileName() == null ? "" : m.fileName().replace('\\', '/');
        name = safe(name.substring(name.lastIndexOf('/') + 1).strip());
        String ext = MimeTypes.extension(m.mimeType());
        if (name.isEmpty() || name.equals(".") || name.equals("..") || m.type() == MessageType.STICKER) {
            String kind = switch (m.type()) {
                case IMAGE -> I18n.t("saver.kind.image");
                case VIDEO -> I18n.t("saver.kind.video");
                case AUDIO -> I18n.t("saver.kind.audio");
                case STICKER -> I18n.t("saver.kind.sticker");
                default -> I18n.t("saver.kind.file");
            };
            return kind + "-" + STAMP.format(m.timestamp().atZone(zone)) + ext;
        }
        return name.contains(".") || ext.isEmpty() ? name : name + ext;
    }

    /**
     * The name a saved file gets: {@link #forMessage} with the message's Matrix id before the extension
     * ("IMG_3944 [abc123].MOV"), so two files called the same never overwrite each other, the name still says which file
     * it is, and a file saved before is known again ({@link #savedIn}).
     */
    public static String forSaving(Message m, ZoneId zone) {
        String name = forMessage(m, zone);
        int dot = name.lastIndexOf('.');
        String tag = " [" + tag(m) + "]";
        String ext = dot > 0 ? name.substring(dot) : "";
        String saved = dot > 0 ? name.substring(0, dot) + tag + ext : name + tag;
        return SHELL_ACTIVE.contains(ext.toLowerCase(java.util.Locale.ROOT)) ? saved + ".txt" : saved;
    }

    /**
     * What Windows' Explorer acts on just by showing the folder (an icon or a location read from inside the file): a
     * {@code \\host\share} there sends the login's NTLM hash to whoever sent it. Saved with ".txt" after, on every system
     * (the folder may be synced to a Windows machine): still readable, inert.
     */
    private static final java.util.Set<String> SHELL_ACTIVE = java.util.Set.of(".url", ".lnk", ".scf", ".library-ms",
            ".searchconnector-ms", ".search-ms", ".website", ".theme", ".themepack", ".deskthemepack", ".appref-ms",
            ".settingcontent-ms");

    /** The message's id as it goes in a name: without Matrix's "$", safe for any disk, at most 60 characters. */
    public static String tag(Message m) {
        String id = safe(m.id().startsWith("$") ? m.id().substring(1) : m.id());
        return id.length() > 60 ? id.substring(0, 60) : id;
    }

    /** Whether something of {@code m} is in {@code dir} already (whole: not hidden nor half-written). */
    public static boolean savedIn(Path dir, Message m) {
        if (!Files.isDirectory(dir)) return false;
        String mark = "[" + tag(m) + "]";
        try (var in = Files.newDirectoryStream(dir)) {
            for (Path p : in) {
                String n = p.getFileName().toString();
                if (n.contains(mark) && !n.startsWith(".") && !n.endsWith(".part")) return true;
            }
        } catch (java.io.IOException e) {
            return false;
        }
        return false;
    }

    /**
     * Windows reads "C:x" as relative to drive C and "a:b" as a hidden stream, rejects {@code <>"|?*}, control
     * characters, trailing dots/spaces and DOS device names: clean all of those, slashes too, and keep it under 200.
     * Invisible format characters go ({@code \p{Cf}}: a right-to-left override, U+202E, showed "invoice[RLO]fdp.exe" as a PDF),
     * and leading dots too: a hidden file was skipped by {@link #savedIn} and folder moves, so saved again each time.
     */
    public static String safe(String name) {
        String n = name.replaceAll("\\p{Cf}", "").replaceAll("[<>:\"|?*/\\\\\\x00-\\x1F]", "_")
                .replaceAll("[. ]+$", "").replaceAll("^[. ]+", "").strip();
        if (RESERVED.matcher(n).matches()) n = "_" + n;
        if (n.length() > 200) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 200 - ext.length()) + ext;
        }
        return n;
    }

    /** A folder named after a chat: {@link #safe}, and never empty nor "..". */
    public static String folder(String chatName) {
        String n = safe(chatName == null ? "" : chatName);
        return n.isEmpty() || n.equals("..") ? "_" : n;
    }

    /** {@code name} in {@code dir}, or "name (2).ext", "name (3).ext"… when it's taken: nothing saved is overwritten. */
    public static Path free(Path dir, String name) {
        Path p = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(base + " (" + i + ")" + ext);
        return p;
    }
}

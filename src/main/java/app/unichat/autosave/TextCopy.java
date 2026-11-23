package app.unichat.autosave;

import app.unichat.model.Chat;
import app.unichat.support.Lines;
import app.unichat.support.SafeFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A chat's texts as a person reads them, in its folder beside what auto-save keeps: made again from every computer's
 * ({@link TextLog#merged}), never the source, so it can be read, searched or copied from without Unichat. Each entry is
 * "when · who · {message}" with its text set in two spaces: no line of a text, nor a name, can pass for another entry.
 * A file kept on disk, not UI: English in every UI language.
 */
final class TextCopy {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String HEADER = "What was said in this chat, copied by Unichat: each message, and each version if it was edited.";

    private TextCopy() {}

    /** "Chat (WhatsApp) 3f2a9c1b2d4e.txt": two chats of the same name, in one folder or not, never share it. */
    static String name(Chat chat) {
        return "Chat (" + chat.network().displayName() + ") " + TextLog.key(chat.id()) + ".txt";
    }

    /** Whether {@code file} is a copy this makes: the cloud has its source, so it doesn't go up as media. */
    static boolean isCopy(Path file) {
        return file.getFileName().toString().matches("Chat \\([^()/\\\\]+\\) [0-9a-f]{12}\\.txt");
    }

    /** Writes it whole, in place of the one before. */
    static void write(Chat chat, List<SavedText> said) throws IOException {
        Path folder = Files.createDirectories(SaveFolders.of(chat));
        SafeFile.at(folder.resolve(name(chat)), ".part").hidden().write(render(chat, said, ZoneId.systemDefault()).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code said} in the order given, a later version of a message marked as edited, at the time it was seen. */
    static String render(Chat chat, List<SavedText> said, ZoneId zone) {
        StringBuilder out = new StringBuilder();
        out.append("# Unichat · ").append(oneLine(chat.title())).append(" · ").append(chat.network().displayName()).append(" · ")
                .append(chat.id()).append('\n').append("# ").append(HEADER).append('\n');
        List<SavedText.Mark> marks = SavedText.marks(said);
        for (int i = 0; i < said.size(); i++) {
            SavedText t = said.get(i);
            SavedText.Mark mark = marks.get(i);
            boolean asSent = mark == SavedText.Mark.SENT || mark == SavedText.Mark.WRITTEN;
            out.append('\n').append(WHEN.format((asSent ? t.at() : t.seen()).atZone(zone))).append(" · ").append(oneLine(t.from()))
                    .append(mark == SavedText.Mark.SENT ? "" : " · " + said(mark))
                    .append(" · {").append(t.id()).append("}\n");
            for (String line : t.text().split("\\R", -1)) out.append("  ").append(shown(line)).append('\n');
        }
        return out.toString();
    }

    private static String said(SavedText.Mark mark) {
        return switch (mark) {
            case EDITED -> "edited";
            case WRITTEN -> "written before AI correction";
            case CORRECTED -> "corrected with AI";
            case SENT -> "";
        };
    }

    /** A name on one line ({@link Lines#one}), the separator a dash: "Ana · edited" would read as an edit. */
    private static String oneLine(String s) {
        return Lines.one(s).replace('·', '-');
    }

    /**
     * A line of a text as it's shown, not obeyed: a control but the tab (a terminal's escape, a backspace) or a
     * direction override is "\uFFFD", or one would rub out the indent and draw a line that reads as another entry.
     */
    private static String shown(String line) {
        return line.replaceAll("[\\p{Cc}&&[^\\t]]|[\\u202A-\\u202E\\u2066-\\u2069]", "\uFFFD");
    }
}

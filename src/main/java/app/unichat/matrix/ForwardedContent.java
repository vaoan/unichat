package app.unichat.matrix;

import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.List;

/**
 * The event of a forwarded copy of a message ({@code service/Forwarding}): what it showed, as one's own, though a
 * stranger wrote it. It answers nothing, mentions no one (forwarded to their network, a mention would ping them), runs
 * no bridge command, hides no part of a file's name and carries no text that wasn't shown. Pure.
 */
final class ForwardedContent {
    /** What {@code ui/timeline/HtmlView} never shows, contents and all: none of it goes out as one's own. */
    private static final String UNSHOWN = "mx-reply, script, style, head, title, meta, link, iframe, object, embed, noscript, template, video, audio";
    /** Invisible marks that turn text around ("factura‮fdp.exe" shows as "facturaexe.pdf"). */
    private static final String BIDI = "[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F]";

    private ForwardedContent() {}

    /** {@code copied}: its file goes as a copy on our server, so nothing of the original's bridge (its thumbnail) stays. */
    static ObjectNode of(Message m, boolean copied) {
        ObjectNode c = MatrixClient.JSON.createObjectNode();
        c.putObject("m.mentions");
        if (!m.isMedia()) {
            c.put("msgtype", m.type() == app.unichat.model.MessageType.EMOTE ? "m.emote" : "m.text");
            c.put("body", notACommand(m.body()));
            if (m.html() != null) c.put("format", "org.matrix.custom.html").put("formatted_body", withoutMentions(m.html()));
            return c;
        }
        c.put("msgtype", switch (m.type()) {
            case IMAGE -> "m.image";
            case VIDEO -> "m.video";
            case AUDIO -> "m.audio";
            default -> "m.file";
        });
        String name = fileName(m);
        c.put("body", notACommand(m.body().isEmpty() ? name : m.body().replaceAll(BIDI, "")));
        if (m.fileName() != null) c.put("filename", name);
        c.put("url", m.mediaUrl());
        ObjectNode info = m.mediaInfo() instanceof ObjectNode o ? o.deepCopy() : c.objectNode();
        if (m.mimeType() != null) info.put("mimetype", m.mimeType());
        if (m.fileSize() > 0) info.put("size", m.fileSize());
        if (m.width() > 0) info.put("w", m.width()).put("h", m.height());
        if (copied) info.remove(List.of("thumbnail_url", "thumbnail_info"));
        info.remove(MediaLibrary.ORIGINAL); // trusted on one's own messages: a stranger's would name what goes next time
        c.set("info", info);
        return c;
    }

    /** A file's name, its invisible marks taken out, or "file". */
    static String fileName(Message m) {
        return m.fileName() == null ? "file" : m.fileName().replaceAll("\\p{Cf}", "");
    }

    /**
     * Bridges run as an admin command any message of one's own starting with their prefix ("!whatsapp …"), in any room:
     * a zero-width space in front keeps a stranger's text text.
     */
    static String notACommand(String text) {
        return text.startsWith("!") ? "\u200B" + text : text;
    }

    /**
     * {@code html} with each person's mention as its plain name, without the quote of what it answered (the reply
     * fallback some clients still write) nor anything the timeline never showed.
     */
    static String withoutMentions(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        doc.select(UNSHOWN).remove();
        for (Element a : doc.select("a[href]")) {
            if (isPerson(a.attr("href"))) a.unwrap();
        }
        return doc.body().html();
    }

    /** Every form a bridge reads as a person: matrix:u/…, and matrix.to (any scheme or subdomain) whose #/ is a user. */
    private static boolean isPerson(String href) {
        String h = href.strip().toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("matrix:")) return h.startsWith("matrix:u/");
        try {
            java.net.URI uri = new java.net.URI(href.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            String fragment = uri.getFragment() == null ? "" : uri.getFragment();
            return (host.equals("matrix.to") || host.endsWith(".matrix.to")) && fragment.startsWith("/@");
        } catch (java.net.URISyntaxException e) {
            return h.contains("matrix.to"); // unreadable: taken as one, to be safe
        }
    }
}

package app.unichat.links;

import app.unichat.model.LinkPreview;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** A page's own description of itself (Open Graph, Twitter's cards, its title), as a link preview without its video yet. */
final class PagePreview {
    /** A stranger's page decides these texts: a card shows a few lines, never a whole page of them. */
    static final int MAX_TEXT = 400;

    private PagePreview() {}

    static LinkPreview parse(String html, String url) {
        Document d = Jsoup.parse(html, url);
        String title = first(d, "og:title", "twitter:title");
        if (title.isEmpty()) title = d.title();
        String image = d.select("meta[property=og:image], meta[name=og:image], meta[property=twitter:image], meta[name=twitter:image]")
                .stream().map(m -> m.absUrl("content")).filter(u -> u.startsWith("http")).findFirst().orElse(null);
        return new LinkPreview(url, cut(first(d, "og:site_name")), cut(title), cut(first(d, "og:description", "twitter:description", "description")),
                image, number(first(d, "og:image:width", "twitter:image:width")), number(first(d, "og:image:height", "twitter:image:height")),
                null, false);
    }

    private static String first(Document d, String... properties) {
        for (String p : properties) {
            for (Element meta : d.select("meta[property=" + p + "], meta[name=" + p + "]")) {
                String v = meta.attr("content").strip();
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    static String cut(String text) {
        String t = text == null ? "" : text.strip();
        int points = t.codePointCount(0, t.length());
        return points <= MAX_TEXT ? t : t.substring(0, t.offsetByCodePoints(0, MAX_TEXT)).strip() + "…";
    }

    static int number(String text) {
        try {
            int n = Integer.parseInt(text.strip());
            return n > 0 && n <= 20_000 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

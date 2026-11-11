package app.unichat.autosave;

import app.unichat.support.I18n;
import app.unichat.support.MarkOfTheWeb;
import app.unichat.video.Ffmpeg;
import app.unichat.video.GalleryDl;
import app.unichat.video.VideoTranscoder;
import app.unichat.video.YtDlp;
import instatray.core.Cookies;
import instatray.core.Fetch;
import instatray.core.Link;
import instatray.core.Links;
import instatray.core.Site;
import instatray.core.Tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * Saves what a link in a chat leads to, with InstaTray's engine ({@code instatray.core}: its sites, gallery-dl for
 * the ones yt-dlp doesn't do, each site's cookies from {@code *cookie*.txt} in Downloads). Unichat keeps what's its own:
 * the chat's folder, the message's id in the name, the progress, and H.264 for whatever arrives otherwise.
 */
final class LinkSaver {
    /** Cookies exported from the browser go to Downloads, as InstaTray expects them. */
    static final Path COOKIES = Path.of(System.getProperty("user.home"), "Downloads");
    private static final Duration LIMIT = Duration.ofHours(2);
    /** The engine's reasons, as a person reads them here. */
    private static final Map<String, String> REASONS = Map.of(
            Fetch.LOGIN_NEEDED, "autosave.link.login", Fetch.PIXIV_LOGIN, "autosave.link.login",
            Fetch.NOT_FOUND, "autosave.link.notFound", Fetch.LIVE, "autosave.link.live",
            Fetch.NO_VIDEO, "autosave.link.noVideo", Fetch.NO_VIDEO_IN_POST, "autosave.link.noVideo",
            Fetch.NO_VIDEO_ON_PAGE, "autosave.link.noVideo", Fetch.ANTI_BOT, "autosave.link.blocked",
            Fetch.PAGE_ONLY, "autosave.link.blocked", Fetch.TIMED_OUT, "autosave.error.tooLong");

    private LinkSaver() {}

    /** The link a chat's {@code url} is: one of InstaTray's sites, or any other yt-dlp knows. */
    static Link linkOf(String url) {
        List<Link> found = Links.find(url);
        return found.isEmpty() ? new Link(Site.OTHER, "other:" + url, url) : found.getFirst();
    }

    /** Null when saved; otherwise why not, and whether that's for good. Interrupted: stopped (nothing half-written stays). */
    static Failure save(String url, Path dir, String tag, DoubleConsumer progress, Tools tools, Path cookiesDir)
            throws IOException, InterruptedException {
        // Only a web address reaches a tool: the engine ends its options before the link too ("--"), but what a chat
        // says should never get that far.
        if (!url.matches("(?i)https?://\\S+")) return Failure.forGood(I18n.t("autosave.link.notALink"));
        Link link = linkOf(url);
        Fetch.Result r = Fetch.run(new Fetch.Request(link, dir, tag, Cookies.forLink(cookiesDir, link), LIMIT), tools,
                new Fetch.Listener() {
                    @Override public void progress(double fraction, String speed, String eta) {
                        if (fraction >= 0) progress.accept(fraction);
                    }

                    @Override public void title(String title) { }

                    @Override public void line(String rawOutputLine) { }
                });
        return switch (r.outcome()) {
            case DONE -> {
                for (Path f : r.files()) {
                    if (!Files.isRegularFile(f)) continue;
                    if (tools.ffmpeg() != null) VideoTranscoder.makePlayable(Path.of(tools.ffmpeg()), f);
                    MarkOfTheWeb.mark(f); // from the internet, as a file received in the chat is (SavedFile)
                }
                yield null;
            }
            case CANCELLED -> throw new InterruptedException();
            case FAILED, FAILED_PERMANENT -> failure(r);
        };
    }

    /** A failed run in the person's words, and whether it's for good. */
    /**
     * A page no site knows (yt-dlp's {@code [generic]}) that refuses or isn't there (4xx): nothing to save there, ever (a
     * Linktree profile answered 403 each time and, tried again, held its chat's later files). A known site refusing may
     * be rate limiting, and a 5xx is a server busy for now: those are tried again.
     */
    private static final java.util.regex.Pattern NOTHING_THERE = java.util.regex.Pattern.compile("^\\[generic\\].*HTTP Error 4\\d\\d");

    static Failure failure(Fetch.Result r) {
        // Tried again at each change of the chat, a run that took the whole LIMIT would hold all saving that long each
        // time, and a missing tool is still missing at the next change: neither is worth holding the chat's later files.
        boolean forGood = r.outcome() == Fetch.Outcome.FAILED_PERMANENT || Fetch.TIMED_OUT.equals(r.reason())
                || r.reason().endsWith(Fetch.NOT_INSTALLED) || NOTHING_THERE.matcher(r.reason()).find();
        return new Failure(reason(r.reason()), forGood);
    }

    /** The engine's reason as Unichat says it; a tool's own words as they are. */
    static String reason(String engine) {
        if (engine.endsWith(Fetch.NOT_INSTALLED)) return I18n.t("autosave.link.missingTool", engine.substring(0, engine.length() - Fetch.NOT_INSTALLED.length()));
        String key = REASONS.get(engine);
        return key == null ? engine : I18n.t(key);
    }

    /** The tools this computer has: yt-dlp and ffmpeg (Unichat's own, downloaded when first needed), gallery-dl if any. */
    static Tools tools() throws IOException, InterruptedException {
        Path ytDlp = YtDlp.whenNeeded(p -> { }), ffmpeg = Ffmpeg.whenNeeded(p -> { }), gallery = GalleryDl.find();
        return new Tools() {
            @Override public String ytDlp() { return ytDlp == null ? null : ytDlp.toString(); }
            @Override public String galleryDl() { return gallery == null ? null : gallery.toString(); }
            @Override public String ffmpeg() { return ffmpeg == null ? null : ffmpeg.toString(); }
        };
    }
}

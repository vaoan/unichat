package app.unichat.links;

import app.unichat.support.BoundedBody;
import app.unichat.support.BoundedMap;
import app.unichat.support.I18n;
import app.unichat.support.Threads;
import app.unichat.model.LinkMedia;
import app.unichat.model.LinkPreview;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Opens the links in messages, as a browser would, to learn whether they hold video or audio: by what the server
 * answers, or the media a page declares. These links come from other people and open by themselves, so no cookies, and
 * nothing on this computer or its network: a link can't reach the local server or the bridges, not even by redirecting.
 */
public final class LinkInspector {
    /** "compatible" gets the page with its metadata: sites like FixupX send a browser straight to the original. */
    private static final String AGENT = "Mozilla/5.0 (compatible; Unichat/1.0)";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** The request's timeout stops at the headers: a server trickling a byte a minute would hold a worker for good. */
    private static final Duration BODY_DEADLINE = Duration.ofSeconds(20);
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_PAGE_BYTES = 1024 * 1024;
    private static final long MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024;
    /** A whole video may take long on a slow line; this only keeps a trickle from holding the download forever. */
    private static final Duration DOWNLOAD_DEADLINE = Duration.ofHours(1);
    private static final int REMEMBERED = 1000;
    static final int MAX_PAGE_MEDIA = 5;
    private static final int AT_ONCE = 4;
    private static final List<String> PAGE_MEDIA = List.of("og:video:secure_url", "og:video:url", "og:video",
            "twitter:player:stream", "og:audio:secure_url", "og:audio:url", "og:audio");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build();
    private final ExecutorService workers = Executors.newFixedThreadPool(AT_ONCE, Threads.daemon("link-inspector"));
    private final Predicate<InetAddress> allowed;
    private final Duration bodyDeadline;
    private final Map<String, CompletableFuture<Optional<LinkPreview>>> seen = BoundedMap.byUse(REMEMBERED);

    public LinkInspector() {
        this(LinkInspector::isPublic);
    }

    LinkInspector(Predicate<InetAddress> allowed) {
        this(allowed, BODY_DEADLINE);
    }

    LinkInspector(Predicate<InetAddress> allowed, Duration bodyDeadline) {
        this.allowed = allowed;
        this.bodyDeadline = bodyDeadline;
    }

    /** What the link holds; each link is opened once however many messages or redraws ask. Empty on any failure. */
    public CompletableFuture<Optional<LinkMedia>> inspect(String url) {
        return preview(url).thenApply(p -> p.map(LinkPreview::video));
    }

    /**
     * What the link is, for its card: the page's title, text and picture, and the video or audio it leads to, checked
     * by what its server answers. Each link is opened once however many messages or redraws ask; empty on any failure.
     */
    public CompletableFuture<Optional<LinkPreview>> preview(String url) {
        synchronized (seen) {
            return seen.computeIfAbsent(url, u -> CompletableFuture.supplyAsync(() -> {
                try {
                    return Optional.ofNullable(read(u)).filter(p -> !p.isEmpty());
                } catch (IOException | IllegalArgumentException e) {
                    return Optional.<LinkPreview>empty();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.<LinkPreview>empty();
                }
            }, workers));
        }
    }

    /** Saves the file to {@code target} under the same rules; returns its content type. */
    public @Nullable String download(String url, Path target) throws IOException {
        try {
            Answer a = open(url);
            try (InputStream in = new BoundedBody(a.response().body(), MAX_DOWNLOAD_BYTES, DOWNLOAD_DEADLINE)) {
                if (a.response().statusCode() != 200) throw new IOException("HTTP " + a.response().statusCode());
                Files.deleteIfExists(target);
                Files.copy(in, target);
            }
            return a.response().headers().firstValue("Content-Type").orElse(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private @Nullable LinkPreview read(String url) throws IOException, InterruptedException {
        String api = TweetPreview.api(url);
        if (api != null) {
            Answer a = open(api);
            try (InputStream in = new BoundedBody(a.response().body(), MAX_PAGE_BYTES, bodyDeadline)) {
                if (a.response().statusCode() != 200) return null;
                return TweetPreview.parse(new String(in.readNBytes(MAX_PAGE_BYTES), StandardCharsets.UTF_8), url);
            }
        }
        Answer a = open(url);
        try (InputStream in = new BoundedBody(a.response().body(), MAX_PAGE_BYTES, bodyDeadline)) {
            if (a.response().statusCode() != 200) return null;
            String mime = mimeOf(a);
            Optional<LinkMedia> media = mediaOf(a, mime);
            if (media.isPresent()) return LinkPreview.of(url, media.orElseThrow(), null);
            if (mime.startsWith("image/")) return LinkPreview.of(url, null, a.uri().toString());
            if (!(mime.equals("text/html") || mime.equals("application/xhtml+xml"))) return null;
            String page = new String(in.readNBytes(MAX_PAGE_BYTES), StandardCharsets.UTF_8);
            LinkPreview found = PagePreview.parse(page, a.uri().toString());
            LinkPreview preview = new LinkPreview(url, found.site(), found.title(), found.description(), found.image(),
                    found.width(), found.height(), null, false);
            // A page can name thousands, each a request from this computer: the first few are the page's own.
            for (String candidate : pageMedia(page, a.uri().toString()).stream().limit(MAX_PAGE_MEDIA).toList()) {
                Optional<LinkMedia> video = media(candidate);
                if (video.isPresent()) return preview.withVideo(video.orElseThrow(), false);
            }
            return preview;
        }
    }

    /** A page's video or audio, if its address really is one. */
    private Optional<LinkMedia> media(String url) throws IOException, InterruptedException {
        Answer a = open(url);
        try (InputStream _ = a.response().body()) {
            return a.response().statusCode() == 200 ? mediaOf(a, mimeOf(a)) : Optional.empty();
        }
    }

    private static String mimeOf(Answer a) {
        return a.response().headers().firstValue("Content-Type").orElse("").split(";")[0].trim().toLowerCase(Locale.ROOT);
    }

    private static Optional<LinkMedia> mediaOf(Answer a, String mime) {
        LinkMedia.Kind kind = kindOf(mime, a.uri().getPath());
        long size = a.response().headers().firstValueAsLong("Content-Length").orElse(0);
        return kind == null ? Optional.empty() : Optional.of(new LinkMedia(kind, a.uri().toString(), mime.isEmpty() ? null : mime, size));
    }

    /** The media a page declares, the most specific first. A player page (YouTube's) isn't media and is skipped later. */
    static List<String> pageMedia(String html, String base) {
        Document d = Jsoup.parse(html, base);
        List<String> out = new ArrayList<>();
        for (String property : PAGE_MEDIA) {
            for (Element meta : d.select("meta[property=" + property + "], meta[name=" + property + "]")) {
                String url = meta.absUrl("content");
                if (!url.isEmpty() && !out.contains(url)) out.add(url);
            }
        }
        for (Element e : d.select("video[src], audio[src], video source[src], audio source[src]")) {
            String url = e.absUrl("src");
            if (!url.isEmpty() && !out.contains(url)) out.add(url);
        }
        return out;
    }

    /** By what the server says; a generic answer falls back on the file's extension. */
    @SuppressFBWarnings(value = "IMPROPER_UNICODE", justification = "only guesses video or audio from an extension, to label a link; nothing is allowed by it")
    static LinkMedia.@Nullable Kind kindOf(String mime, @Nullable String path) {
        if (mime.startsWith("video/")) return LinkMedia.Kind.VIDEO;
        if (mime.startsWith("audio/") || mime.equals("application/ogg")) return LinkMedia.Kind.AUDIO;
        if (!mime.isEmpty() && !mime.equals("application/octet-stream") && !mime.equals("binary/octet-stream")) return null;
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.matches(".*\\.(mp4|m4v|mov|webm)")) return LinkMedia.Kind.VIDEO;
        if (p.matches(".*\\.(mp3|m4a|ogg|oga|opus|wav|flac|aac)")) return LinkMedia.Kind.AUDIO;
        return null;
    }

    private record Answer(URI uri, HttpResponse<InputStream> response) {}

    /** Follows redirects by hand: each hop is checked before anything is sent to it. */
    private Answer open(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            check(uri);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", AGENT).GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            Optional<String> next = response.headers().firstValue("Location");
            if (code < 300 || code >= 400 || next.isEmpty()) return new Answer(uri, response);
            response.body().close();
            uri = uri.resolve(next.orElseThrow());
        }
        throw new IOException(I18n.t("link.tooManyRedirects"));
    }

    @SuppressFBWarnings(value = "IMPROPER_UNICODE", justification = "a java.net.URI scheme is ASCII letters only, so lowercasing can't turn a look-alike into http; the host is then checked by its resolved addresses")
    private void check(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException(I18n.t("link.notAllowed"));
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (!allowed.test(a)) throw new IOException(I18n.t("link.addressNotAllowed"));
            }
        } catch (UnknownHostException e) {
            throw new IOException(e);
        }
    }

    /** Nothing on this machine or its local network. */
    static boolean isPublic(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        return b.length == 4 ? isPublicV4(b) : isPublicV6(b);
    }

    private static boolean isPublicV4(byte[] b) {
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        boolean sharedNat = first == 100 && (second & 0xC0) == 64; // 100.64.0.0/10
        boolean benchmarking = first == 198 && (second & 0xFE) == 18; // 198.18.0.0/15
        boolean protocolAssignments = first == 192 && second == 0 && b[2] == 0; // 192.0.0.0/24
        return first != 0 && first < 240 && !sharedNat && !benchmarking && !protocolAssignments; // 240.0.0.0/4 and broadcast
    }

    /** An IPv6 address that carries an IPv4 one is only as public as that one: NAT64 or 6to4 reach it all the same. */
    private static boolean isPublicV6(byte[] b) {
        if ((b[0] & 0xFE) == 0xFC) return false; // unique local, fc00::/7
        byte[] v4 = carriedV4(b);
        try {
            return v4 == null || isPublic(InetAddress.getByAddress(v4));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static byte @Nullable [] carriedV4(byte[] b) {
        boolean sixToFour = (b[0] & 0xFF) == 0x20 && b[1] == 0x02; // 2002::/16, the IPv4 in bytes 2 to 5
        if (sixToFour) return java.util.Arrays.copyOfRange(b, 2, 6);
        byte[] nat64 = {0, 0x64, (byte) 0xFF, (byte) 0x9B, 0, 0, 0, 0, 0, 0, 0, 0}; // 64:ff9b::/96
        byte[] compatible = new byte[12]; // ::/96, the old "IPv4-compatible" form
        byte[] head = java.util.Arrays.copyOf(b, 12);
        return java.util.Arrays.equals(head, nat64) || java.util.Arrays.equals(head, compatible)
                ? java.util.Arrays.copyOfRange(b, 12, 16) : null;
    }
}

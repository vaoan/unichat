package app.unichat.links;

import app.unichat.support.Threads;
import app.unichat.model.LinkMedia;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * Opens the links in messages, as a browser would, to learn whether they hold video or audio: by what the server
 * answers, or the media a page declares. These links come from other people and open by themselves, so no cookies, and
 * nothing on this computer or its network: a link can't reach the local server or the bridges, not even by redirecting.
 */
public final class LinkInspector {
    /** "compatible" gets the page with its metadata: sites like FixupX send a browser straight to the original. */
    private static final String AGENT = "Mozilla/5.0 (compatible; Unichat/1.0)";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_PAGE_BYTES = 1024 * 1024;
    private static final long MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024;
    private static final int REMEMBERED = 1000;
    private static final int AT_ONCE = 4;
    private static final List<String> PAGE_MEDIA = List.of("og:video:secure_url", "og:video:url", "og:video",
            "twitter:player:stream", "og:audio:secure_url", "og:audio:url", "og:audio");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build();
    private final ExecutorService workers = Executors.newFixedThreadPool(AT_ONCE, Threads.daemon("link-inspector"));
    private final Predicate<InetAddress> allowed;
    private final Map<String, CompletableFuture<Optional<LinkMedia>>> seen = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<Optional<LinkMedia>>> eldest) {
            return size() > REMEMBERED;
        }
    };

    public LinkInspector() {
        this(LinkInspector::isPublic);
    }

    LinkInspector(Predicate<InetAddress> allowed) {
        this.allowed = allowed;
    }

    /** What the link holds; each link is opened once however many messages or redraws ask. Empty on any failure. */
    public CompletableFuture<Optional<LinkMedia>> inspect(String url) {
        synchronized (seen) {
            return seen.computeIfAbsent(url, u -> CompletableFuture.supplyAsync(() -> {
                try {
                    return look(u, true);
                } catch (IOException | IllegalArgumentException e) {
                    return Optional.<LinkMedia>empty();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.<LinkMedia>empty();
                }
            }, workers));
        }
    }

    /** Saves the file to {@code target} under the same rules; returns its content type. */
    public String download(String url, Path target) throws IOException {
        try {
            Answer a = open(url);
            try (InputStream in = a.response().body()) {
                if (a.response().statusCode() != 200) throw new IOException("HTTP " + a.response().statusCode());
                Files.deleteIfExists(target);
                if (Files.copy(new Capped(in), target) > MAX_DOWNLOAD_BYTES) throw new IOException("Demasiado grande");
            }
            return a.response().headers().firstValue("Content-Type").orElse(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private Optional<LinkMedia> look(String url, boolean mayBePage) throws IOException, InterruptedException {
        Answer a = open(url);
        try (InputStream in = a.response().body()) {
            if (a.response().statusCode() != 200) return Optional.empty();
            String mime = a.response().headers().firstValue("Content-Type").orElse("").split(";")[0].trim().toLowerCase(Locale.ROOT);
            LinkMedia.Kind kind = kindOf(mime, a.uri().getPath());
            long size = a.response().headers().firstValueAsLong("Content-Length").orElse(0);
            if (kind != null) return Optional.of(new LinkMedia(kind, a.uri().toString(), mime.isEmpty() ? null : mime, size));
            if (!mayBePage || !(mime.equals("text/html") || mime.equals("application/xhtml+xml"))) return Optional.empty();
            String page = new String(in.readNBytes(MAX_PAGE_BYTES), StandardCharsets.UTF_8);
            for (String candidate : pageMedia(page, a.uri().toString())) {
                Optional<LinkMedia> found = look(candidate, false);
                if (found.isPresent()) return found;
            }
            return Optional.empty();
        }
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
    static LinkMedia.Kind kindOf(String mime, String path) {
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
            uri = uri.resolve(next.get());
        }
        throw new IOException("Demasiadas redirecciones");
    }

    private void check(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("Enlace no permitido");
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (!allowed.test(a)) throw new IOException("Dirección no permitida");
            }
        } catch (UnknownHostException e) {
            throw new IOException(e);
        }
    }

    /** Nothing on this machine or its local network. */
    static boolean isPublic(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (b.length == 16) return (b[0] & 0xFE) != 0xFC; // unique local, fc00::/7
        boolean sharedNat = (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
        return !sharedNat && b[0] != 0;
    }

    /** Stops an endless file at the limit rather than filling the disk. */
    private static final class Capped extends java.io.FilterInputStream {
        private long total;

        Capped(InputStream in) {
            super(in);
        }

        @Override public int read() throws IOException {
            int c = super.read();
            count(c < 0 ? 0 : 1);
            return c;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            count(Math.max(n, 0));
            return n;
        }

        private void count(int n) throws IOException {
            total += n;
            if (total > MAX_DOWNLOAD_BYTES) throw new IOException("Demasiado grande");
        }
    }
}

package app.unichat.cloud;

import app.unichat.support.I18n;
import app.unichat.support.Urls;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * The person's own storage over WebDAV, the one way pCloud, Icedrive, Nextcloud, Koofr… all offer: whether something
 * is there, folders, putting and getting a file. Paths are relative to the account's root, "/"-separated; each part is
 * encoded here. Nothing is overwritten by mistake (callers ask {@link #exists} first); {@link #delete} only when asked.
 */
public final class WebDav implements Storage {
    private final String base;
    private final String auth;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    /** How long a question (is it there, which version, a folder) may go unanswered: a server that never answers ends in a failure, never a hang. */
    static final Duration ANSWER = Duration.ofSeconds(30);
    private final Duration answer;

    public WebDav(String url, String user, String secret) {
        this(url, user, secret, ANSWER);
    }

    /** {@code answer}: how long a question may wait for its answer (files going up or down have no limit). */
    WebDav(String url, String user, String secret, Duration answer) {
        this.answer = answer;
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /** Whether the address answers and takes these credentials; why not, in the person's words. */
    @Override
    public void check() throws IOException {
        int code = send("PROPFIND", "", HttpRequest.BodyPublishers.noBody(), true).statusCode();
        if (code != 207 && code != 200) throw failure(code);
    }

    @Override
    public boolean exists(String path) throws IOException {
        int code = send("PROPFIND", path, HttpRequest.BodyPublishers.noBody(), true).statusCode();
        if (code == 404) return false;
        if (code == 207 || code == 200) return true;
        throw failure(code);
    }

    /**
     * What tells this version of {@code path} from the next (its ETag, else its date), without downloading it; null
     * when there's none. Each check asks only this, and downloads the file when it changed.
     */
    @Override
    public String stamp(String path) throws IOException {
        String xml = properties(path);
        if (xml == null) return null;
        String etag = prop(xml, "getetag");
        String date = prop(xml, "getlastmodified");
        return etag != null ? etag : date != null ? date : xml;
    }

    /** WebDAV says a file's size, not a checksum. */
    @Override
    public RemoteFile info(String path) throws IOException {
        String xml = properties(path);
        if (xml == null) return null;
        String size = prop(xml, "getcontentlength");
        return new RemoteFile(size == null ? -1 : Long.parseLong(size.strip()), null, null);
    }

    /** What the server says of {@code path} (its PROPFIND answer), or null when there's nothing there. */
    private String properties(String path) throws IOException {
        HttpResponse<String> r = Http.send(http, HttpRequest.newBuilder(uri(path)).timeout(answer).header("Authorization", auth)
                .header("Depth", "0").method("PROPFIND", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 207 && r.statusCode() != 200) throw failure(r.statusCode());
        return r.body();
    }

    static String prop(CharSequence xml, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<(?:[A-Za-z0-9]+:)?" + name + "[^<>]*>([^<]*)<").matcher(xml);
        return m.find() ? m.group(1).strip() : null;
    }

    /** What's in {@code dir}: its PROPFIND one level deep, each answer's last part but the folder's own. */
    @Override
    public java.util.List<String> list(String dir) throws IOException {
        URI self = uri(dir);
        HttpResponse<String> r = Http.send(http, HttpRequest.newBuilder(URI.create(self + "/")).timeout(answer).header("Authorization", auth)
                .header("Depth", "1").method("PROPFIND", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return java.util.List.of();
        if (r.statusCode() != 207 && r.statusCode() != 200) throw failure(r.statusCode());
        String own = trimmed(decoded(self.getRawPath()));
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String href : hrefs(r.body())) {
            String path = trimmed(decoded(href.contains("://") ? URI.create(href).getRawPath() : href));
            if (path.equals(own) || path.isEmpty()) continue; // the folder itself
            out.add(path.substring(path.lastIndexOf('/') + 1));
        }
        return java.util.List.copyOf(out);
    }

    /** Each {@code href} of a PROPFIND answer, as written. */
    @SuppressFBWarnings(value = "REDOS", justification = "linear: each class stops at the next < or >, so no scan runs past another tag (WebDavTest.aServersAnswerIsReadInLinearTimeWhateverItHolds)")
    static java.util.List<String> hrefs(CharSequence xml) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<(?:[A-Za-z0-9]+:)?href[^<>]*>([^<]*)<").matcher(xml);
        while (m.find()) out.add(m.group(1).strip().replace("&amp;", "&"));
        return out;
    }

    private static String decoded(String rawPath) {
        return java.net.URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String trimmed(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** Makes {@code dir} and the folders above it that are missing. */
    @Override
    public void mkdirs(String dir) throws IOException {
        StringBuilder at = new StringBuilder();
        for (String part : dir.split("/")) {
            if (part.isEmpty()) continue;
            at.append(at.isEmpty() ? "" : "/").append(part);
            int code = send("MKCOL", at.toString(), HttpRequest.BodyPublishers.noBody(), false).statusCode();
            if (code != 201 && code != 405 && code != 200) throw failure(code); // 405: it was already there
        }
    }

    /** DELETE: on a collection it removes all in it (RFC 4918); 404 means nothing was there. */
    @Override
    public void delete(String path) throws IOException {
        Storage.deletable(path);
        int code = send("DELETE", path, HttpRequest.BodyPublishers.noBody(), false).statusCode();
        if (code != 404 && code != 200 && code != 204) throw failure(code);
    }

    /** MOVE with "Overwrite: F": the server answers 412 rather than replace what's at {@code to}. */
    @Override
    public void move(String from, String to) throws IOException {
        Storage.deletable(from);
        Storage.deletable(to);
        HttpRequest r = HttpRequest.newBuilder(uri(from)).timeout(answer).header("Authorization", auth)
                .header("Destination", uri(to).toString()).header("Overwrite", "F").method("MOVE", HttpRequest.BodyPublishers.noBody()).build();
        int code = Http.send(http, r, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (code == 412) throw new IOException(I18n.t("cloud.error.moveTaken", to));
        if (code == 404) throw new IOException(I18n.t("cloud.error.moveNothing", from));
        if (code != 201 && code != 204 && code != 200) throw failure(code);
    }

    @Override
    public void put(String path, Path file) throws IOException {
        put(path, file, null);
    }

    @Override
    public void put(String path, Path file, java.util.function.LongConsumer sent) throws IOException {
        int code = send("PUT", path, CountingBody.of(HttpRequest.BodyPublishers.ofFile(file), sent), false).statusCode();
        if (code != 201 && code != 204 && code != 200) throw failure(code);
    }

    @Override
    public void put(String path, byte[] data) throws IOException {
        int code = send("PUT", path, HttpRequest.BodyPublishers.ofByteArray(data), false).statusCode();
        if (code != 201 && code != 204 && code != 200) throw failure(code);
    }

    /** The file at {@code path}, or null when there's none. */
    @Override
    public boolean get(String path, Path target) throws IOException {
        HttpResponse<java.io.InputStream> r = download(path, HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            r.body().close(); // the connection is given back, not left waiting for a body nobody reads
            if (r.statusCode() == 404) return false;
            throw failure(r.statusCode());
        }
        Storage.save(r.body(), target);
        return true;
    }

    @Override
    public byte[] get(String path) throws IOException {
        HttpResponse<byte[]> r = download(path, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 200) throw failure(r.statusCode());
        return r.body();
    }

    private HttpResponse<Void> send(String method, String path, HttpRequest.BodyPublisher body, boolean depth0) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Authorization", auth).method(method, body);
        if (!method.equals("PUT")) b.timeout(answer); // a big file going up takes as long as it takes
        if (depth0) b.header("Depth", "0");
        return Http.send(http, b.build(), HttpResponse.BodyHandlers.discarding());
    }

    private <T> HttpResponse<T> download(String path, HttpResponse.BodyHandler<T> body) throws IOException {
        return Http.send(http, HttpRequest.newBuilder(uri(path)).header("Authorization", auth).GET().build(), body);
    }

    private URI uri(String path) {
        StringBuilder u = new StringBuilder(base).append('/');
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (u.charAt(u.length() - 1) != '/') u.append('/');
            u.append(Urls.enc(part));
        }
        return URI.create(u.toString());
    }

    private static IOException failure(int code) {
        return new IOException(switch (code) {
            case 401, 403 -> I18n.t("cloud.error.login");
            case 507 -> I18n.t("cloud.error.full");
            default -> I18n.t("cloud.error.server", code);
        });
    }
}

package app.unichat.cloud;

import app.unichat.support.I18n;
import app.unichat.support.Urls;

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
 * encoded here. Nothing is ever deleted nor overwritten by mistake: callers ask {@link #exists} first.
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
        HttpResponse<String> r;
        try {
            r = http.send(HttpRequest.newBuilder(uri(path)).timeout(answer).header("Authorization", auth).header("Depth", "0")
                    .method("PROPFIND", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException(I18n.t("cloud.error.noAnswer"), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 207 && r.statusCode() != 200) throw failure(r.statusCode());
        String etag = prop(r.body(), "getetag"), date = prop(r.body(), "getlastmodified");
        return etag != null ? etag : date != null ? date : r.body();
    }

    private static String prop(String xml, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<(?:[A-Za-z0-9]+:)?" + name + "[^>]*>([^<]*)<").matcher(xml);
        return m.find() ? m.group(1).strip() : null;
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

    @Override
    public void put(String path, Path file) throws IOException {
        int code = send("PUT", path, HttpRequest.BodyPublishers.ofFile(file), false).statusCode();
        if (code != 201 && code != 204 && code != 200) throw failure(code);
    }

    @Override
    public void put(String path, byte[] data) throws IOException {
        int code = send("PUT", path, HttpRequest.BodyPublishers.ofByteArray(data), false).statusCode();
        if (code != 201 && code != 204 && code != 200) throw failure(code);
    }

    /** The file at {@code path}, or null when there's none. */
    @Override
    public byte[] get(String path) throws IOException {
        HttpResponse<byte[]> r = sendBytes(path);
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 200) throw failure(r.statusCode());
        return r.body();
    }

    private HttpResponse<Void> send(String method, String path, HttpRequest.BodyPublisher body, boolean depth0) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Authorization", auth).method(method, body);
        if (!method.equals("PUT")) b.timeout(answer); // a big file going up takes as long as it takes
        if (depth0) b.header("Depth", "0");
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException(I18n.t("cloud.error.noAnswer"), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private HttpResponse<byte[]> sendBytes(String path) throws IOException {
        try {
            return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", auth).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException(I18n.t("cloud.error.noAnswer"), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private URI uri(String path) {
        StringBuilder u = new StringBuilder(base).append('/');
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (u.charAt(u.length() - 1) != '/') u.append('/');
            u.append(Urls.enc(parts[i]));
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

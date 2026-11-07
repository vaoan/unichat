package app.unichat.matrix;

import app.unichat.support.I18n;
import app.unichat.support.Urls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** Thin blocking wrapper over the Matrix Client-Server API. Thread-safe. */
final class MatrixClient {
    static final String CLIENT = "/_matrix/client/v3";
    static final ObjectMapper JSON = new ObjectMapper();
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private volatile HttpClient http;
    private final String baseUrl;
    private final String accessToken;

    MatrixClient(String baseUrl, String accessToken) {
        this(newHttpClient(), baseUrl, accessToken);
    }

    MatrixClient(HttpClient http, String baseUrl, String accessToken) {
        this.http = http;
        this.baseUrl = stripSlash(baseUrl);
        this.accessToken = accessToken;
    }

    static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    String baseUrl() { return baseUrl; }

    static String stripSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    JsonNode get(String path) throws IOException { return request("GET", path, null, DEFAULT_TIMEOUT); }
    JsonNode post(String path, Object body) throws IOException { return request("POST", path, body, DEFAULT_TIMEOUT); }
    JsonNode put(String path, Object body) throws IOException { return request("PUT", path, body, DEFAULT_TIMEOUT); }
    JsonNode delete(String path) throws IOException { return request("DELETE", path, null, DEFAULT_TIMEOUT); }

    /** Sends one event to a room; the answer holds its {@code event_id}. */
    JsonNode sendEvent(String roomId, String type, String txnId, Object content) throws IOException {
        return put(CLIENT + "/rooms/" + Urls.enc(roomId) + "/send/" + Urls.enc(type) + "/" + Urls.enc(txnId), content);
    }

    JsonNode join(String roomId) throws IOException {
        return post(CLIENT + "/join/" + Urls.enc(roomId), null);
    }

    /** Sends a JSON request; {@code path} starts with {@code /_matrix/...}. Throws {@link MatrixException} on non-2xx. */
    JsonNode request(String method, String path, Object body, Duration timeout) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json");
        if (accessToken != null) b.header("Authorization", "Bearer " + accessToken);
        if (body == null && (method.equals("POST") || method.equals("PUT"))) body = JSON.createObjectNode();
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> resp = send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return parse(resp.statusCode(), resp.body());
    }

    /** Uploads raw bytes to the media repository; returns the mxc:// content URI. */
    String upload(Path file, String contentType, String fileName) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/_matrix/media/v3/upload?filename=" + Urls.enc(fileName)))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        HttpResponse<byte[]> resp = send(req, HttpResponse.BodyHandlers.ofByteArray());
        String uri = parse(resp.statusCode(), resp.body()).path("content_uri").asText(null);
        if (uri == null) throw new MatrixException(resp.statusCode(), null, I18n.t("media.noFileUrl"));
        return uri;
    }

    /**
     * Downloads {@code url} (absolute, or a path relative to the homeserver) into {@code target}.
     * Returns the response Content-Type (may be null).
     */
    String download(String url, boolean authenticated, Path target) throws IOException {
        String full = url.startsWith("http://") || url.startsWith("https://") ? url : baseUrl + url;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(full)).timeout(Duration.ofMinutes(5)).GET();
        if (authenticated && accessToken != null) b.header("Authorization", "Bearer " + accessToken);
        HttpResponse<InputStream> resp = send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
            if (resp.statusCode() / 100 != 2) {
                parse(resp.statusCode(), in.readAllBytes());
                throw new MatrixException(resp.statusCode(), null, "HTTP " + resp.statusCode());
            }
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part" + Thread.currentThread().threadId());
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return resp.headers().firstValue("Content-Type").orElse(null);
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        HttpClient c = http;
        try {
            return c.send(req, handler);
        } catch (IOException e) {
            // A client whose selector died ("selector manager closed") fails every request from then on: replace it
            // so the next attempt (the sync loop retries) gets a working one.
            if (c.isTerminated()) {
                synchronized (this) {
                    if (http == c) http = newHttpClient();
                }
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
    }

    static JsonNode parse(int status, byte[] body) throws IOException {
        JsonNode json = null;
        if (body != null && body.length > 0) {
            try {
                json = JSON.readTree(body);
            } catch (IOException e) {
                if (status / 100 == 2) throw e;
            }
        }
        if (status / 100 == 2) return json == null ? JSON.createObjectNode() : json;
        String errcode = json == null ? null : json.path("errcode").asText(null);
        String error = json == null ? null : json.path("error").asText(null);
        throw new MatrixException(status, errcode, error != null ? error : "HTTP " + status);
    }
}

package app.unichat.cloud;

import app.unichat.support.I18n;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Dropbox through its API (v2), with the lasting permission its sign-in gave ({@link DropboxLogin}): each call carries
 * a short-lived token made from it, made again when Dropbox says it expired. The app sees only its own folder
 * (Dropbox/Apps/…), so paths start there. Files over {@link #SINGLE} go up in pieces (an upload session).
 */
public final class DropboxApi implements Storage {
    /** Dropbox's limit for one upload call. */
    static final long SINGLE = 150L * 1024 * 1024;
    private static final int CHUNK = 64 * 1024 * 1024;
    /** Its header takes JSON in plain ASCII only: "Ñandú.jpg" goes as \\u escapes. */
    private static final JsonMapper JSON = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    private final String appKey;
    private final String refresh;
    private final String api;
    private final String content;
    private final String tokenUrl;
    private final long single;
    private final int chunk;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private volatile String access;

    public DropboxApi(String appKey, String refreshToken) {
        this(appKey, refreshToken, "https://api.dropboxapi.com", "https://content.dropboxapi.com", DropboxLogin.TOKEN, SINGLE, CHUNK);
    }

    DropboxApi(String appKey, String refreshToken, String api, String content, String tokenUrl, long single, int chunk) {
        this.appKey = appKey;
        this.refresh = refreshToken;
        this.api = api;
        this.content = content;
        this.tokenUrl = tokenUrl;
        this.single = single;
        this.chunk = chunk;
    }

    @Override
    public void check() throws IOException {
        email();
    }

    /** The account's email, to say who's connected. */
    public String email() throws IOException {
        return rpc("/2/users/get_current_account", null).path("email").asText("");
    }

    @Override
    public boolean exists(String path) throws IOException {
        return metadata(path) != null;
    }

    @Override
    public String stamp(String path) throws IOException {
        JsonNode m = metadata(path);
        return m == null ? null : m.path("rev").asText(m.path("id").asText(""));
    }

    /** Dropbox gives a file's size and its own content hash ({@link Checksums#DROPBOX}). */
    @Override
    public RemoteFile info(String path) throws IOException {
        JsonNode m = metadata(path);
        if (m == null) return null;
        String hash = m.path("content_hash").asText(null);
        return new RemoteFile(m.path("size").asLong(-1), hash == null ? null : Checksums.DROPBOX, hash);
    }

    private JsonNode metadata(String path) throws IOException {
        try {
            return rpc("/2/files/get_metadata", JSON.createObjectNode().put("path", abs(path)));
        } catch (Missing gone) {
            return null;
        }
    }

    @Override
    public void mkdirs(String dir) throws IOException {
        // Uploading makes the folders it needs; this one only makes sure an empty one exists.
        try {
            rpc("/2/files/create_folder_v2", JSON.createObjectNode().put("path", abs(dir)).put("autorename", false));
        } catch (Conflict alreadyThere) {
            // fine
        }
    }

    @Override
    public void put(String path, Path file) throws IOException {
        put(path, file, null);
    }

    @Override
    public void put(String path, Path file, java.util.function.LongConsumer sent) throws IOException {
        long size = Files.size(file);
        if (size <= single) {
            upload(path, CountingBody.of(HttpRequest.BodyPublishers.ofFile(file), sent));
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            String session = send(content + "/2/files/upload_session/start", JSON.createObjectNode().put("close", false),
                    piece(in.readNBytes(chunk), 0, sent)).path("session_id").asText();
            long offset = chunk;
            while (offset + chunk < size) {
                ObjectNode arg = JSON.createObjectNode().put("close", false);
                arg.putObject("cursor").put("session_id", session).put("offset", offset);
                send(content + "/2/files/upload_session/append_v2", arg, piece(in.readNBytes(chunk), offset, sent));
                offset += chunk;
            }
            ObjectNode arg = JSON.createObjectNode();
            arg.putObject("cursor").put("session_id", session).put("offset", offset);
            arg.putObject("commit").put("path", abs(path)).put("mode", "overwrite").put("mute", true);
            send(content + "/2/files/upload_session/finish", arg, piece(in.readAllBytes(), offset, sent));
        }
    }

    /** One piece of a session upload, counting on from the {@code before} bytes already gone. */
    private static HttpRequest.BodyPublisher piece(byte[] data, long before, java.util.function.LongConsumer sent) {
        return CountingBody.of(HttpRequest.BodyPublishers.ofByteArray(data), sent == null ? null : n -> sent.accept(before + n));
    }

    @Override
    public void put(String path, byte[] data) throws IOException {
        upload(path, HttpRequest.BodyPublishers.ofByteArray(data));
    }

    private void upload(String path, HttpRequest.BodyPublisher body) throws IOException {
        send(content + "/2/files/upload", JSON.createObjectNode().put("path", abs(path)).put("mode", "overwrite").put("mute", true), body);
    }

    @Override
    public boolean get(String path, Path target) throws IOException {
        HttpResponse<InputStream> r = authorized(download(path), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            byte[] error;
            try (InputStream in = r.body()) {
                error = in.readNBytes(64 * 1024); // an error's JSON, never a file
            }
            IOException failed = failure(r.statusCode(), error);
            if (failed instanceof Missing) return false;
            throw failed;
        }
        Storage.save(r.body(), target);
        return true;
    }

    @Override
    public byte[] get(String path) throws IOException {
        try {
            return call(download(path), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Missing gone) {
            return null;
        }
    }

    private HttpRequest.Builder download(String path) throws IOException {
        return HttpRequest.newBuilder(URI.create(content + "/2/files/download"))
                .header("Dropbox-API-Arg", arg(JSON.createObjectNode().put("path", abs(path))))
                .POST(HttpRequest.BodyPublishers.noBody());
    }

    /** A call with JSON in and out (or no arguments: {@code null}). */
    private JsonNode rpc(String endpoint, ObjectNode args) throws IOException {
        byte[] out = call(HttpRequest.newBuilder(URI.create(api + endpoint)).timeout(WebDav.ANSWER)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(args == null ? "null" : JSON.writeValueAsString(args))),
                HttpResponse.BodyHandlers.ofByteArray());
        return JSON.readTree(out);
    }

    /** A content call: the arguments in the header, the file in the body. */
    private JsonNode send(String url, ObjectNode args, HttpRequest.BodyPublisher body) throws IOException {
        byte[] out = call(HttpRequest.newBuilder(URI.create(url)).header("Dropbox-API-Arg", arg(args))
                .header("Content-Type", "application/octet-stream").POST(body), HttpResponse.BodyHandlers.ofByteArray());
        return JSON.readTree(out);
    }

    private static String arg(ObjectNode args) throws IOException {
        return JSON.writeValueAsString(args);
    }

    private byte[] call(HttpRequest.Builder b, HttpResponse.BodyHandler<byte[]> handler) throws IOException {
        HttpResponse<byte[]> r = authorized(b, handler);
        if (r.statusCode() == 200) return r.body();
        throw failure(r.statusCode(), r.body());
    }

    /** With today's token; once more with a new one if Dropbox says it expired. */
    private <T> HttpResponse<T> authorized(HttpRequest.Builder b, HttpResponse.BodyHandler<T> handler) throws IOException {
        HttpResponse<T> r = send(b, handler, token(false));
        if (r.statusCode() != 401) return r;
        if (r.body() instanceof InputStream unread) unread.close();
        return send(b, handler, token(true));
    }

    /** What an answer other than 200 means: not there, already there, or why, in the person's words. */
    private static IOException failure(int code, byte[] body) throws IOException {
        String summary = code == 409 ? JSON.readTree(body).path("error_summary").asText("") : "";
        if (summary.contains("not_found")) return new Missing();
        if (summary.contains("conflict")) return new Conflict();
        return new IOException(code == 401 ? I18n.t("cloud.error.dropboxLogin")
                : summary.contains("insufficient_space") ? I18n.t("cloud.error.full")
                : I18n.t("cloud.error.server", code + (summary.isEmpty() ? "" : " " + summary)));
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder b, HttpResponse.BodyHandler<T> handler, String token) throws IOException {
        try {
            return http.send(b.copy().header("Authorization", "Bearer " + token).build(), handler);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException(I18n.t("cloud.error.noAnswer"), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    /** A short-lived token from the lasting permission: kept until Dropbox says it expired. */
    private synchronized String token(boolean fresh) throws IOException {
        if (access != null && !fresh) return access;
        String form = "grant_type=refresh_token&refresh_token=" + app.unichat.support.Urls.enc(refresh)
                + "&client_id=" + app.unichat.support.Urls.enc(appKey);
        HttpResponse<String> r;
        try {
            r = http.send(HttpRequest.newBuilder(URI.create(tokenUrl)).timeout(WebDav.ANSWER)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException(I18n.t("cloud.error.noAnswer"), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        String t = r.statusCode() == 200 ? JSON.readTree(r.body()).path("access_token").asText(null) : null;
        if (t == null) throw new IOException(I18n.t("cloud.error.dropboxLogin"));
        access = t;
        return t;
    }

    /** Not there: an answer, not a failure. */
    static final class Missing extends IOException {
        Missing() {
            super("missing");
        }
    }

    /** Already there (a folder): fine. */
    static final class Conflict extends IOException {
        Conflict() {
            super("conflict");
        }
    }

    private static String abs(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }
}

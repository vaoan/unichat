package app.unichat.cloud;

import app.unichat.support.I18n;
import app.unichat.support.Urls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/**
 * pCloud through its own API (docs.pcloud.com), with the permission its sign-in page gave ({@link PCloudLogin}): what
 * works with two-step verification on, which its WebDAV refuses. The token goes in a header, never in an address
 * (addresses end up in logs). Calls go to the host pCloud named at sign-in (api. or eapi. for Europe).
 */
public final class PCloudApi implements Storage {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String host;
    private final String token;
    private final Duration answer;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public PCloudApi(String host, String token) {
        this(host, token, WebDav.ANSWER);
    }

    /** {@code answer}: how long a question may wait (files going up or down have no limit). */
    PCloudApi(String host, String token, Duration answer) {
        this.host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        this.token = token;
        this.answer = answer;
    }

    @Override
    public void check() throws IOException {
        call("userinfo", "");
    }

    /** The account's email, to say who's connected. */
    public String email() throws IOException {
        return call("userinfo", "").path("email").asText("");
    }

    @Override
    public boolean exists(String path) throws IOException {
        return stat(path) != null;
    }

    @Override
    public String stamp(String path) throws IOException {
        JsonNode meta = stat(path);
        if (meta == null) return null;
        return meta.path("hash").asText("") + "/" + meta.path("modified").asText("");
    }

    /** The file's metadata, or null when there's none. */
    private JsonNode stat(String path) throws IOException {
        try {
            return call("stat", "path=" + Urls.enc(abs(path))).path("metadata");
        } catch (Missing gone) {
            return null;
        }
    }

    /** pCloud gives a file's SHA-1 (both regions; Europe adds SHA-256). */
    @Override
    public RemoteFile info(String path) throws IOException {
        JsonNode meta = stat(path);
        if (meta == null) return null;
        JsonNode sums;
        try {
            sums = call("checksumfile", "path=" + Urls.enc(abs(path)));
        } catch (Missing gone) {
            return null;
        }
        String sha1 = sums.path("sha1").asText(null);
        return new RemoteFile(meta.path("size").asLong(-1), sha1 == null ? null : Checksums.SHA1, sha1);
    }

    @Override
    public void mkdirs(String dir) throws IOException {
        StringBuilder at = new StringBuilder();
        for (String part : dir.split("/")) {
            if (part.isEmpty()) continue;
            at.append('/').append(part);
            call("createfolderifnotexists", "path=" + Urls.enc(at.toString()));
        }
    }

    @Override
    public void put(String path, Path file) throws IOException {
        upload(path, HttpRequest.BodyPublishers.ofFile(file));
    }

    @Override
    public void put(String path, byte[] data) throws IOException {
        upload(path, HttpRequest.BodyPublishers.ofByteArray(data));
    }

    /** Whole or not at all ({@code nopartial}); a name already there is replaced, as for the settings file. */
    private void upload(String path, HttpRequest.BodyPublisher body) throws IOException {
        String p = abs(path);
        int slash = p.lastIndexOf('/');
        String folder = slash <= 0 ? "/" : p.substring(0, slash);
        String query = "path=" + Urls.enc(folder) + "&filename=" + Urls.enc(p.substring(slash + 1)) + "&nopartial=1";
        result(send(HttpRequest.newBuilder(URI.create(host + "/uploadfile?" + query)).PUT(body), false));
    }

    @Override
    public byte[] get(String path) throws IOException {
        JsonNode link;
        try {
            link = call("getfilelink", "path=" + Urls.enc(abs(path)) + "&forcedownload=1");
        } catch (Missing gone) {
            return null;
        }
        String scheme = host.startsWith("http://") ? "http://" : "https://"; // the same as the API's (a test's is plain)
        String hostPart = link.path("hosts").path(0).asText(), file = link.path("path").asText();
        URI where;
        try {
            // Encoded already ("%20") it goes as it is; otherwise each character that needs it is encoded.
            where = file.contains("%") ? URI.create(scheme + hostPart + file)
                    : new URI(scheme.replace("://", ""), hostPart, file, null, null);
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        HttpResponse<byte[]> r = sendBytes(HttpRequest.newBuilder(where).GET());
        if (r.statusCode() != 200) throw new IOException(I18n.t("cloud.error.server", r.statusCode()));
        return r.body();
    }

    private JsonNode call(String method, String query) throws IOException {
        return result(send(HttpRequest.newBuilder(URI.create(host + "/" + method + (query.isEmpty() ? "" : "?" + query))).GET(), true));
    }

    /** pCloud answers 200 with {"result": 0} or an error number: none, missing, or why, in the person's words. */
    static JsonNode result(String body) throws IOException {
        JsonNode n = JSON.readTree(body);
        int code = n.path("result").asInt(-1);
        if (code == 0) return n;
        if (code == 2002 || code == 2005 || code == 2009) throw new Missing();
        throw new IOException(switch (code) {
            case 1000, 2000, 2094, 2095 -> I18n.t("cloud.error.pcloudLogin");
            case 2008 -> I18n.t("cloud.error.full");
            default -> I18n.t("cloud.error.server", code + " " + n.path("error").asText(""));
        });
    }

    /** A file or folder that isn't there: an answer, not a failure. */
    static final class Missing extends IOException {
        Missing() {
            super("missing");
        }
    }

    private String send(HttpRequest.Builder b, boolean question) throws IOException {
        if (question) b.timeout(answer);
        try {
            HttpResponse<String> r = http.send(b.header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IOException(I18n.t("cloud.error.server", r.statusCode()));
            return r.body();
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException(I18n.t("cloud.error.noAnswer"), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest.Builder b) throws IOException {
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static String abs(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }
}

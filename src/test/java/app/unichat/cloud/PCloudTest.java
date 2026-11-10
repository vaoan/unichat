package app.unichat.cloud;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** pCloud through its own API and its browser sign-in, which works with two-step verification. */
class PCloudTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** A sign-in that waits as long as the test does. */
    static final java.time.Duration NO_LIMIT = java.time.Duration.ofNanos(Long.MAX_VALUE);

    @Test
    void foldersFilesAndWhetherTheyChanged() throws Exception {
        try (FakePCloud cloud = new FakePCloud("tok")) {
            PCloudApi api = new PCloudApi(cloud.host(), "tok");
            api.check();
            assertEquals("yo@correo.com", api.email());
            String photo = "Unichat/Multimedia/Dann Serna/foto [abc].jpg";
            assertFalse(api.exists(photo));
            api.mkdirs("Unichat/Multimedia/Dann Serna");
            api.put(photo, "f".getBytes(StandardCharsets.UTF_8));
            assertTrue(api.exists(photo), "names with spaces and brackets");
            assertArrayEquals("f".getBytes(StandardCharsets.UTF_8), api.get(photo));
            assertNull(api.get("nada.txt"));
            String before = api.stamp(photo);
            cloud.put("/" + photo, "g".getBytes(StandardCharsets.UTF_8));
            assertNotEquals(before, api.stamp(photo), "a new version is told apart");
            assertNull(api.stamp("nada.txt"));
        }
    }

    @Test
    void aFileComesDownIntoAFileNeverWholeInMemory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        try (FakePCloud cloud = new FakePCloud("tok")) {
            byte[] video = new byte[300_000];
            new java.util.Random(3).nextBytes(video);
            cloud.put("/Unichat/Multimedia/Ana/video [1].mp4", video);
            StorageDownloads.check(new PCloudApi(cloud.host(), "tok"), "Unichat/Multimedia/Ana/video [1].mp4", video, dir);
        }
    }

    @Test
    void aRevokedPermissionSaysToConnectAgain() throws Exception {
        try (FakePCloud cloud = new FakePCloud("tok")) {
            IOException e = assertThrows(IOException.class, () -> new PCloudApi(cloud.host(), "viejo").check());
            assertEquals(app.unichat.support.I18n.t("cloud.error.pcloudLogin"), e.getMessage());
        }
    }

    @Test
    void theSettingsTravelThroughPCloudToo() throws Exception {
        try (FakePCloud cloud = new FakePCloud("tok")) {
            PCloudApi api = new PCloudApi(cloud.host(), "tok");
            Map<String, String> a = new HashMap<>(Map.of("ui.theme", "DARK")), b = new HashMap<>();
            SyncedPart pa = new SyncedPart(CloudAccount.SETTINGS_FILE, "test-pa", now -> Bundle.of("a", now, a, null), t -> { });
            SyncedPart pb = new SyncedPart(CloudAccount.SETTINGS_FILE, "test-pb", now -> Bundle.of("b", now, b, null), t -> {
                b.clear();
                b.putAll(t.settings());
            });
            try {
                assertEquals(SyncedPart.Action.UPLOAD, pa.sync(api, java.time.Instant.now()));
                assertEquals(SyncedPart.Action.ADOPT, pb.sync(api, java.time.Instant.now()));
                assertEquals("DARK", b.get("ui.theme"));
            } finally {
                app.unichat.settings.Settings.get().set("cloud.base.test-pa", null);
                app.unichat.settings.Settings.get().set("cloud.base.test-pb", null);
            }
        }
    }

    /** What the browser does after the person signs in: pCloud sends it to our page, which passes the "#" part on. */
    private static Map<String, String> signIn(URI authorize, String hash) throws Exception {
        Map<String, String> q = new HashMap<>();
        for (String pair : authorize.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            q.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        String page = HTTP.send(HttpRequest.newBuilder(URI.create(q.get("redirect_uri"))).build(), HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(page.contains("location.hash"), "the page passes the permission on");
        HTTP.send(HttpRequest.newBuilder(URI.create(q.get("redirect_uri") + "/done?" + hash.replace("STATE", q.get("state")))).build(),
                HttpResponse.BodyHandlers.ofString());
        return q;
    }

    @Test
    void signingInInTheBrowserGivesTheAccount() throws Exception {
        URI[] opened = {null};
        CompletableFuture<CloudAccount> login = PCloudLogin.start("APP", u -> opened[0] = u, 0, h -> "https://" + h, NO_LIMIT);
        Map<String, String> asked = signIn(opened[0], "access_token=T0K&token_type=bearer&uid=1&state=STATE&hostname=eapi.pcloud.com");
        assertEquals("APP", asked.get("client_id"));
        assertEquals("token", asked.get("response_type"));
        CloudAccount a = login.join();
        assertEquals(CloudAccount.Service.PCLOUD, a.service());
        assertEquals("https://eapi.pcloud.com", a.url(), "the host pCloud named: Europe's here");
        assertEquals("T0K", a.secret());
    }

    @Test
    void anAnswerForAnotherRequestIsIgnoredAndADeclineFails() throws Exception {
        URI[] opened = {null};
        CompletableFuture<CloudAccount> login = PCloudLogin.start("APP", u -> opened[0] = u, 0, h -> "https://" + h, NO_LIMIT);
        Map<String, String> q = new HashMap<>();
        for (String pair : opened[0].getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            q.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        HttpResponse<String> foreign = HTTP.send(HttpRequest.newBuilder(URI.create(q.get("redirect_uri")
                + "/done?access_token=X&hostname=api.pcloud.com&state=otro")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, foreign.statusCode());
        assertFalse(login.isDone(), "still waiting for ours");
        signIn(opened[0], "error=access_denied&state=STATE");
        assertThrows(CompletionException.class, login::join);
    }

    /** The sign-in's listener is gone: its port can be taken again (the next "Connect" said "port busy" until a restart). */
    static void portIsFree(String redirect) throws IOException {
        Loopback.server(URI.create(redirect).getPort()).stop(0);
    }

    private static String redirect(URI authorize) {
        return Loopback.query(authorize.getRawQuery()).get("redirect_uri");
    }

    @Test
    void aSignInLeftInTheBrowserEndsAtItsDeadlineAndFreesThePort() throws Exception {
        URI[] opened = {null};
        CompletableFuture<CloudAccount> login = PCloudLogin.start("APP", u -> opened[0] = u, 0, h -> "https://" + h, java.time.Duration.ZERO);
        var e = assertThrows(java.util.concurrent.ExecutionException.class, login::get); // get: a JUnit timeout can interrupt it
        assertEquals(app.unichat.support.I18n.t("cloud.signIn.expired"), e.getCause().getMessage());
        portIsFree(redirect(opened[0]));
    }

    @Test
    void cancellingFreesThePort() throws Exception {
        URI[] opened = {null};
        CompletableFuture<CloudAccount> login = PCloudLogin.start("APP", u -> opened[0] = u, 0, h -> "https://" + h, NO_LIMIT);
        login.cancel(false);
        portIsFree(redirect(opened[0]));
    }

    @Test
    void theTokenDoesntStayInTheBrowsersHistory() throws Exception {
        URI[] opened = {null};
        CompletableFuture<CloudAccount> login = PCloudLogin.start("APP", u -> opened[0] = u, 0, h -> "https://" + h, NO_LIMIT);
        try {
            String page = HTTP.send(HttpRequest.newBuilder(URI.create(redirect(opened[0]))).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(page.contains("history.replaceState(null, '', location.pathname)"), "the address loses its \"#access_token=…\"");
            assertTrue(page.indexOf("history.replaceState") > page.indexOf("location.hash"), "after the token was read from it");
        } finally {
            login.cancel(false);
        }
    }

    @Test
    void onlyPCloudsOwnHostsAreTrusted() {
        assertEquals("https://api.pcloud.com", PCloudLogin.pcloudApi("api.pcloud.com"));
        assertEquals("https://eapi.pcloud.com", PCloudLogin.pcloudApi("eapi.pcloud.com"));
        assertNull(PCloudLogin.pcloudApi("evil.com"), "the permission never goes elsewhere");
        assertNull(PCloudLogin.pcloudApi("api.pcloud.com.evil.com"));
        assertNull(PCloudLogin.pcloudApi(null));
    }

    @Test
    void itSaysAFilesSizeAndSha1() throws Exception {
        try (FakePCloud cloud = new FakePCloud("tok")) {
            PCloudApi api = new PCloudApi(cloud.host(), "tok");
            api.mkdirs("Unichat");
            api.put("Unichat/a.jpg", "12345".getBytes(StandardCharsets.UTF_8));
            Storage.RemoteFile f = api.info("Unichat/a.jpg");
            assertEquals(5, f.size());
            assertEquals(Checksums.SHA1, f.kind());
            assertEquals(FakePCloud.sha1("12345".getBytes(StandardCharsets.UTF_8)), f.hash());
            assertNull(api.info("Unichat/nada.jpg"));
        }
    }
}

package app.unichat.cloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Dropbox through its API and its browser sign-in (PKCE): what Unichat's sync needs of it. */
class DropboxTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    private static DropboxApi api(FakeDropbox fake, long single, int chunk) {
        return new DropboxApi("APP", "REFRESH", fake.url(), fake.url(), fake.url() + "/oauth2/token", single, chunk);
    }

    @Test
    void filesWhetherTheyChangedAndNamesWithAccents() throws Exception {
        try (FakeDropbox fake = new FakeDropbox("APP", "REFRESH")) {
            DropboxApi api = api(fake, DropboxApi.SINGLE, 1024);
            api.check();
            assertEquals("yo@correo.com", api.email());
            String photo = "Unichat/Multimedia/Mamá 🌸/foto [abc].jpg";
            assertFalse(api.exists(photo));
            api.mkdirs("Unichat/Multimedia");
            api.mkdirs("Unichat/Multimedia"); // already there: fine
            api.put(photo, "f".getBytes(StandardCharsets.UTF_8));
            assertTrue(api.exists(photo), "accents and emoji in the name (the header only takes ASCII)");
            assertArrayEquals("f".getBytes(StandardCharsets.UTF_8), api.get(photo));
            assertNull(api.get("nada.txt"));
            String before = api.stamp(photo);
            fake.put("/" + photo, "g".getBytes(StandardCharsets.UTF_8));
            assertNotEquals(before, api.stamp(photo), "a new version is told apart");
            assertNull(api.stamp("nada.txt"));
        }
    }

    @Test
    void anExpiredTokenIsMadeAgainFromTheLastingPermission() throws Exception {
        try (FakeDropbox fake = new FakeDropbox("APP", "REFRESH")) {
            DropboxApi api = api(fake, DropboxApi.SINGLE, 1024);
            api.check();
            assertEquals(1, fake.tokensMade.get());
            api.check();
            assertEquals(1, fake.tokensMade.get(), "kept while it works");
            fake.expire();
            api.check();
            assertEquals(2, fake.tokensMade.get(), "made again once it expired, and the call went through");
        }
    }

    @Test
    void aRevokedPermissionSaysToConnectAgain() throws Exception {
        try (FakeDropbox fake = new FakeDropbox("APP", "OTHER")) {
            IOException e = assertThrows(IOException.class, () -> api(fake, DropboxApi.SINGLE, 1024).check());
            assertEquals(app.unichat.support.I18n.t("cloud.error.dropboxLogin"), e.getMessage());
        }
    }

    @Test
    void aBigFileGoesUpInPieces() throws Exception {
        try (FakeDropbox fake = new FakeDropbox("APP", "REFRESH")) {
            byte[] video = new byte[2500];
            new java.util.Random(7).nextBytes(video);
            Path file = Files.write(dir.resolve("video.mp4"), video);
            api(fake, 1000, 1000).put("Unichat/Multimedia/video.mp4", file); // over the single-call limit
            assertArrayEquals(video, fake.files.get("/Unichat/Multimedia/video.mp4"), "whole, in order");
            assertEquals(3, fake.sessionCalls.get(), "start, one append, finish");
        }
    }

    @Test
    void theSettingsTravelThroughDropboxToo() throws Exception {
        try (FakeDropbox fake = new FakeDropbox("APP", "REFRESH")) {
            DropboxApi api = api(fake, DropboxApi.SINGLE, 1024);
            Map<String, String> a = new HashMap<>(Map.of("ui.theme", "DARK")), b = new HashMap<>();
            SyncedPart pa = new SyncedPart(CloudAccount.SETTINGS_FILE, "test-da", now -> Bundle.of("a", now, a, null), t -> { });
            SyncedPart pb = new SyncedPart(CloudAccount.SETTINGS_FILE, "test-db", now -> Bundle.of("b", now, b, null), t -> {
                b.clear();
                b.putAll(t.settings());
            });
            try {
                assertEquals(SyncedPart.Action.UPLOAD, pa.sync(api, java.time.Instant.now()));
                assertEquals(SyncedPart.Action.ADOPT, pb.sync(api, java.time.Instant.now()));
                assertEquals("DARK", b.get("ui.theme"));
            } finally {
                app.unichat.settings.Settings.get().set("cloud.base.test-da", null);
                app.unichat.settings.Settings.get().set("cloud.base.test-db", null);
            }
        }
    }

    private static Map<String, String> query(URI u) {
        return Loopback.query(u.getRawQuery());
    }

    @Test
    void signingInGivesTheLastingPermissionOnlyWithThisSignInsOwnSecret() throws Exception {
        try (FakeDropbox fake = new FakeDropbox("APP", "REFRESH-1")) {
            URI[] opened = {null};
            CompletableFuture<CloudAccount> login = DropboxLogin.start("APP", u -> opened[0] = u, 0, fake.url() + "/oauth2/token");
            Map<String, String> asked = query(opened[0]);
            assertEquals("APP", asked.get("client_id"));
            assertEquals("offline", asked.get("token_access_type"), "a lasting permission");
            assertEquals("S256", asked.get("code_challenge_method"));
            fake.challenge = asked.get("code_challenge"); // what Dropbox keeps from the request
            HttpResponse<String> back = HTTP.send(HttpRequest.newBuilder(URI.create(asked.get("redirect_uri")
                    + "?code=the-code&state=" + asked.get("state"))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, back.statusCode());
            CloudAccount a = login.join();
            assertEquals(CloudAccount.Service.DROPBOX, a.service());
            assertEquals("REFRESH-1", a.secret(), "the lasting permission, exchanged with the verifier only Unichat had");
        }
    }

    @Test
    void anAnswerForAnotherRequestIsIgnoredAndADeclineFails() throws Exception {
        try (FakeDropbox fake = new FakeDropbox("APP", "REFRESH")) {
            URI[] opened = {null};
            CompletableFuture<CloudAccount> login = DropboxLogin.start("APP", u -> opened[0] = u, 0, fake.url() + "/oauth2/token");
            Map<String, String> asked = query(opened[0]);
            HttpResponse<String> foreign = HTTP.send(HttpRequest.newBuilder(URI.create(asked.get("redirect_uri")
                    + "?code=x&state=otro")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, foreign.statusCode());
            assertFalse(login.isDone(), "still waiting for ours");
            HTTP.send(HttpRequest.newBuilder(URI.create(asked.get("redirect_uri") + "?error=access_denied&state=" + asked.get("state"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThrows(CompletionException.class, login::join);
        }
    }

    @Test
    void itSaysAFilesSizeAndContentHashWhichMatchesTheOneComputedHere() throws Exception {
        try (FakeDropbox fake = new FakeDropbox("APP", "REFRESH")) {
            DropboxApi api = api(fake, DropboxApi.SINGLE, 1024);
            Path file = Files.writeString(dir.resolve("a.jpg"), "12345");
            api.put("Unichat/a.jpg", file);
            Storage.RemoteFile f = api.info("Unichat/a.jpg");
            assertEquals(5, f.size());
            assertEquals(Checksums.DROPBOX, f.kind());
            assertEquals(Checksums.of(file, Checksums.DROPBOX), f.hash(), "the same hash computed here and there");
            assertNull(api.info("Unichat/nada.jpg"));
        }
    }
}

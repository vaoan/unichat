package app.unichat.cloud;

import app.unichat.Present;
import app.unichat.support.I18n;
import java.io.IOException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The passkey probe's page: what it serves, and what it takes back from the browser. */
class PasskeyPageTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final List<PasskeyPage.Finding> found = new CopyOnWriteArrayList<>();
    private final URI[] opened = {null};

    private CompletableFuture<Void> start() throws IOException {
        return PasskeyPage.start(u -> opened[0] = u, found::add, 0, PCloudTest.NO_LIMIT);
    }

    /** The page's address, at 127.0.0.1: the test's HTTP client is no browser, it needs no relying party. */
    private URI at(String path) {
        return URI.create("http://127.0.0.1:" + opened[0].getPort() + path);
    }

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(at("/passkey/result"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String state() throws IOException, InterruptedException {
        String page = HTTP.send(HttpRequest.newBuilder(at("/passkey")).build(), HttpResponse.BodyHandlers.ofString()).body();
        var m = Pattern.compile("const STATE = \"([0-9a-f]+)\"").matcher(page);
        assertTrue(m.find(), "the page carries its state");
        return m.group(1);
    }

    @Test
    void theBrowserOpensItAtLocalhostTheRelyingParty() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            assertEquals("localhost", opened[0].getHost(), "WebAuthn's relying party is localhost: 127.0.0.1 would be refused");
            assertEquals("/passkey", opened[0].getPath());
        } finally {
            probe.cancel(false);
        }
    }

    @Test
    void aResultWithAMissingOrWrongStateIsRefused() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            state();
            assertEquals(400, post("step=get&prfLength=32").statusCode());
            assertEquals(400, post("step=get&prfLength=32&state=otro").statusCode());
            assertTrue(found.isEmpty(), "nothing recorded from an answer that isn't ours");
            assertFalse(probe.isDone(), "still waiting for ours");
        } finally {
            probe.cancel(false);
        }
    }

    @Test
    void aResultWithoutPrfIsRecordedAsSuch() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            HttpResponse<String> r = post("state=" + state() + "&step=get&credentialId=abc_-1&attachment=platform&userAgent=Chrome%2F147");
            assertEquals(200, r.statusCode());
            assertEquals(I18n.t("cloud.passkey.noPrf"), r.body(), "the page shows Unichat's words");
            PasskeyPage.Finding f = found.getFirst();
            assertEquals(PasskeyPage.Step.GET, f.step());
            assertEquals(-1, f.prfLength());
            assertFalse(f.prf());
            assertTrue(f.line().contains("prfLength=none"), f.line());
            assertTrue(f.line().contains("prfFingerprint=none"), f.line());
            assertTrue(f.line().contains("credentialId=abc_-1"), f.line());
        } finally {
            probe.cancel(false);
        }
    }

    @Test
    void aResultWithPrfRecordsItsLengthOnly() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            HttpResponse<String> r = post("state=" + state()
                    + "&step=get&prfLength=32&prfFingerprint=0123456789abcdef&prf=U0VDUkVUQllURVM&first=U0VDUkVUQllURVM");
            assertEquals(I18n.t("cloud.passkey.gotPrf"), r.body());
            PasskeyPage.Finding f = found.getFirst();
            assertEquals(32, f.prfLength());
            assertTrue(f.prf());
            assertTrue(f.line().contains("prfLength=32"), f.line());
            assertFalse(f.line().contains("U0VDUkVUQllURVM"), "bytes sent anyway never reach the record");
            assertTrue(f.line().contains("prfFingerprint=0123456789abcdef"), "the same value everywhere shows as the same fingerprint");
        } finally {
            probe.cancel(false);
        }
    }

    @Test
    void aFingerprintThatIsntOneIsNotKept() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            post("state=" + state() + "&step=get&prfLength=32&prfFingerprint=U0VDUkVUQllURVMU0VDUkVUQllURVM");
            post("state=" + state() + "&step=get&prfLength=32&prfFingerprint=0123456789ABCDEF");
            for (PasskeyPage.Finding f : found) {
                assertTrue(f.line().contains("prfFingerprint=none"), "only 16 lowercase hex characters: " + f.line());
                assertFalse(f.line().contains("U0VDUkVU"));
            }
        } finally {
            probe.cancel(false);
        }
    }

    @Test
    void aPasskeyMadeWithPrfOnSaysSo() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            HttpResponse<String> made = post("state=" + state() + "&step=create&prfEnabled=true");
            assertEquals(I18n.t("cloud.passkey.createdPrf"), made.body());
            assertEquals(true, found.getFirst().prfEnabled());
            post("state=" + state() + "&step=create&prfEnabled=false");
            assertEquals(false, found.get(1).prfEnabled());
            assertEquals(I18n.t("cloud.passkey.createdNoPrf"), found.get(1).said());
            post("state=" + state() + "&step=create");
            assertNull(found.get(2).prfEnabled(), "the browser said nothing about it");
            assertTrue(found.get(2).line().contains("prfEnabled=unknown"), found.get(2).line());
        } finally {
            probe.cancel(false);
        }
    }

    @Test
    void anErrorIsRecordedOnOneLine() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            HttpResponse<String> r = post("state=" + state() + "&step=get&error=NotAllowedError%3A+cancelled%0Aforged+line"
                    + "&userAgent=Mozilla%0D%0Aother");
            assertEquals(I18n.t("cloud.passkey.failed", "NotAllowedError: cancelled forged line"), r.body());
            String line = found.getFirst().line();
            assertFalse(line.contains("\n") || line.contains("\r"), "the browser can't add lines to the record: " + line);
        } finally {
            probe.cancel(false);
        }
    }

    /** What a browser sends for another name (DNS rebinding: evil.example resolved to 127.0.0.1); HttpClient won't set Host. */
    @SuppressFBWarnings(value = "UNENCRYPTED_SOCKET", justification = "talks to the page under test on 127.0.0.1, inside the test")
    private int raw(String request) throws IOException {
        try (java.net.Socket s = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), opened[0].getPort())) {
            s.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(),
                    java.nio.charset.StandardCharsets.US_ASCII)).readLine();
            if (status == null) throw new IOException("no answer");
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    @Test
    void aPageAskedForUnderAnotherNameIsRefused() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            String state = state();
            int port = opened[0].getPort();
            assertEquals(403, raw("GET /passkey HTTP/1.1\r\nHost: evil.example:" + port + "\r\nConnection: close\r\n\r\n"),
                    "its state never reaches another site's script");
            String body = "state=" + state + "&step=get&prfLength=32";
            assertEquals(403, raw("POST /passkey/result HTTP/1.1\r\nHost: evil.example:" + port + "\r\nContent-Type: "
                    + "application/x-www-form-urlencoded\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body));
            assertEquals(200, raw("GET /passkey HTTP/1.1\r\nHost: localhost:" + port + "\r\nConnection: close\r\n\r\n"));
            assertTrue(found.isEmpty());
        } finally {
            probe.cancel(false);
        }
    }

    @Test
    void aResultFromAnotherOriginIsRefused() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            String body = "state=" + state() + "&step=get&prfLength=32";
            HttpResponse<String> foreign = HTTP.send(HttpRequest.newBuilder(at("/passkey/result"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("Origin", "http://evil.example")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, foreign.statusCode());
            assertTrue(found.isEmpty());
            HttpResponse<String> ours = HTTP.send(HttpRequest.newBuilder(at("/passkey/result"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("Origin", "http://localhost:" + opened[0].getPort())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ours.statusCode(), "the page's own origin");
        } finally {
            probe.cancel(false);
        }
    }

    @Test
    void anUnknownStepIsRefused() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            assertEquals(400, post("state=" + state() + "&step=delete").statusCode());
            assertTrue(found.isEmpty());
        } finally {
            probe.cancel(false);
        }
    }

    @Test
    void thePageLoadsNothingFromOutside() throws Exception {
        CompletableFuture<Void> probe = start();
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(at("/passkey")).build(), HttpResponse.BodyHandlers.ofString());
            String page = r.body();
            assertFalse(page.contains("http://") || page.contains("https://") || page.contains("//cdn"), "no outside address");
            assertFalse(page.contains(" src=") || page.contains("<link") || page.contains("@import"), "no outside script or style");
            assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
            assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"), "never framed");
            assertTrue(page.contains("crypto.subtle.digest('SHA-256'"), "the fingerprint is made on the page");
            assertTrue(page.contains("rp: {id: 'localhost'"), "the relying party is localhost");
            assertTrue(page.contains("prf: {eval: {first: SALT}}"), "PRF asked for, at create and at get");
            assertTrue(page.contains("residentKey: 'required'") && page.contains("userVerification: 'required'"));
            assertTrue(page.contains("allowCredentials: []"), "a discoverable passkey: any one made for Unichat is offered");
        } finally {
            probe.cancel(false);
        }
    }

    @Test
    void theSaltIsFixedAndThirtyTwoBytes() {
        assertEquals(32, PasskeyPage.salt().length);
        assertEquals("2605a491e4660e44451bba6dfb0762d9b650ddcbaf633936e4f2261941d8ef63", java.util.HexFormat.of().formatHex(PasskeyPage.salt()),
                "SHA-256 of \"unichat-vault passkey prf\": a passkey's PRF changes with it");
    }

    @Test
    void aProbeLeftInTheBrowserEndsAtItsDeadlineAndFreesThePort() throws Exception {
        CompletableFuture<Void> probe = PasskeyPage.start(u -> opened[0] = u, found::add, 0, Duration.ZERO);
        var e = assertThrows(ExecutionException.class, probe::get); // get: a JUnit timeout can interrupt it
        assertEquals(I18n.t("cloud.passkey.expired"), Present.of(e.getCause()).getMessage(), "a probe, not a sign-in");
        assertInstanceOf(Loopback.Expired.class, e.getCause());
        PCloudTest.portIsFree(opened[0].toString());
    }

    @Test
    void cancellingFreesThePort() throws Exception {
        CompletableFuture<Void> probe = start();
        probe.cancel(false);
        PCloudTest.portIsFree(opened[0].toString());
    }
}

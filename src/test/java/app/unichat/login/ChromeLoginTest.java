package app.unichat.login;

import app.unichat.model.Network;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ChromeLoginTest {
    private static final Duration NO_LIMIT = Duration.ofMillis(Long.MAX_VALUE);

    @TempDir Path dir;

    private static BridgeLogin.CookieParams params(String url) {
        return new BridgeLogin.CookieParams(url, "Agente-Del-Puente/1.0", List.of(
                new BridgeLogin.CookieField("cookie_header", true, List.of(new BridgeLogin.Source("request_header", "Cookie", null)), "\\bJSESSIONID=[^;]+"),
                new BridgeLogin.CookieField("track", true, List.of(new BridgeLogin.Source("request_header", "X-LI-Track", null)), "clientVersion"),
                new BridgeLogin.CookieField("page", true, List.of(new BridgeLogin.Source("request_header", "X-LI-Page-Instance", null)), "urn:li:page")),
                null, ".*/feed.*");
    }

    @Test
    void chromeIsNeverToldItsAutomatedAndKeepsItsOwnIdentityForGoogle() {
        List<String> c = ChromeLogin.command(Path.of("/x/chrome"), Path.of("/p"), 9333, false);
        assertTrue(c.contains("--remote-debugging-port=9333"), "port 0 turns navigator.webdriver on");
        assertTrue(c.contains("--user-data-dir=/p"), "never the person's own profile");
        assertFalse(c.contains("--enable-automation"));
        assertFalse(c.contains("--use-mock-keychain"), "passkeys need the real Keychain");
        assertFalse(c.contains("--headless=new"));
        assertTrue(c.stream().noneMatch(a -> a.startsWith("--user-agent")), "it would reach Google's window too");
    }

    @Test
    void theSitesTabGetsAnIdentityThatDoesntContradictItself() throws Exception {
        JsonNode p = new ObjectMapper().readTree(ChromeLogin.identity(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")).path("params");
        assertEquals("Linux x86_64", p.path("platform").asText());
        assertEquals("Linux", p.path("userAgentMetadata").path("platform").asText());
        assertEquals("141", p.path("userAgentMetadata").path("brands").get(0).path("version").asText());
        assertTrue(new ObjectMapper().readTree(ChromeLogin.identity("Agente/1.0")).path("params").path("userAgentMetadata").isMissingNode());
    }

    @Test
    void onAMacChromeIsStartedAsAnAppOfItsOwn() {
        assertEquals(List.of("/usr/bin/open", "-n", "-W", "-a", "/Applications/G C.app", "--args", "--a", "https://l"),
                ChromeLogin.launched(List.of("/Applications/G C.app/Contents/MacOS/G C", "--a", "https://l")));
        List<String> windows = List.of("/Program Files/Google/Chrome/Application/chrome.exe", "--a");
        assertEquals(windows, ChromeLogin.launched(windows));
        assertEquals(List.of("/x/chrome"), ChromeLogin.launched(List.of("/x/chrome")));
    }

    @Test
    void itReadsTabsAndOnlyTheSitesCookiesAsTheyAre() throws Exception {
        assertEquals(List.of("https://a/"), ChromeLogin.tabsIn("[{\"type\":\"page\",\"url\":\"https://a/\"},{\"type\":\"iframe\",\"url\":\"https://b/\"}]"));
        Map<String, String> c = ChromeLogin.cookiesIn("{\"id\":1,\"result\":{\"cookies\":["
                + "{\"name\":\"JSESSIONID\",\"value\":\"\\\"ajax:1\\\"\",\"domain\":\".www.linkedin.com\"},"
                + "{\"name\":\"li_at\",\"value\":\"x\",\"domain\":\".linkedin.com\"},"
                + "{\"name\":\"SID\",\"value\":\"google\",\"domain\":\".google.com\"}]}}", "linkedin.com");
        assertEquals(Map.of("JSESSIONID", "\"ajax:1\"", "li_at", "x"), c);
    }

    @Test
    void aGuestSessionIsNotASession() {
        ChromeLogin login = new ChromeLogin(Network.LINKEDIN, params("https://www.linkedin.com/login"), Path.of("/x"), dir);
        String feed = "https://www.linkedin.com/feed/";
        assertNull(login.values(feed, Map.of("JSESSIONID", "guest", "bcookie", "x")), "no li_at yet");
        Map<String, String> v = login.values(feed, Map.of("JSESSIONID", "\"ajax:1\"", "li_at", "x", "bcookie", "b"));
        assertTrue(v.get("cookie_header").contains("bcookie=b"), "every cookie the browser has");
        assertTrue(v.get("cookie_header").contains("JSESSIONID=\"ajax:1\""));
    }

    /** Any program here can drive Chrome through its debugging port while it waits: no longer than a login takes. */
    @Test
    void chromeWaitsNoLongerThanALoginTakes() {
        assertTrue(ChromeLogin.MAX_WAIT.compareTo(Duration.ofMinutes(5)) <= 0, ChromeLogin.MAX_WAIT.toString());
    }

    private ChromeLogin waiting(Duration maxWait) {
        return new ChromeLogin(Network.LINKEDIN, params("https://www.linkedin.com/login"), Path.of("/x"), dir, true, maxWait, NO_LIMIT);
    }

    /** Cancelling ends the wait at once, however long the next look would be, so Chrome and its port close right away. */
    @Test
    void cancellingEndsTheWaitAtOnce() throws Exception {
        ChromeLogin login = waiting(Duration.ofDays(1));
        CountDownLatch looked = new CountDownLatch(1);
        CompletableFuture<Map<String, String>> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(login.waitForLogin(() -> {
                    looked.countDown();
                    return null;
                }));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        looked.await();
        login.cancel();
        assertNull(result.get(), "get, not join: JUnit's timeout interrupts it, so a wait that never ends fails");
    }

    @Test
    void outOfTimeItStopsWithoutLooking() throws Exception {
        assertNull(waiting(Duration.ZERO).waitForLogin(() -> fail("no look after the deadline")));
    }

    @Test
    void theValuesOrAClosedWindowEndTheWait() throws Exception {
        assertEquals(Map.of("a", "b"), waiting(Duration.ofDays(1)).waitForLogin(() -> Map.of("a", "b")));
        assertNull(waiting(Duration.ofDays(1)).waitForLogin(() -> ChromeLogin.CLOSED));
    }

    /** With the real browser, off screen: nothing of the person's is touched. */
    @Test
    @EnabledIfSystemProperty(named = "unichat.chromeTests", matches = "true")
    void theRealChromeHandsOverItsHttpOnlyCookiesAndLeavesNothingBehind() throws Exception {
        Path chrome = ChromeLogin.findChrome();
        assertNotNull(chrome);
        AtomicReference<String> agent = new AtomicReference<>();
        AtomicReference<String> popupAgent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", ex -> {
            agent.set(ex.getRequestHeaders().getFirst("User-Agent"));
            ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=\"ajax:1\"; Path=/");
            ex.getResponseHeaders().add("Set-Cookie", "li_at=secreto; Path=/; HttpOnly");
            byte[] b = "<script>window.open('/popup');setTimeout(function(){location.href='/feed'},1500)</script>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            try (var o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.createContext("/popup", ex -> {
            popupAgent.set(ex.getRequestHeaders().getFirst("User-Agent"));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/feed", ex -> {
            byte[] b = "<p>hola</p>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (var o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
        Path profile = dir.resolve("perfil");
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> v = new ChromeLogin(Network.LINKEDIN, params(base + "/login"), chrome, profile, true).run();
            assertNotNull(v);
            assertTrue(v.get("cookie_header").contains("li_at=secreto"), "an HttpOnly cookie too");
            assertTrue(v.get("track").contains("clientVersion"));
            assertEquals("Agente-Del-Puente/1.0", agent.get());
            assertNotNull(popupAgent.get(), "the window it opened");
            assertTrue(popupAgent.get().contains("Chrome/"), "a window it opens, like Google's, stays the real Chrome: " + popupAgent.get());
            assertFalse(Files.exists(profile), "the profile is gone");
        } finally {
            server.stop(0);
        }
    }
}

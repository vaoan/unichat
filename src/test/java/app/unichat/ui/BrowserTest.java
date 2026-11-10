package app.unichat.ui;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Links come from messages anyone can send: only web pages reach the browser, never a handler that runs or reads something here. */
class BrowserTest {
    @Test
    void webPagesOpen() {
        assertEquals(Optional.of(URI.create("https://example.com/a?b=c#d")), Browser.web("https://example.com/a?b=c#d"));
        assertTrue(Browser.web("http://matrix.to/#/@a:b").isPresent());
        assertTrue(Browser.web("HTTPS://Example.com").isPresent(), "a scheme in capitals is still the web");
        assertTrue(Browser.web("  https://example.com  ").isPresent(), "spaces around a pasted address");
    }

    @Test
    void anythingElseIsRefused() {
        for (String url : List.of("file:///etc/passwd", "file://server/share/x.exe", "smb://attacker/share", "\\\\attacker\\share",
                "ms-msdt:/id PCWDiagnostic", "search-ms:query=x&crumb=location:\\\\attacker", "javascript:alert(1)",
                "data:text/html,<script>1</script>", "vbscript:x", "ftp://example.com/", "mailto:a@b.c", "https:///no-host",
                "//example.com/no-scheme", "example.com", "", "https://exa mple.com/")) {
            assertEquals(Optional.empty(), Browser.web(url), url);
        }
        assertEquals(Optional.empty(), Browser.web(null));
    }
}

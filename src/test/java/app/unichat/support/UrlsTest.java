package app.unichat.support;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UrlsTest {
    @Test
    void aSpaceIsNeverAPlus() {
        assertEquals("con%20espacio", Urls.enc("con espacio"));
    }

    @Test
    void aPlusStaysAPlusOnceDecoded() {
        assertEquals("%2B57%20300", Urls.enc("+57 300"));
        assertEquals("/+57 300", URI.create("http://h/" + Urls.enc("+57 300")).getPath());
    }

    @Test
    void matrixIdsAndNonAsciiAreEscaped() {
        assertEquals("%21room%3Aexample.org", Urls.enc("!room:example.org"));
        assertEquals("a%2Fb%3Fc%23d", Urls.enc("a/b?c#d"));
        assertEquals("ni%C3%B1o", Urls.enc("niño"));
    }

    @Test
    void addressesInTextWithoutTheSentencesPunctuation() {
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("https://youtu.be/abc", "http://x.org/a?b=1"),
                Urls.in("mira https://youtu.be/abc, y http://x.org/a?b=1."));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), Urls.in(null));
    }
}

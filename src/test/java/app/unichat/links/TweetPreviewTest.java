package app.unichat.links;

import app.unichat.Present;
import app.unichat.model.LinkPreview;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TweetPreviewTest {
    /** FxTwitter's answer for the post sent to Seth Miski (2026-11-14), cut to what's read. */
    private static final String GIF_POST = """
            {"code":200,"message":"OK","tweet":{"text":"This is what I’ve been doing over Cancun lately~ 😉",
             "author":{"name":"Montelobo (LOST AT 16K)","screen_name":"Montelobo_"},
             "media":{"all":[{"url":"https://video.twimg.com/tweet_video/HXHiRchaoAAa4L1.mp4",
               "thumbnail_url":"https://pbs.twimg.com/tweet_video_thumb/HXHiRchaoAAa4L1.jpg",
               "width":800,"height":866,"format":"video/mp4","type":"gif"}]}}}""";

    @Test
    void aPostOnXOrItsFixingSitesIsAskedOfFxTwittersApi() {
        assertEquals("https://api.fxtwitter.com/montelobo_/status/2121725725623628253",
                TweetPreview.api("https://fixupx.com/montelobo_/status/2121725725623628253"));
        assertEquals("https://api.fxtwitter.com/a_b/status/1", TweetPreview.api("https://www.x.com/a_b/status/1/photo/1?s=20"));
        assertEquals("https://api.fxtwitter.com/a/status/2", TweetPreview.api("https://twitter.com/a/statuses/2"));
        assertNull(TweetPreview.api("https://x.com/hashtag/b"), "not a post");
        assertNull(TweetPreview.api("https://x.com.evil.example/a/status/1"), "another site");
        assertNull(TweetPreview.api("https://x.com/a/status/..%2F..%2Fx"), "only digits for the post");
        assertNull(TweetPreview.api("no es un enlace"));
    }

    @Test
    void aGifPostIsACardWithItsGifToPlay() throws Exception {
        LinkPreview p = Present.of(TweetPreview.parse(GIF_POST, "https://fixupx.com/montelobo_/status/2121725725623628253"));
        assertEquals("https://fixupx.com/montelobo_/status/2121725725623628253", p.url(), "the link sent, not the API");
        assertEquals("X", p.site());
        assertEquals("Montelobo (LOST AT 16K) (@Montelobo_)", p.title());
        assertEquals("This is what I’ve been doing over Cancun lately~ 😉", p.description());
        assertEquals("https://video.twimg.com/tweet_video/HXHiRchaoAAa4L1.mp4", Present.of(p.video()).url());
        assertTrue(p.gif(), "plays by itself, muted, looping");
        assertEquals("https://pbs.twimg.com/tweet_video_thumb/HXHiRchaoAAa4L1.jpg", p.image());
        assertEquals(800, p.width());
        assertEquals(866, p.height());
    }

    @Test
    void aPhotoPostHasItsPictureAndNoVideoAndOnlyWebAddressesCount() throws Exception {
        LinkPreview photo = Present.of(TweetPreview.parse("""
                {"tweet":{"text":"hola","author":{"name":"Ana","screen_name":"ana"},
                 "media":{"all":[{"type":"photo","url":"https://pbs.twimg.com/media/a.jpg","width":1200,"height":800}]}}}""", "https://x.com/ana/status/3"));
        assertEquals("https://pbs.twimg.com/media/a.jpg", photo.image());
        assertNull(photo.video());
        assertFalse(photo.gif());
        LinkPreview local = Present.of(TweetPreview.parse("""
                {"tweet":{"text":"x","media":{"all":[{"type":"video","url":"file:///etc/passwd","thumbnail_url":"smb://h/s"}]}}}""", "https://x.com/a/status/4"));
        assertNull(local.video());
        assertNull(local.image());
        assertNull(TweetPreview.parse("{\"code\":404}", "https://x.com/a/status/5"), "no such post");
    }
}

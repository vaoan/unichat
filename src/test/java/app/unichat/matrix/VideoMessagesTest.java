package app.unichat.matrix;

import app.unichat.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How bridges say what a video or reel is. */
class VideoMessagesTest {
    private static Message parse(String msgtype, String json) throws Exception {
        Message m = new Message("$1", "!c", "@a:s", "A", false, java.time.Instant.EPOCH);
        JsonNode content = MatrixClient.JSON.readTree(json);
        SyncEvents.applyContent(m, msgtype, content.path("body").asText(), content);
        return m;
    }

    private static Message video(String info) throws Exception {
        return parse("m.video", "{\"body\":\"a.mp4\",\"url\":\"mxc://s/1\",\"info\":{\"mimetype\":\"video/mp4\"" + info + "}}");
    }

    @Test
    void gifsPlayInTheChatWhateverTheirSize() throws Exception {
        assertTrue(video(",\"fi.mau.gif\":true,\"size\":7429228").playsInline());
    }

    @Test
    void shortSmallClipsPlayInTheChatAndTheRestWaitToBeOpened() throws Exception {
        assertTrue(video(",\"size\":900000").playsInline(), "small, duration unknown");
        assertTrue(video(",\"size\":900000,\"duration\":6000").playsInline());
        assertFalse(video(",\"size\":3528335").playsInline(), "too heavy");
        assertFalse(video(",\"size\":900000,\"duration\":40000").playsInline(), "too long");
        assertFalse(video("").playsInline(), "size unknown: it might be huge");
    }

    @Test
    void stickersThatCameAsVideoPlayInTheChat() throws Exception {
        assertTrue(parse("m.sticker", "{\"body\":\"s\",\"url\":\"mxc://s/1\",\"info\":{\"mimetype\":\"video/webm\"}}").playsInline());
        assertFalse(parse("m.sticker", "{\"body\":\"s\",\"url\":\"mxc://s/1\",\"info\":{\"mimetype\":\"image/webp\"}}").playsInline());
    }

    @Test
    void aReelWithoutVideoIsItsPictureAndItsLink() throws Exception {
        Message reel = parse("m.image", "{\"body\":\"r.jpg\",\"url\":\"mxc://s/1\",\"external_url\":\"https://www.instagram.com/reel/abc/\"}");
        assertTrue(reel.isReelLink());
        assertEquals("https://www.instagram.com/reel/abc/", reel.externalUrl());
    }

    @Test
    void onlyAReelPageOfTheNetworkCounts() throws Exception {
        for (String url : new String[]{"https://www.instagram.com/p/abc/", "http://www.instagram.com/reel/abc/",
                "https://instagram.com.evil.example/reel/abc", "file:///etc/passwd"}) {
            assertFalse(parse("m.image", "{\"body\":\"r\",\"url\":\"mxc://s/1\",\"external_url\":\"" + url + "\"}").isReelLink(), url);
        }
        assertFalse(parse("m.video", "{\"body\":\"r\",\"url\":\"mxc://s/1\",\"external_url\":\"https://www.instagram.com/reel/abc/\"}").isReelLink(),
                "a reel with its video plays here");
    }

    @Test
    void videosKeepAnExtensionThePlayerRecognizes() {
        assertEquals(".mp4", MediaDownloader.extension("video/mp4"));
        assertEquals(".mp4", MediaDownloader.extension("video/quicktime; codecs=x"));
        assertEquals(".m4v", MediaDownloader.extension("video/x-m4v"));
    }
}

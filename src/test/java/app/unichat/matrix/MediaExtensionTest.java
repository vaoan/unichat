package app.unichat.matrix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaExtensionTest {
    @Test
    void theCacheNamesQuicktimeMp4BecauseJavaFxRefusesMov() {
        assertEquals(".mp4", MediaDownloader.extension("video/quicktime"));
    }

    @Test
    void everythingElseIsTheSharedTable() {
        assertEquals(".ogg", MediaDownloader.extension("audio/ogg; codecs=opus"));
        assertEquals("", MediaDownloader.extension(null));
    }
}

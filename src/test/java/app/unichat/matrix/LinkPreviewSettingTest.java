package app.unichat.matrix;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkPreviewSettingTest {
    @Test
    void withPreviewsOffNoLinkIsOpened() throws Exception {
        Settings s = Settings.get();
        s.setLinkPreviews(false);
        try {
            MediaDownloader d = new MediaDownloader(null, Executors.newSingleThreadExecutor());
            // 192.0.2.1 never answers (TEST-NET): if it were opened, this would wait, not come back empty at once
            assertTrue(d.linkMedia("https://192.0.2.1/video.mp4").get(1, TimeUnit.SECONDS).isEmpty());
        } finally {
            s.setLinkPreviews(true);
        }
    }
}

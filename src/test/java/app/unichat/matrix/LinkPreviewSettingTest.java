package app.unichat.matrix;

import app.unichat.settings.Settings;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkPreviewSettingTest {
    @Test
    void withPreviewsOffNoLinkIsOpened() throws Exception {
        Settings s = Settings.get();
        s.setLinkPreviews(false);
        try {
            MediaDownloader d = new MediaDownloader(null, Executors.newSingleThreadExecutor());
            // Answered right there, without opening anything: a link that were opened couldn't be done yet
            var found = d.linkMedia("https://192.0.2.1/video.mp4");
            assertTrue(found.isDone(), "answered without opening the link");
            assertTrue(found.get().isEmpty());
        } finally {
            s.setLinkPreviews(true);
        }
    }
}

package app.unichat.ui.timeline;

import app.unichat.support.I18n;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The snapshot's waiting texts are what a person would write, in the UI language (demo.draft.*). */
class SnapshotDraftsTest {
    @Test
    void theDraftsFollowTheUiLanguage() {
        String before = I18n.locale().getLanguage();
        try {
            I18n.use("en");
            List<String> en = ThreadPane.snapshotDrafts();
            assertEquals(4, en.size());
            assertEquals("think i cant make it tomorrow", en.get(0));
            I18n.use("es");
            assertEquals("creo q mañana no puedo", ThreadPane.snapshotDrafts().get(0));
        } finally {
            I18n.use(before);
        }
    }
}

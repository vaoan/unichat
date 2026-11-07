package app.unichat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MimeTypesTest {
    @Test
    void parametersAndCaseDontMatter() {
        assertEquals(".ogg", MimeTypes.extension("audio/ogg; codecs=opus"));
        assertEquals(".ogg", MimeTypes.extension("AUDIO/OGG;codecs=opus"));
        assertEquals(".png", MimeTypes.extension("  image/png  "));
    }

    @Test
    void unknownIsEmpty() {
        assertEquals("", MimeTypes.extension(null));
        assertEquals("", MimeTypes.extension(""));
        assertEquals("", MimeTypes.extension(";"));
        assertEquals("", MimeTypes.extension("application/x-nada"));
    }

    @Test
    void quicktimeIsMov() {
        assertEquals(".mov", MimeTypes.extension("video/quicktime"));
    }
}

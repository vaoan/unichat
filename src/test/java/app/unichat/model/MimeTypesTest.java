package app.unichat.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void aFilesNameGivesItsTypeWhateverItsCase() {
        assertEquals("image/jpeg", MimeTypes.ofFileName("Foto.JPEG"));
        assertEquals("image/jpeg", MimeTypes.ofFileName("a.jpg"));
        assertEquals("audio/mp4", MimeTypes.ofFileName("nota.m4a"));
        assertEquals("video/x-matroska", MimeTypes.ofFileName("peli.mkv"));
        assertEquals("application/pdf", MimeTypes.ofFileName("x.tar.pdf"));
    }

    @Test
    void anUnknownOrMissingExtensionHasNoType() {
        assertNull(MimeTypes.ofFileName("LEEME"));
        assertNull(MimeTypes.ofFileName("datos.xyz"));
        assertNull(MimeTypes.ofFileName("."));
        assertNull(MimeTypes.ofFileName(null));
    }

    @Test
    void theTableGoesBothWays() {
        for (String type : new String[] {"image/png", "video/mp4", "audio/ogg", "application/pdf", "image/bmp"}) {
            assertEquals(type, MimeTypes.ofFileName("f" + MimeTypes.extension(type)));
        }
    }

    @Test
    void aFileNothingNamesIsUnknown() {
        assertEquals(MimeTypes.UNKNOWN, MimeTypes.of(Path.of("no-existe-sin-extension")));
        assertEquals("image/webp", MimeTypes.ofFileName("no-existe.webp"));
    }
}

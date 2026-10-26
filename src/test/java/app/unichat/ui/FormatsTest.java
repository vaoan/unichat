package app.unichat.ui;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatsTest {
    private static final Instant NOW = Instant.parse("2026-10-26T15:00:00Z"); // Monday

    @Test
    void listStamp() {
        assertEquals("14:05", Formats.listStamp(Instant.parse("2026-10-26T14:05:00Z"), NOW, ZoneOffset.UTC));
        assertEquals("Ayer", Formats.listStamp(Instant.parse("2026-10-25T09:00:00Z"), NOW, ZoneOffset.UTC));
        assertEquals("jue", Formats.listStamp(Instant.parse("2026-10-22T09:00:00Z"), NOW, ZoneOffset.UTC));
        assertEquals("3/2/26", Formats.listStamp(Instant.parse("2026-02-03T09:00:00Z"), NOW, ZoneOffset.UTC));
        assertEquals("", Formats.listStamp(Instant.EPOCH, NOW, ZoneOffset.UTC));
    }

    @Test
    void daySeparator() {
        assertEquals("Hoy", Formats.daySeparator(NOW, NOW, ZoneOffset.UTC));
        assertEquals("Martes, 3 de febrero", Formats.daySeparator(Instant.parse("2026-02-03T09:00:00Z"), NOW, ZoneOffset.UTC));
        assertEquals("Miércoles, 3 de diciembre de 2025", Formats.daySeparator(Instant.parse("2025-12-03T09:00:00Z"), NOW, ZoneOffset.UTC));
    }

    @Test
    void initialsSizesTyping() {
        assertEquals("AM", Formats.initials("Ana María"));
        assertEquals("D", Formats.initials("#desarrollo"));
        assertEquals("?", Formats.initials("  "));
        assertEquals("1.5 MB", Formats.fileSize(1572864));
        assertEquals("Ana y Luis están escribiendo…", Formats.typing(List.of("Ana", "Luis")));
    }

    @Test
    void accountShortNames() {
        assertEquals("33", Formats.accountShort("+57 300 111 2233"));
        assertEquals("00", Formats.accountShort("16505550000"));
        assertEquals("T", Formats.accountShort("Trabajo"));
        assertEquals("AP", Formats.accountShort("Ana Pérez"));
        assertEquals("H", Formats.accountShort("@ejemplo"));
        assertEquals("?", Formats.accountShort(null));
    }
}

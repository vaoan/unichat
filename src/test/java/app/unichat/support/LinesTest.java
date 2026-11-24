package app.unichat.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinesTest {
    @Test
    void aNameStaysOnOneLineReadingOneWayWithItsEmojiWhole() {
        assertEquals("Ana falsa", Lines.one(" Ana\n falsa\u001b "));
        assertEquals("gpj.exe", Lines.one("\u202egpj.exe"));
        assertEquals("Familia 👨‍👩‍👧", Lines.one("Familia 👨‍👩‍👧"), "joiners stay");
    }
}

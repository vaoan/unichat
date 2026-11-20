package app.unichat.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class RecoveryCodeTest {
    @Test
    void aCodeIsShownAsEightGroupsOfFour() {
        RecoveryCode code = RecoveryCode.random();
        assertEquals(20, code.bits().length);
        assertTrue(code.shown().matches("([0-9A-HJKMNP-TV-Z]{4}-){7}[0-9A-HJKMNP-TV-Z]{4}"), code.shown());
        assertEquals(code.shown().split("-")[2], code.group(3));
        assertEquals(code.shown().split("-")[0], code.group(1));
        assertEquals(code.shown().split("-")[7], code.group(8));
        assertNotEquals(code, RecoveryCode.random());
    }

    @Test
    void aCodeTypedLooselyStillOpens() {
        RecoveryCode code = RecoveryCode.random();
        assertEquals(code, RecoveryCode.parse(code.shown()));
        assertEquals(code, RecoveryCode.parse(code.shown().replace("-", "").toLowerCase()));
        assertEquals(code, RecoveryCode.parse("  " + code.shown().replace('-', ' ').toLowerCase() + " "));
        RecoveryCode zeros = RecoveryCode.parse("0000-0000-0000-0000-0000-0000-0000-0001");
        assertEquals(zeros, RecoveryCode.parse("oooo-oooo-oooo-oooo-oooo-oooo-oooo-oool"));
        assertEquals(zeros, RecoveryCode.parse("0000-0000-0000-0000-0000-0000-0000-000i"));
        assertEquals(zeros, RecoveryCode.parse("0000-0000-0000-0000-0000-0000-0000-00u01"));
    }

    @Test
    void aCodeTypedIsReadFromItsCharactersAsTyped() {
        RecoveryCode code = RecoveryCode.random();
        char[] typed = code.shown().toLowerCase(java.util.Locale.ROOT).replace("-", " ").replace('0', 'o').toCharArray();
        assertEquals(code, RecoveryCode.parse(typed), "never made into a text that stays in memory");
        assertNull(RecoveryCode.parse("ABCD".toCharArray()));
        assertNull(RecoveryCode.parse((char[]) null));
    }

    @Test
    void aCodeOfTheWrongLengthIsNone() {
        String shown = RecoveryCode.random().shown();
        assertNull(RecoveryCode.parse((String) null));
        assertNull(RecoveryCode.parse(""));
        assertNull(RecoveryCode.parse(shown.substring(0, shown.length() - 1)));
        assertNull(RecoveryCode.parse(shown + "A"));
        assertNull(RecoveryCode.parse("!" + shown.substring(1)));
    }

    @Test
    void itNeverPrintsItself() {
        RecoveryCode code = RecoveryCode.random();
        for (int i = 1; i <= 8; i++) assertFalse(code.toString().contains(code.group(i)));
        assertEquals(code.hashCode(), RecoveryCode.parse(code.shown()).hashCode());
    }

    @Test
    void unicodeDashesAndAnyWhitespaceAreForgiven() {
        RecoveryCode code = RecoveryCode.random();
        assertEquals(code, RecoveryCode.parse(code.shown().replace("-", "\u2011").replace("\u2011", "\u2013 ")));
        assertEquals(code, RecoveryCode.parse(code.shown().replace("-", "\u2212\u00a0").toLowerCase()));
        assertEquals(code, RecoveryCode.parse("\t" + code.shown().replace("-", "\n") + "\u3000"));
        assertEquals(code, RecoveryCode.parse(code.shown().replace("-", "\u2010\u2012\u2014\u2015")));
    }

    @Test
    void caseFoldingIgnoresTheLocale() {
        Locale was = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            RecoveryCode zeros = RecoveryCode.parse("0000-0000-0000-0000-0000-0000-0000-0001");
            assertEquals(zeros, RecoveryCode.parse("0000-0000-0000-0000-0000-0000-0000-000i"));
        } finally {
            Locale.setDefault(was);
        }
    }

    @Test
    void aCodeNeedsExactlyTwentyBytes() {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryCode(new byte[19]));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryCode(null));
    }

    @Test
    void aGroupTypedBackIsForgivenAsTheCodeIs() {
        RecoveryCode c = RecoveryCode.random();
        String shown = c.shown();
        for (int g = 1; g <= RecoveryCheck.GROUPS; g++) {
            String group = shown.split("-")[g - 1];
            assertTrue(RecoveryCheck.matches(shown, g, " " + group.toLowerCase(java.util.Locale.ROOT).replace('0', 'o').replace('1', 'l') + " "), group);
            assertFalse(RecoveryCheck.matches(shown, g, group.substring(1)), "not all of it");
        }
        assertFalse(RecoveryCheck.matches(shown, 0, "ABCD"));
        assertFalse(RecoveryCheck.matches(shown, 9, "ABCD"));
        assertFalse(RecoveryCheck.matches("no es un código", 1, "NOES"));
        assertFalse(RecoveryCheck.matches(shown, 1, null));
    }
}

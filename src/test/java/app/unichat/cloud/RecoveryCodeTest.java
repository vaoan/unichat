package app.unichat.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void aCodeOfTheWrongLengthIsNone() {
        String shown = RecoveryCode.random().shown();
        assertNull(RecoveryCode.parse(null));
        assertNull(RecoveryCode.parse(""));
        assertNull(RecoveryCode.parse(shown.substring(0, shown.length() - 1)));
        assertNull(RecoveryCode.parse(shown + "A"));
        assertNull(RecoveryCode.parse("!" + shown.substring(1)));
    }

    @Test
    void itNeverPrintsItself() {
        RecoveryCode code = RecoveryCode.random();
        assertFalse(code.toString().contains(code.group(1)) && code.toString().contains(code.group(2)));
        assertEquals(code.hashCode(), RecoveryCode.parse(code.shown()).hashCode());
    }
}

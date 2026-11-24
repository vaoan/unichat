package app.unichat.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NumbersTest {
    @Test
    void aNumberIsRead() {
        assertEquals(42, Numbers.intOf("42"));
        assertEquals(-7, Numbers.intOf("-7"));
        assertEquals(255, Numbers.intOf("ff", 16));
        assertEquals(10_000_000_000L, Numbers.longOf("10000000000"));
        assertEquals(493L, Numbers.longOf("755", 8));
        assertEquals(1.5, Numbers.doubleOf("1.5"));
    }

    @Test
    void whatIsntOneIsNull() {
        for (String bad : new String[]{"", " 1", "uno", "1.5", "99999999999"}) assertNull(Numbers.intOf(bad), bad);
        assertNull(Numbers.intOf("fg", 16));
        for (String bad : new String[]{"", "uno", "1.5", "99999999999999999999"}) assertNull(Numbers.longOf(bad), bad);
        assertNull(Numbers.longOf("8", 8));
        for (String bad : new String[]{"", "uno", "1,5"}) assertNull(Numbers.doubleOf(bad), bad);
    }
}

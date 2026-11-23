package app.unichat.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedMapTest {
    @Test
    void byUseForgetsTheOneUsedLongestAgo() {
        Map<String, Integer> m = BoundedMap.byUse(2);
        m.put("a", 1);
        m.put("b", 2);
        var _ = m.get("a");
        m.put("c", 3);
        assertEquals(List.of("a", "c"), List.copyOf(m.keySet()), "b was used longest ago");
    }

    @Test
    void byAgeForgetsTheOnePutFirstWhateverWasRead() {
        Map<String, Integer> m = BoundedMap.byAge(2);
        m.put("a", 1);
        m.put("b", 2);
        var _ = m.get("a");
        m.put("c", 3);
        assertEquals(List.of("b", "c"), List.copyOf(m.keySet()), "a was put first");
    }

    @Test
    void itNeverHoldsMoreThanItsMax() {
        Map<Integer, Integer> m = BoundedMap.byAge(3);
        for (int i = 0; i < 10; i++) m.put(i, i);
        assertEquals(List.of(7, 8, 9), List.copyOf(m.keySet()));
    }
}

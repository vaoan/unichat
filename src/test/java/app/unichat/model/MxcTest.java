package app.unichat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MxcTest {
    @Test
    void readsTheServerAndTheMediaId() {
        Mxc m = Mxc.parse("mxc://localhost:8448/AbC-12_x");
        assertEquals("localhost:8448", m.server());
        assertEquals("AbC-12_x", m.mediaId());
        assertEquals("mxc://localhost:8448/AbC-12_x", m.toString());
    }

    @Test
    void malformedAddressesAreNone() {
        for (String bad : new String[] {null, "", "mxc://", "mxc://servidor", "mxc://servidor/", "mxc:///id",
                "mxc://servidor/a/b", "mxc://ser vidor/id", "mxc://servidor/id\n", "https://servidor/id", "MXC://s/id"}) {
            assertNull(Mxc.parse(bad), bad);
        }
    }

    @Test
    void cannotBeBuiltFromBadParts() {
        assertThrows(IllegalArgumentException.class, () -> new Mxc("", "id"));
        assertThrows(IllegalArgumentException.class, () -> new Mxc("s", "a/b"));
        assertThrows(IllegalArgumentException.class, () -> new Mxc(null, "id"));
    }
}

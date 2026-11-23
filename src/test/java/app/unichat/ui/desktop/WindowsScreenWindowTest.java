package app.unichat.ui.desktop;

import com.sun.jna.Memory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reading a POWERBROADCAST_SETTING (the GUID, DataLength, then the data), on every OS with JNA's own memory. */
class WindowsScreenWindowTest {
    private static Memory setting(byte[] guid, int length, int data) {
        Memory m = new Memory(24);
        m.write(0, guid, 0, 16);
        m.setInt(16, length);
        m.setInt(20, data);
        return m;
    }

    @Test
    void theDisplayStateIsReadAfterTheGuidAndLength() {
        for (int state = 0; state <= 2; state++) {
            assertEquals(state, WindowsScreenWindow.displayState(setting(WindowsScreenWindow.displayGuid(), 4, state)));
        }
    }

    @Test
    void anotherSettingOrAShortOneIsNoDisplayState() {
        byte[] other = WindowsScreenWindow.displayGuid();
        other[0] ^= 1;
        assertEquals(ScreenMessages.NO_DISPLAY_STATE, WindowsScreenWindow.displayState(setting(other, 4, 0)));
        assertEquals(ScreenMessages.NO_DISPLAY_STATE,
                WindowsScreenWindow.displayState(setting(WindowsScreenWindow.displayGuid(), 2, 0)));
        assertEquals(ScreenMessages.NO_DISPLAY_STATE, WindowsScreenWindow.displayState(null));
    }

    @Test
    void theGuidIsGuidConsoleDisplayStateInMemoryOrder() {
        // 6FE69556-704A-47A0-8F24-C28D936FDA47: Data1, Data2 and Data3 little-endian, Data4 as written
        byte[] expected = {0x56, (byte) 0x95, (byte) 0xE6, 0x6F, 0x4A, 0x70, (byte) 0xA0, 0x47,
            (byte) 0x8F, 0x24, (byte) 0xC2, (byte) 0x8D, (byte) 0x93, 0x6F, (byte) 0xDA, 0x47};
        assertEquals(java.util.HexFormat.of().formatHex(expected),
                java.util.HexFormat.of().formatHex(WindowsScreenWindow.displayGuid()));
    }
    /** A WTSINFOEXW: Level 1, then WTSINFOEX_LEVEL1_W at offset 8 (SessionId, SessionState, SessionFlags…). */
    private static Memory sessionInfo(int level, int flags) {
        Memory m = new Memory(WindowsScreenWindow.SESSION_INFO_SIZE);
        m.clear();
        m.setInt(0, level);
        m.setInt(16, flags);
        return m;
    }

    private static List<String> told(Memory info, int bytes, String osVersion) {
        List<String> told = new ArrayList<>();
        WindowsScreenWindow.lockAtStart((msg, wParam, display) -> told.add(msg + "/" + wParam + "/" + display),
                info, bytes, osVersion);
        return told;
    }

    @Test
    void aSessionLockedAtStartIsToldAsALock() {
        String lock = ScreenMessages.WM_WTSSESSION_CHANGE + "/" + ScreenMessages.WTS_SESSION_LOCK + "/"
                + ScreenMessages.NO_DISPLAY_STATE;
        assertEquals(List.of(lock), told(sessionInfo(1, 0), WindowsScreenWindow.SESSION_INFO_SIZE, "10.0"));
    }

    @Test
    void anUnlockedUnknownOrUnreadableSessionTellsNothing() {
        int size = WindowsScreenWindow.SESSION_INFO_SIZE;
        assertEquals(List.of(), told(sessionInfo(1, 1), size, "10.0"), "WTS_SESSIONSTATE_UNLOCK");
        assertEquals(List.of(), told(sessionInfo(1, -1), size, "10.0"), "WTS_SESSIONSTATE_UNKNOWN");
        assertEquals(List.of(), told(sessionInfo(2, 0), size, "10.0"), "not level 1");
        assertEquals(List.of(), told(sessionInfo(1, 0), 16, "10.0"), "too short to hold SessionFlags");
        assertEquals(List.of(), told(null, 0, "10.0"), "no buffer");
        assertEquals(List.of(), told(sessionInfo(1, 0), size, "6.1"), "Windows 7 / 2008 R2 say it the other way round");
    }
}

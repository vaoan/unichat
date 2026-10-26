package app.unichat.matrix;

import app.unichat.model.Chat;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spaces (bridge folders, Discord servers, Telegram forums) are recognised with their children and parents. */
class SpaceSyncTest {
    private static final String ME = "@me:example.org";
    private SyncProcessor proc;

    @BeforeEach
    void setUp() {
        proc = new SyncProcessor(ME);
    }

    private static JsonNode json(String s) {
        try {
            return MatrixClient.JSON.readTree(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void sync(String roomId, String stateEvents) {
        proc.process(json("{\"rooms\":{\"join\":{\"" + roomId + "\":{\"state\":{\"events\":[" + stateEvents + "]}}}}}"), true);
    }

    private static String ev(String type, String key, String content) {
        return "{\"type\":\"" + type + "\",\"state_key\":\"" + key + "\",\"sender\":\"" + ME + "\",\"content\":" + content + "}";
    }

    private static String me() {
        return ev("m.room.member", ME, "{\"membership\":\"join\"}");
    }

    private Chat chat(String id) {
        return proc.rooms().stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow().chat;
    }

    @Test
    void spaceRoomIsMarkedAndListsChildrenInOrder() {
        sync("!s:x", me() + "," + ev("m.room.create", "", "{\"type\":\"m.space\"}")
                + "," + ev("m.space.child", "!b:x", "{\"via\":[\"x\"],\"order\":\"b\"}")
                + "," + ev("m.space.child", "!a:x", "{\"via\":[\"x\"],\"order\":\"a\"}")
                + "," + ev("m.space.child", "!c:x", "{\"via\":[\"x\"]}"));
        Chat s = chat("!s:x");
        assertTrue(s.space());
        assertFalse(s.group(), "a space is not a group chat");
        assertEquals(List.of("!a:x", "!b:x", "!c:x"), s.spaceChildren());
    }

    @Test
    void emptyChildContentRemovesTheChild() {
        sync("!s:x", me() + "," + ev("m.room.create", "", "{\"type\":\"m.space\"}")
                + "," + ev("m.space.child", "!a:x", "{\"via\":[\"x\"]}") + "," + ev("m.space.child", "!b:x", "{\"via\":[\"x\"]}"));
        sync("!s:x", ev("m.space.child", "!a:x", "{}"));
        assertEquals(List.of("!b:x"), chat("!s:x").spaceChildren());
        sync("!s:x", ev("m.space.child", "!a:x", "{\"via\":[\"x\"]}"));
        assertEquals(List.of("!b:x", "!a:x"), chat("!s:x").spaceChildren(), "re-added");
    }

    @Test
    void personalSpaceIsTheBridgeFolder() {
        sync("!p:x", me() + "," + ev("m.room.create", "", "{\"type\":\"m.space\"}")
                + "," + ev("m.bridge", "fi.mau.telegram://telegram/__personal_space", "{\"protocol\":{\"id\":\"telegram\"},\"channel\":{\"id\":\"__personal_space\"}}"));
        sync("!g:x", me() + "," + ev("m.room.create", "", "{\"type\":\"m.space\"}")
                + "," + ev("m.bridge", "fi.mau.telegram://telegram/123", "{\"protocol\":{\"id\":\"telegram\"},\"channel\":{\"id\":\"123\"}}"));
        assertTrue(chat("!p:x").personalSpace());
        assertFalse(chat("!g:x").personalSpace());
    }

    @Test
    void childDeclaresItsParent() {
        sync("!c:x", me() + "," + ev("m.space.parent", "!s:x", "{\"via\":[\"x\"]}"));
        assertEquals(Set.of("!s:x"), chat("!c:x").spaceParents());
        assertFalse(chat("!c:x").space());
        sync("!c:x", ev("m.space.parent", "!s:x", "{}"));
        assertTrue(chat("!c:x").spaceParents().isEmpty());
    }
}

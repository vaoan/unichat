package app.unichat.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomFeaturesTest {
    private static JsonNode json(String s) throws Exception {
        return new ObjectMapper().readTree(s);
    }

    @Test
    void whatsappLimits() throws Exception {
        RoomFeatures f = RoomFeatures.parse(json("""
            {"id":"wa","edit":2,"edit_max_age":900,"delete":2,"delete_max_age":172800,"reply":2,"reaction":2,"reaction_count":1,
             "file":{"m.sticker":{"mime_types":{"image/webp":2}},"fi.mau.gif":{"mime_types":{"video/mp4":2}},
                     "m.image":{"mime_types":{"image/*":2},"max_size":104857600}}}"""));
        Instant sent = Instant.parse("2026-10-26T10:00:00Z");
        assertTrue(f.canEdit(sent, sent.plusSeconds(14 * 60)));
        assertFalse(f.canEdit(sent, sent.plusSeconds(20 * 60)), "WhatsApp edits close after 15 min");
        assertTrue(f.canDeleteForEveryone(sent, sent.plusSeconds(47 * 3600)));
        assertFalse(f.canDeleteForEveryone(sent, sent.plusSeconds(49 * 3600)));
        assertTrue(f.accepts("m.sticker"));
        assertTrue(f.accepts("fi.mau.gif"));
        assertFalse(f.accepts("org.matrix.msc3245.voice"));
        assertTrue(f.replies());
    }

    @Test
    void googleMessagesHasNoEditAndOnlyDeleteForMe() throws Exception {
        RoomFeatures f = RoomFeatures.parse(json("{\"id\":\"gm\",\"delete_for_me\":true,\"reply\":2}"));
        assertFalse(f.canEdit(Instant.EPOCH, Instant.EPOCH));
        assertFalse(f.canDeleteForEveryone(Instant.EPOCH, Instant.EPOCH));
        assertTrue(f.deleteForMe());
    }

    @Test
    void rejectedOrDroppedLevelsAreNotOffered() throws Exception {
        RoomFeatures f = RoomFeatures.parse(json("{\"id\":\"x\",\"edit\":-1,\"reply\":0,\"file\":{\"m.sticker\":{\"mime_types\":{\"image/webp\":-2}}}}"));
        assertFalse(f.canEdit(Instant.EPOCH, Instant.EPOCH));
        assertFalse(f.replies());
        assertFalse(f.accepts("m.sticker"));
    }

    @Test
    void allowedReactionsMatchIgnoringVariationSelector() throws Exception {
        RoomFeatures f = RoomFeatures.parse(json("{\"id\":\"tg\",\"reaction\":2,\"allowed_reactions\":[\"❤\",\"👍\",\"🔥\"]}"));
        assertEquals(List.of("❤️", "👍"), f.reactionsOffered(List.of("❤️", "👍", "😂")));
    }

    @Test
    void anyReactionWhenTheChatDoesNotRestrictThem() throws Exception {
        RoomFeatures f = RoomFeatures.parse(json("{\"id\":\"s\",\"reaction\":2}"));
        assertEquals(List.of("❤️", "😂"), f.reactionsOffered(List.of("❤️", "😂")));
        assertEquals(List.of(), RoomFeatures.parse(json("{\"id\":\"n\"}")).reactionsOffered(List.of("❤️")));
    }

    @Test
    void unknownOffersEverything() {
        assertTrue(RoomFeatures.UNKNOWN.canEdit(Instant.EPOCH, Instant.now()));
        assertTrue(RoomFeatures.UNKNOWN.canDeleteForEveryone(Instant.EPOCH, Instant.now()));
        assertTrue(RoomFeatures.UNKNOWN.accepts("m.sticker"));
        assertEquals(List.of("❤️"), RoomFeatures.UNKNOWN.reactionsOffered(List.of("❤️")));
    }

    @Test
    void discordTable() {
        RoomFeatures d = RoomFeatures.discord();
        assertTrue(d.canEdit(Instant.EPOCH, Instant.now()));
        assertTrue(d.accepts("fi.mau.gif"));
        assertTrue(d.accepts("m.sticker"));
        assertFalse(d.accepts("org.matrix.msc3245.voice"));
    }

    @Test
    void anyEmojiOnlyWhenTheNetworkDoesNotRestrictReactions() throws Exception {
        assertTrue(RoomFeatures.UNKNOWN.anyReaction());
        assertTrue(RoomFeatures.parse(json("{\"reaction\":2}")).anyReaction());
        assertFalse(RoomFeatures.parse(json("{\"reaction\":2,\"allowed_reactions\":[\"👍\"]}")).anyReaction());
        assertFalse(RoomFeatures.parse(json("{\"reaction\":0}")).anyReaction());
    }

    @Test
    void anEmptyEventMeansUnknownNotNothing() throws Exception {
        assertTrue(RoomFeatures.parse(json("{}")).isUnknown(), "a redacted/empty room_features must not strip every option");
    }

    @Test
    void stickerFormatsFollowTheNetworksMimeTypes() throws Exception {
        RoomFeatures wa = RoomFeatures.parse(json("""
                {"id":"wa","file":{"m.sticker":{"mime_types":{"image/webp":2,"image/png":1,"image/jpeg":1}},
                 "m.image":{"mime_types":{"image/*":1}}}}"""));
        assertTrue(wa.acceptsMime(RoomFeatures.STICKER, "image/webp"));
        assertTrue(wa.acceptsMime(RoomFeatures.STICKER, "image/png"));
        assertFalse(wa.acceptsMime(RoomFeatures.STICKER, "image/gif"), "WhatsApp refuses GIF stickers");
        assertTrue(wa.acceptsMime("m.image", "image/gif"), "wildcards count");
        assertFalse(wa.acceptsMime("m.video", "video/mp4"));
        assertTrue(RoomFeatures.UNKNOWN.acceptsMime(RoomFeatures.STICKER, "image/gif"));
        assertTrue(RoomFeatures.discord().acceptsMime(RoomFeatures.STICKER, "image/gif"), "no list: anything goes");
    }
}

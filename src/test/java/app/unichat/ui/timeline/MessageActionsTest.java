package app.unichat.ui.timeline;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.RoomFeatures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static app.unichat.ui.timeline.MessageActions.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageActionsTest {
    private static final Instant SENT = Instant.parse("2026-10-26T10:00:00Z");

    private static RoomFeatures f(String json) throws Exception {
        return RoomFeatures.parse(new ObjectMapper().readTree(json));
    }

    private static final String WHATSAPP = "{\"id\":\"wa\",\"edit\":2,\"edit_max_age\":900,\"delete\":2,\"delete_max_age\":172800,\"reply\":2}";

    private static Message msg(boolean outgoing, MessageType type, String mime) {
        Message m = new Message("$1", "!c", outgoing ? "@me" : "@ana", "Ana", outgoing, SENT);
        m.setType(type);
        if (type != MessageType.TEXT) {
            m.setMediaUrl("mxc://s/abc");
            m.setMimeType(mime);
        } else {
            m.setBody("hola");
        }
        return m;
    }

    @Test
    void whatsappTextEditableFor15Minutes() throws Exception {
        Set<MessageActions.Action> fresh = MessageActions.of(msg(true, MessageType.TEXT, null), f(WHATSAPP), SENT.plusSeconds(600));
        assertTrue(fresh.containsAll(Set.of(REPLY, COPY, EDIT, DELETE_FOR_EVERYONE)));
        Set<MessageActions.Action> old = MessageActions.of(msg(true, MessageType.TEXT, null), f(WHATSAPP), SENT.plusSeconds(1200));
        assertFalse(old.contains(EDIT));
        assertTrue(old.contains(DELETE_FOR_EVERYONE));
    }

    @Test
    void receivedPhotoCanBeSavedNotEdited() throws Exception {
        Set<MessageActions.Action> a = MessageActions.of(msg(false, MessageType.IMAGE, "image/jpeg"), f(WHATSAPP), SENT);
        assertTrue(a.containsAll(Set.of(SAVE_AS, OPEN_FILE, REPLY)));
        assertFalse(a.contains(EDIT));
        assertFalse(a.contains(DELETE_FOR_EVERYONE), "only your own messages");
        assertFalse(a.contains(COPY));
    }

    @Test
    void onlyVoiceNotesAndAudioCanBeTranscribed() throws Exception {
        assertTrue(MessageActions.of(msg(false, MessageType.AUDIO, "audio/ogg"), f(WHATSAPP), SENT).contains(TRANSCRIBE));
        assertFalse(MessageActions.of(msg(false, MessageType.VIDEO, "video/mp4"), f(WHATSAPP), SENT).contains(TRANSCRIBE));
        assertFalse(MessageActions.of(msg(false, MessageType.TEXT, null), f(WHATSAPP), SENT).contains(TRANSCRIBE));
    }

    @Test
    void stickersAndGifsCanBeFavourites() throws Exception {
        assertTrue(MessageActions.of(msg(false, MessageType.STICKER, "image/webp"), f(WHATSAPP), SENT).contains(FAVORITE));
        assertTrue(MessageActions.of(msg(false, MessageType.IMAGE, "image/gif"), f(WHATSAPP), SENT).contains(FAVORITE));
        assertFalse(MessageActions.of(msg(false, MessageType.IMAGE, "image/jpeg"), f(WHATSAPP), SENT).contains(FAVORITE));
        // Telegram's and WhatsApp's GIFs come as an MP4 the bridge marks as one, Discord's gifv as a WebP so marked.
        Message gifVideo = msg(false, MessageType.VIDEO, "video/mp4");
        gifVideo.setMediaInfo(new ObjectMapper().readTree("{\"fi.mau.gif\":true}"));
        assertTrue(MessageActions.of(gifVideo, f(WHATSAPP), SENT).contains(FAVORITE), "a GIF that came as a video");
        Message gifv = msg(false, MessageType.IMAGE, "image/webp");
        gifv.setMediaInfo(new ObjectMapper().readTree("{\"fi.mau.gif\":true,\"fi.mau.discord.gifv\":true}"));
        assertTrue(MessageActions.of(gifv, f(WHATSAPP), SENT).contains(FAVORITE), "Discord's gifv");
        Message video = msg(false, MessageType.VIDEO, "video/mp4");
        assertFalse(MessageActions.of(video, f(WHATSAPP), SENT).contains(FAVORITE), "a plain video isn't a GIF");
    }

    @Test
    void googleMessagesDeletesOnlyForMe() throws Exception {
        Set<MessageActions.Action> a = MessageActions.of(msg(true, MessageType.TEXT, null), f("{\"id\":\"gm\",\"delete_for_me\":true,\"reply\":2}"), SENT);
        assertTrue(a.contains(DELETE_FOR_ME));
        assertFalse(a.contains(DELETE_FOR_EVERYONE));
        assertFalse(a.contains(EDIT));
    }

    @Test
    void deletedMessagesHaveNoActions() throws Exception {
        Message m = msg(true, MessageType.TEXT, null);
        m.setDeleted(true);
        assertEquals(Set.of(), MessageActions.of(m, f(WHATSAPP), SENT));
    }

    @Test
    void unknownNetworksKeepTodaysActions() {
        Set<MessageActions.Action> a = MessageActions.of(msg(true, MessageType.TEXT, null), RoomFeatures.UNKNOWN, SENT.plusSeconds(99999));
        assertTrue(a.containsAll(Set.of(REPLY, COPY, EDIT, DELETE_FOR_EVERYONE)));
    }

    @Test
    void aMessageWithALinkOrAReelsPageOffersSavingItOthersDont() throws Exception {
        Message link = msg(false, MessageType.TEXT, null);
        link.setBody("mira https://youtu.be/abc123");
        assertTrue(MessageActions.of(link, f(WHATSAPP), SENT).contains(SAVE_LINK));
        assertFalse(MessageActions.of(msg(false, MessageType.TEXT, null), f(WHATSAPP), SENT).contains(SAVE_LINK), "no link");
        Message reel = msg(false, MessageType.IMAGE, "image/jpeg");
        reel.setExternalUrl("https://www.instagram.com/reel/XYZ/");
        assertTrue(MessageActions.of(reel, f(WHATSAPP), SENT).contains(SAVE_LINK), "a reel shown as its picture: its page");
        Message video = msg(false, MessageType.VIDEO, "video/mp4");
        video.setExternalUrl("https://www.instagram.com/reel/XYZ/");
        assertFalse(MessageActions.of(video, f(WHATSAPP), SENT).contains(SAVE_LINK), "a reel that came as its video is its file");
        Message sending = msg(true, MessageType.TEXT, null);
        sending.setBody("mira https://youtu.be/abc123");
        sending.setStatus(app.unichat.model.MessageStatus.SENDING);
        assertFalse(MessageActions.of(sending, f(WHATSAPP), SENT).contains(SAVE_LINK), "only its temporary id yet");
    }

    @Test
    void whatsOnTheServerCanBeForwardedAnywhere() throws Exception {
        assertTrue(MessageActions.of(msg(false, MessageType.TEXT, null), RoomFeatures.UNKNOWN, SENT).contains(FORWARD));
        assertTrue(MessageActions.of(msg(false, MessageType.IMAGE, "image/jpeg"), f(WHATSAPP), SENT).contains(FORWARD));
        Message sending = msg(true, MessageType.TEXT, null);
        sending.setStatus(app.unichat.model.MessageStatus.SENDING);
        assertFalse(MessageActions.of(sending, f(WHATSAPP), SENT).contains(FORWARD), "only what the server has: its copy is what goes");
        Message system = msg(false, MessageType.SYSTEM, null);
        assertFalse(MessageActions.of(system, f(WHATSAPP), SENT).contains(FORWARD), "someone joining isn't a message to pass on");
        Message noFile = msg(false, MessageType.FILE, "application/pdf");
        noFile.setMediaUrl(null);
        assertFalse(MessageActions.of(noFile, f(WHATSAPP), SENT).contains(FORWARD));
        Message local = msg(false, MessageType.FILE, "application/pdf");
        local.setMediaUrl("file:///Users/x/.ssh/id_rsa");
        assertFalse(MessageActions.of(local, f(WHATSAPP), SENT).contains(FORWARD), "a stranger's message chooses its address: only a media one goes on");
    }
}

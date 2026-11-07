package app.unichat.matrix;

import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A bridge's com.beeper.message_send_status says whether a message reached the network: one it couldn't send shows as failed. */
class SendStatusTest {
    private static final String ME = "@me:example.org", BOT = "@telegrambot:example.org", ROOM = "!c:example.org";
    private SyncProcessor proc;

    @BeforeEach
    void setUp() {
        proc = new SyncProcessor(ME);
    }

    private void sync(String... events) {
        try {
            proc.process(MatrixClient.JSON.readTree(("{'rooms':{'join':{'" + ROOM + "':{'timeline':{'events':["
                    + String.join(",", events) + "]}}}}}").replace('\'', '"')), true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String message(String id, String sender) {
        return "{'type':'m.sticker','event_id':'" + id + "','sender':'" + sender + "','origin_server_ts':1,"
                + "'content':{'body':'sticker','url':'mxc://s/a','info':{'mimetype':'image/webp'}}}";
    }

    private static String status(String id, String target, String status) {
        return "{'type':'com.beeper.message_send_status','event_id':'" + id + "','sender':'" + BOT + "','origin_server_ts':2,"
                + "'content':{'network':'telegram','status':'" + status + "','reason':'m_unsupported',"
                + "'m.relates_to':{'rel_type':'m.reference','event_id':'" + target + "'}}}";
    }

    private Message byId(String id) {
        return proc.room(ROOM).byId.get(id);
    }

    @Test
    void aMessageTheBridgeCouldNotSendShowsAsFailed() {
        sync(message("$m", ME), status("$s", "$m", "FAIL_PERMANENT"));
        assertEquals(MessageStatus.FAILED, byId("$m").status());
        assertEquals(1, proc.room(ROOM).timeline.size(), "the status itself is not a message");
    }

    @Test
    void aRetriableFailureThatLaterSucceedsIsSentAgain() {
        sync(message("$m", ME), status("$s1", "$m", "FAIL_RETRIABLE"));
        assertEquals(MessageStatus.FAILED, byId("$m").status());
        sync(status("$s2", "$m", "SUCCESS"));
        assertEquals(MessageStatus.SENT, byId("$m").status());
    }

    @Test
    void successNeverUndoesARead() {
        sync(message("$m", ME));
        byId("$m").setStatus(MessageStatus.READ);
        sync(status("$s", "$m", "SUCCESS"));
        assertEquals(MessageStatus.READ, byId("$m").status());
    }

    @Test
    void aStatusThatArrivesFirstWaitsForItsMessage() {
        sync(status("$s", "$m", "FAIL_PERMANENT"));
        sync(message("$m", ME));
        assertEquals(MessageStatus.FAILED, byId("$m").status());
    }

    @Test
    void onlyOurOwnMessagesTakeAStatus() {
        sync(message("$m", "@telegram_1:example.org"), status("$s", "$m", "FAIL_PERMANENT"));
        assertEquals(MessageStatus.SENT, byId("$m").status());
    }

    @Test
    void pendingChangesNothing() {
        sync(message("$m", ME), status("$s", "$m", "PENDING"));
        assertEquals(MessageStatus.SENT, byId("$m").status());
    }

}

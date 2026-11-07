package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SyncProcessorTest {
    private static final String ME = "@me:example.org";
    private SyncProcessor proc;

    @BeforeEach
    void setUp() {
        proc = new SyncProcessor(ME);
    }

    private static JsonNode json(String s) {
        try {
            return MatrixClient.JSON.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Wraps one joined room into a /sync response. */
    private SyncProcessor.Result sync(String roomId, String roomJson, boolean live) {
        return proc.process(json("{'rooms':{'join':{'" + roomId + "':" + roomJson + "}}}"), live);
    }

    private static String member(String user, String name) {
        return "{'type':'m.room.member','state_key':'" + user + "','sender':'" + user + "','event_id':'$m" + user.hashCode()
                + "','origin_server_ts':1,'content':{'membership':'join','displayname':'" + name + "'}}";
    }

    private static String text(String id, String sender, long ts, String body) {
        return "{'type':'m.room.message','event_id':'" + id + "','sender':'" + sender + "','origin_server_ts':" + ts
                + ",'content':{'msgtype':'m.text','body':'" + body + "'}}";
    }

    private List<Message> messages(String roomId) {
        return proc.room(roomId).timeline;
    }

    @Test
    void detectsNetworkFromBridgeStateAndDmTitle() {
        sync("!wa:example.org", """
                {'summary':{'m.heroes':['@whatsapp_573001:example.org'],'m.joined_member_count':3},
                 'state':{'events':[
                   {'type':'m.bridge','state_key':'x','sender':'@whatsappbot:example.org','event_id':'$b','origin_server_ts':1,
                    'content':{'protocol':{'id':'whatsapp'},'com.beeper.room_type':'dm'}},
                   %s, %s, %s]},
                 'timeline':{'events':[%s],'prev_batch':'p1'},
                 'unread_notifications':{'notification_count':2,'highlight_count':1}}
                """.formatted(member(ME, "Me"), member("@whatsapp_573001:example.org", "Ana"),
                member("@whatsappbot:example.org", "WhatsApp bridge bot"),
                text("$1", "@whatsapp_573001:example.org", 1000, "hola")), false);
        Chat c = proc.room("!wa:example.org").chat;
        assertEquals(Network.WHATSAPP, c.network());
        assertFalse(c.group());
        assertFalse(c.bridgeBot());
        assertEquals("Ana", c.title());
        assertEquals(2, c.unreadCount());
        assertEquals(1, c.mentionCount());
        assertEquals("hola", c.lastPreview());
        assertEquals("p1", proc.room("!wa:example.org").prevBatch);
    }

    @Test
    void networkFallbackFromPuppetUserIds() {
        sync("!tg:example.org", """
                {'state':{'events':[%s,%s,%s,%s]},'timeline':{'events':[]}}
                """.formatted(member(ME, "Me"), member("@telegram_1:example.org", "A"),
                member("@telegram_2:example.org", "B"), member("@telegram_3:example.org", "C")), false);
        Chat c = proc.room("!tg:example.org").chat;
        assertEquals(Network.TELEGRAM, c.network());
        assertTrue(c.group());
        assertEquals("A, B, C", c.title());
    }

    @Test
    void bridgeBotManagementRoom() {
        sync("!bot:example.org", """
                {'summary':{'m.heroes':['@signalbot:example.org'],'m.joined_member_count':2},
                 'state':{'events':[%s,%s]},'timeline':{'events':[]}}
                """.formatted(member(ME, "Me"), member("@signalbot:example.org", "Signal bridge bot")), false);
        Chat c = proc.room("!bot:example.org").chat;
        assertTrue(c.bridgeBot());
        assertEquals(Network.SIGNAL, c.network());
        assertEquals("Signal bridge bot", c.title());
    }

    @Test
    void aCustomEmojiReactionKeepsItsNameAndNeverReadsAsItsAddress() {
        sync("!r:example.org", """
                {'timeline':{'events':[%s,
                  {'type':'m.reaction','event_id':'$2','sender':'@bob:example.org','origin_server_ts':2,
                   'content':{'com.beeper.reaction.shortcode':':orclove:','m.relates_to':{'rel_type':'m.annotation','event_id':'$1','key':'mxc://s/orc'}}},
                  {'type':'m.reaction','event_id':'$3','sender':'@ann:example.org','origin_server_ts':3,
                   'content':{'m.relates_to':{'rel_type':'m.annotation','event_id':'$1','key':'mxc://s/sinnombre'}}},
                  {'type':'m.reaction','event_id':'$4','sender':'@ann:example.org','origin_server_ts':4,
                   'content':{'m.relates_to':{'rel_type':'m.annotation','event_id':'$1','key':'👍'}}}]}}
                """.formatted(text("$1", ME, 1, "hola")), false);
        Message m = messages("!r:example.org").get(0);
        assertEquals(":orclove:", m.reactionLabel("mxc://s/orc"));
        assertEquals("emoji personalizado", m.reactionLabel("mxc://s/sinnombre"));
        assertEquals("👍", m.reactionLabel("👍"));
    }

    @Test
    void stripsReplyFallback() {
        sync("!r:example.org", """
                {'timeline':{'events':[%s,
                  {'type':'m.room.message','event_id':'$2','sender':'@bob:example.org','origin_server_ts':2,
                   'content':{'msgtype':'m.text','body':'> <@me:example.org> original\\n> line two\\n\\nmy answer',
                   'm.relates_to':{'m.in_reply_to':{'event_id':'$1'}}}}]}}
                """.formatted(text("$1", ME, 1, "original")), false);
        Message reply = messages("!r:example.org").get(1);
        assertEquals("$1", reply.replyToId());
        assertEquals("my answer", reply.body());
        assertTrue(messages("!r:example.org").get(0).outgoing());
        assertEquals("plain", SyncEvents.stripReplyFallback("plain"));
    }

    @Test
    void appliesEditsIncludingOutOfOrder() {
        String room = "!e:example.org";
        String edit = """
                {'type':'m.room.message','event_id':'$e%d','sender':'@bob:example.org','origin_server_ts':%d,
                 'content':{'msgtype':'m.text','body':'* %s','m.new_content':{'msgtype':'m.text','body':'%s'},
                 'm.relates_to':{'rel_type':'m.replace','event_id':'$1'}}}""";
        sync(room, "{'timeline':{'events':[%s,%s,%s]}}".formatted(text("$1", "@bob:example.org", 1, "tpyo"),
                edit.formatted(2, 3, "second", "second"), edit.formatted(1, 2, "first", "first")), false);
        List<Message> ms = messages(room);
        assertEquals(1, ms.size(), "edit events are not shown as messages");
        assertEquals("second", ms.get(0).body());
        assertTrue(ms.get(0).edited());

        // Edit by someone else is ignored
        sync(room, "{'timeline':{'events':[%s]}}".formatted(edit.formatted(3, 9, "x", "x").replace("@bob", "@eve")), true);
        assertEquals("second", ms.get(0).body());
    }

    @Test
    void reactionsToggleAndRedaction() {
        String room = "!x:example.org";
        String react = """
                {'type':'m.reaction','event_id':'%s','sender':'%s','origin_server_ts':5,
                 'content':{'m.relates_to':{'rel_type':'m.annotation','event_id':'$1','key':'👍'}}}""";
        sync(room, "{'timeline':{'events':[%s,%s,%s]}}".formatted(text("$1", "@bob:example.org", 1, "hi"),
                react.formatted("$r1", ME), react.formatted("$r2", "@bob:example.org")), false);
        Message m = messages(room).get(0);
        assertEquals(Set.of(ME, "@bob:example.org"), m.reactions().get("👍"));
        assertEquals("$r1", proc.ownReactionEvent(room, "$1", "👍"));

        sync(room, """
                {'timeline':{'events':[{'type':'m.room.redaction','event_id':'$red','sender':'@me:example.org',
                 'origin_server_ts':6,'redacts':'$r1','content':{'redacts':'$r1'}}]}}""", true);
        assertEquals(Set.of("@bob:example.org"), m.reactions().get("👍"));
        assertNull(proc.ownReactionEvent(room, "$1", "👍"));
        assertEquals(1, messages(room).size());
    }

    @Test
    void reactionBeforeTargetIsAppliedWhenHistoryLoads() {
        String room = "!h:example.org";
        sync(room, """
                {'timeline':{'prev_batch':'t0','events':[{'type':'m.reaction','event_id':'$r','sender':'@bob:example.org',
                 'origin_server_ts':5,'content':{'m.relates_to':{'rel_type':'m.annotation','event_id':'$old','key':'❤️'}}}]}}""", false);
        SyncProcessor.Result r = proc.processHistory(room, json("{'chunk':[%s],'end':'t1'}"
                .formatted(text("$old", "@bob:example.org", 1, "old message"))));
        assertTrue(r.hasMore);
        assertEquals("$old", messages(room).get(0).id());
        assertEquals(Set.of("@bob:example.org"), messages(room).get(0).reactions().get("❤️"));
        assertEquals("t1", proc.room(room).prevBatch);

        r = proc.processHistory(room, json("{'chunk':[]}"));
        assertFalse(r.hasMore);
    }

    @Test
    void redactionMarksDeleted() {
        String room = "!d:example.org";
        sync(room, "{'timeline':{'events':[%s,%s]}}".formatted(text("$1", "@bob:example.org", 1, "secret"),
                "{'type':'m.room.redaction','event_id':'$x','sender':'@bob:example.org','origin_server_ts':2,'content':{'redacts':'$1'}}"),
                false);
        Message m = messages(room).get(0);
        assertTrue(m.deleted());
        assertEquals("", m.body());
        assertEquals("Mensaje eliminado", proc.room(room).chat.lastPreview());
    }

    @Test
    void tagsMarkedUnreadReminderAndMute() {
        String room = "!t:example.org";
        sync(room, """
                {'timeline':{'events':[]},'account_data':{'events':[
                  {'type':'m.tag','content':{'tags':{'m.favourite':{'order':0.1},'u.archived':{},'u.pinned':{}}}},
                  {'type':'com.famedly.marked_unread','content':{'unread':true}},
                  {'type':'app.unichat.reminder','content':{'ts':1700000000000}}]}}""", false);
        Chat c = proc.room(room).chat;
        assertTrue(c.favorite());
        assertFalse(c.lowPriority());
        assertTrue(c.archived());
        assertTrue(c.pinned());
        assertTrue(c.markedUnread());
        assertTrue(c.isUnread());
        assertEquals(Instant.ofEpochMilli(1700000000000L), c.reminderAt());

        sync(room, "{'account_data':{'events':[{'type':'m.marked_unread','content':{'unread':false}},"
                + "{'type':'com.famedly.marked_unread','content':{'unread':true}},{'type':'m.tag','content':{'tags':{'m.lowpriority':{}}}},"
                + "{'type':'app.unichat.reminder','content':{}}]}}", true);
        assertFalse(c.markedUnread(), "stable m.marked_unread wins over the unstable type");
        assertFalse(c.favorite());
        assertTrue(c.lowPriority());
        assertEquals(Instant.ofEpochMilli(1700000000000L), c.reminderAt(), "a reminder in the file isn't cleared by the server");

        proc.process(json("""
                {'account_data':{'events':[{'type':'m.push_rules','content':{'global':{'room':[
                  {'rule_id':'!t:example.org','enabled':true,'actions':[]}]}}}]}}"""), true);
        assertTrue(c.muted());
    }

    @Test
    void mediaAndEncryptedEvents() {
        String room = "!m:example.org";
        sync(room, """
                {'timeline':{'events':[
                 {'type':'m.room.message','event_id':'$i','sender':'@bob:example.org','origin_server_ts':1,
                  'content':{'msgtype':'m.image','body':'foto.jpg','url':'mxc://example.org/abc','info':{'w':640,'h':480,'size':1234}}},
                 {'type':'m.room.message','event_id':'$f','sender':'@bob:example.org','origin_server_ts':2,
                  'content':{'msgtype':'m.file','body':'mira esto','filename':'doc.pdf','url':'mxc://example.org/def'}},
                 {'type':'m.room.encrypted','event_id':'$c','sender':'@bob:example.org','origin_server_ts':3,'content':{'algorithm':'m.megolm.v1.aes-sha2'}}]}}
                """, false);
        List<Message> ms = messages(room);
        Message img = ms.get(0);
        assertEquals(MessageType.IMAGE, img.type());
        assertEquals("mxc://example.org/abc", img.mediaUrl());
        assertEquals("foto.jpg", img.fileName());
        assertEquals(640, img.width());
        assertEquals(480, img.height());
        assertEquals(1234, img.fileSize());
        Message file = ms.get(1);
        assertEquals("doc.pdf", file.fileName());
        assertEquals("mira esto", file.body());
        assertEquals(MessageType.SYSTEM, ms.get(2).type());
        assertEquals(SyncProcessor.encryptedText(), ms.get(2).body());
    }

    @Test
    void systemMessagesInGroupsOnly() {
        String room = "!g:example.org";
        sync(room, """
                {'state':{'events':[%s,%s,%s]},'timeline':{'events':[
                 {'type':'m.room.name','state_key':'','event_id':'$n','sender':'@bob:example.org','origin_server_ts':5,'content':{'name':'Familia'}},
                 {'type':'m.room.member','state_key':'@carl:example.org','event_id':'$j','sender':'@carl:example.org','origin_server_ts':6,
                  'content':{'membership':'join','displayname':'Carl'}}]}}
                """.formatted(member(ME, "Me"), member("@bob:example.org", "Bob"), member("@dan:example.org", "Dan")), false);
        List<Message> ms = messages(room);
        assertEquals("Bob cambió el nombre del grupo a Familia", ms.get(0).body());
        assertEquals("Carl se unió", ms.get(1).body());
        assertEquals(MessageType.SYSTEM, ms.get(1).type());
        assertEquals("Familia", proc.room(room).chat.title());
    }

    @Test
    void localEchoReplacedBySyncAndReadReceipts() {
        String room = "!l:example.org";
        sync(room, "{'timeline':{'events':[]}}", false);
        Message echo = proc.addLocalEcho(room, "t1", m -> m.setBody("hey"));
        assertEquals(MessageStatus.SENDING, echo.status());
        SyncProcessor.Result r = sync(room, """
                {'timeline':{'events':[{'type':'m.room.message','event_id':'$real','sender':'@me:example.org','origin_server_ts':9,
                  'unsigned':{'transaction_id':'t1'},'content':{'msgtype':'m.text','body':'hey'}}]}}""", true);
        assertTrue(r.newMessages.isEmpty(), "outgoing messages never notify");
        assertEquals(1, messages(room).size());
        Message real = messages(room).get(0);
        assertEquals("$real", real.id());
        assertEquals(MessageStatus.SENT, real.status());
        proc.confirmLocalEcho(room, "t1", "$real");
        assertEquals(1, messages(room).size());

        sync(room, """
                {'ephemeral':{'events':[{'type':'m.receipt','content':{'$real':{'m.read':{'@bob:example.org':{'ts':10}}}}},
                  {'type':'m.typing','content':{'user_ids':['@bob:example.org','@me:example.org']}}]}}""", true);
        assertEquals(MessageStatus.READ, real.status());
        assertEquals(Set.of("bob"), proc.room(room).chat.typing());
    }

    @Test
    void confirmedEchoKeepsWhatOnlyTheEchoKnows() throws Exception {
        String room = "!e:example.org";
        sync(room, "{'timeline':{'events':[]}}", false);
        JsonNode info = json("{'duration':4200}");
        proc.addLocalEcho(room, "t2", m -> {
            m.setType(MessageType.AUDIO);
            m.setMediaUrl("mxc://s/note");
            m.setMimeType("audio/ogg; codecs=opus");
            m.setMediaInfo(info);
            m.setExternalUrl("https://www.instagram.com/reel/1/");
            m.setReactionName("mxc://s/e", ":orclove:");
        });
        proc.confirmLocalEcho(room, "t2", "$sent");
        Message real = messages(room).get(0);
        assertEquals("$sent", real.id());
        assertEquals(MessageStatus.SENT, real.status());
        assertEquals("audio/ogg; codecs=opus", real.mimeType());
        assertEquals(4200, real.durationMs(), "a sent voice note keeps its duration");
        assertEquals("https://www.instagram.com/reel/1/", real.externalUrl());
        assertEquals(":orclove:", real.reactionLabel("mxc://s/e"));
    }

    @Test
    void liveIncomingMessagesAreReported() {
        String room = "!n:example.org";
        SyncProcessor.Result r = sync(room, "{'timeline':{'events':[%s]}}".formatted(text("$1", "@bob:example.org", 1, "a")), true);
        assertEquals(1, r.newMessages.size());
        r = sync(room, "{'timeline':{'events':[%s]}}".formatted(text("$1", "@bob:example.org", 1, "a")), true);
        assertTrue(r.newMessages.isEmpty(), "duplicates are ignored");
    }

    @Test
    void invitesAndLeaves() {
        SyncProcessor.Result r = proc.process(json("""
                {'rooms':{'invite':{'!i:example.org':{'invite_state':{'events':[
                  {'type':'m.room.name','state_key':'','sender':'@bob:example.org','content':{'name':'Equipo'}},
                  {'type':'m.room.member','state_key':'@me:example.org','sender':'@bob:example.org','content':{'membership':'invite'}}]}}}}}"""),
                true);
        assertEquals(List.of("!i:example.org"), r.invites);
        assertEquals("Equipo", proc.room("!i:example.org").chat.title());
        proc.process(json("{'rooms':{'leave':{'!i:example.org':{}}}}"), true);
        assertNull(proc.room("!i:example.org"));
    }

    @Test
    void aChatSaysItIsAnInvitationUntilJoined() {
        proc.process(json("{'rooms':{'invite':{'!i:example.org':{'invite_state':{'events':[]}}}}}"), true);
        assertTrue(proc.room("!i:example.org").chat.invite());
        sync("!i:example.org", "{'timeline':{'events':[" + text("$1", "@bob:example.org", 1, "hola") + "]}}", true);
        assertFalse(proc.room("!i:example.org").chat.invite());
    }

    @Test
    void directAccountDataMakesDm() {
        proc.process(json("{'account_data':{'events':[{'type':'m.direct','content':{'@bob:example.org':['!dm:example.org']}}]}}"), false);
        sync("!dm:example.org", "{'state':{'events':[%s,%s,%s,%s]}}".formatted(member(ME, "Me"),
                member("@bob:example.org", "Bob"), member("@x:example.org", "X"), member("@y:example.org", "Y")), false);
        Chat c = proc.room("!dm:example.org").chat;
        assertFalse(c.group());
        assertEquals("Bob", c.title());
    }

    @Test
    void namedNativeRoomIsGroupButNamedBridgedDmIsNot() {
        String name = "{'type':'m.room.name','state_key':'','sender':'@bob:example.org','content':{'name':'Equipo'}}";
        sync("!named:example.org", "{'state':{'events':[%s,%s,%s]}}".formatted(member(ME, "Me"),
                member("@bob:example.org", "Bob"), name), false);
        assertTrue(proc.room("!named:example.org").chat.group());

        sync("!portal:example.org", "{'state':{'events':[%s,%s,%s]}}".formatted(member(ME, "Me"),
                member("@whatsapp_573001:example.org", "Ana"), name), false);
        assertFalse(proc.room("!portal:example.org").chat.group());
    }

    @Test
    void mutedFromOverrideRule() {
        Set<String> muted = SyncEvents.mutedRoomsFrom(json("""
                {'global':{'override':[{'rule_id':'!a:x','enabled':true,'actions':['dont_notify']},
                  {'rule_id':'.m.rule.master','enabled':false,'actions':[]}],
                 'room':[{'rule_id':'!b:x','enabled':true,'actions':['notify']}]}}"""));
        assertEquals(Set.of("!a:x"), muted);
    }

    @Test
    void myOwnReceiptsTellUpToWhereIRead() {
        String room = "!p:example.org";
        // A bridge delivers a message together with my receipt from the phone (read there already).
        SyncProcessor.Result r = sync(room, """
                {'timeline':{'events':[%s]},
                 'ephemeral':{'events':[{'type':'m.receipt','content':{'$1':{'m.read':{'@me:example.org':{'ts':900}}}}}]}}"""
                .formatted(text("$1", "@bob:example.org", 500, "a")), true);
        assertEquals(Instant.ofEpochMilli(500), r.readByMe.get(room), "the read message's own time");
        r = sync(room, """
                {'ephemeral':{'events':[{'type':'m.receipt','content':{'$unknown':{'m.read.private':{'@me:example.org':{'ts':700}}}}}]}}""",
                true);
        assertEquals(Instant.ofEpochMilli(700), r.readByMe.get(room), "an event not loaded: when it was read");
        r = sync(room, """
                {'ephemeral':{'events':[{'type':'m.receipt','content':{'$1':{'m.read':{'@bob:example.org':{'ts':950}}}}}]}}""", true);
        assertTrue(r.readByMe.isEmpty(), "others' receipts don't count");
    }

    @Test
    void htmlComesAlongAndAnEmbedLeavesTheBodyEvenWhenItArrivesWithALaterEdit() {
        String room = "!embed:example.org";
        sync(room, "{'timeline':{'events':[%s]}}".formatted(text("$1", "@bob:example.org", 1, "https://a.example")), false);
        Message m = messages(room).get(0);
        assertNull(m.html());
        String html = "<blockquote class=\\\"discord-embed\\\"><p>Hola</p></blockquote>";
        String nc = "{'msgtype':'m.text','body':'https://a.example\\n> Hola','format':'org.matrix.custom.html','formatted_body':'" + html + "'}";
        sync(room, "{'timeline':{'events':[{'type':'m.room.message','event_id':'$2','sender':'@bob:example.org','origin_server_ts':2,"
                + "'content':{'msgtype':'m.text','body':'* x','m.new_content':" + nc + ",'m.relates_to':{'rel_type':'m.replace','event_id':'$1'}}}]}}", true);
        assertEquals("https://a.example", m.body());
        assertTrue(m.html().contains("discord-embed"));
        assertEquals("Hola", m.htmlText());
        sync(room, "{'timeline':{'events':[{'type':'m.room.message','event_id':'$3','sender':'@bob:example.org','origin_server_ts':3,"
                + "'content':{'msgtype':'m.text','body':'* y','m.new_content':{'msgtype':'m.text','body':'y'},'m.relates_to':{'rel_type':'m.replace','event_id':'$1'}}}]}}", true);
        assertNull(m.html(), "an edit that drops it");
        assertNull(m.htmlText());
    }
}

package app.unichat.service;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import app.unichat.support.I18n;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The chats and people of demo mode. */
final class DemoChats {
    private static final String ME = MockChatService.ME;
    private static final String WA_PERSONAL = MockChatService.WA_PERSONAL;
    private static final String WA_WORK = MockChatService.WA_WORK;

    private DemoChats() {}

    /**
     * Gives a demo chat its key and applies what the file has chosen. Only fields the file has override the demo's own
     * values ({@link PreferencesFile#preview} falls back to them), so an empty file changes nothing.
     */
    static void adopt(PreferencesFile prefs, Chat c) {
        c.setPrefKey(ChatKeys.of(null, null, c.id()));
        var demo = new PreferencesFile.ServerValues(c.archived(), c.muted(), c.favorite(), c.lowPriority(), c.pinned(),
                c.reminderAt(), true);
        var e = prefs.preview(c.prefKey(), demo);
        c.setArchived(e.archived());
        c.setMuted(e.muted());
        c.setFavorite(e.favorite());
        c.setLowPriority(e.lowPriority());
        c.setPinned(e.pinned());
        c.setReminderAt(e.reminder());
    }

    static void seed(MockChatService svc) {
        Map<String, Account> accounts = svc.accounts;
        Instant now = Instant.now();
        for (Network n : List.of(Network.WHATSAPP, Network.TELEGRAM, Network.INSTAGRAM, Network.SIGNAL,
                Network.DISCORD, Network.SLACK, Network.MESSENGER, Network.GOOGLE_MESSAGES)) {
            if (n != Network.WHATSAPP) accounts.put(n.protocolId(), new Account(n, "demo_" + n.protocolId(), AccountStatus.CONNECTED, null));
        }

        accounts.put(WA_PERSONAL, new Account(WA_PERSONAL, Network.WHATSAPP, "+57 300 111 2233", AccountStatus.CONNECTED, null));
        accounts.put(WA_WORK, new Account(WA_WORK, Network.WHATSAPP, "+57 310 555 0000", AccountStatus.CONNECTED, null));

        whatsAppChats(svc);
        otherChats(svc);
        discordServer(svc);
    }

    private static void whatsAppChats(MockChatService svc) {
        ChatBuilder.dm(svc, "andrea", Network.WHATSAPP, I18n.t("demo.title.andrea"))
                .in(-1500, I18n.t("demo.andrea.1"))
                .out(-1400, I18n.t("demo.andrea.2"))
                .in(-240, I18n.t("demo.andrea.3"))
                .account(WA_WORK).unread(1).done();

        ChatBuilder.dm(svc, "mama", Network.WHATSAPP, I18n.t("demo.title.mom"))
                .in(-2900, I18n.t("demo.mom.1"))
                .out(-2850, I18n.t("demo.mom.2"))
                .in(-2800, I18n.t("demo.mom.3"))
                .out(-2700, I18n.t("demo.mom.4"))
                .in(-180, I18n.t("demo.mom.5"))
                .in(-120, I18n.t("demo.mom.6"))
                .unread(2).favorite().done();

        ChatBuilder.group(svc, "familia", Network.WHATSAPP, I18n.t("demo.title.family"), I18n.t("demo.name.dad"), "Laura", I18n.t("demo.name.uncleJorge"))
                .in(-7200, I18n.t("demo.name.dad"), I18n.t("demo.family.1"))
                .in(-7100, "Laura", I18n.t("demo.family.2"))
                .photo(-7000, I18n.t("demo.name.uncleJorge"), I18n.t("demo.family.photo"))
                .in(-6900, I18n.t("demo.name.uncleJorge"), I18n.t("demo.family.3"))
                .out(-6800, I18n.t("demo.family.4"))
                .react(-7000, "❤️", I18n.t("demo.name.dad"), "Laura")
                .voice(-6700, I18n.t("demo.name.dad"))
                .in(-600, "Laura", I18n.t("demo.family.5"))
                .unread(1).done();
    }

    private static void otherChats(MockChatService svc) {
        ChatBuilder.dm(svc, "andres", Network.TELEGRAM, "Andrés Mejía")
                .out(-90000, I18n.t("demo.andres.1"))
                .in(-89000, I18n.t("demo.andres.2"))
                .in(-88900, I18n.t("demo.andres.3"))
                .out(-3600, I18n.t("demo.andres.4"))
                .reply(-3500, I18n.t("demo.andres.5"), -3600)
                .favorite().done();

        ChatBuilder.group(svc, "devs", Network.SLACK, I18n.t("demo.title.dev"), "Camila Rojas", "Santiago", I18n.t("demo.name.ciBot"))
                .in(-5400, I18n.t("demo.name.ciBot"), I18n.t("demo.dev.1"))
                .in(-5000, "Camila Rojas", I18n.t("demo.dev.2"))
                .in(-4900, "Santiago", I18n.t("demo.dev.3"))
                .out(-4800, I18n.t("demo.dev.4"))
                .in(-300, "Camila Rojas", I18n.t("demo.dev.5"))
                .unread(3).mention().done();

        ChatBuilder.dm(svc, "valen", Network.INSTAGRAM, "valentina.gomez")
                .in(-14000, I18n.t("demo.valen.1"))
                .out(-13900, I18n.t("demo.valen.2"))
                .photo(-13800, "valentina.gomez", I18n.t("demo.valen.photo"))
                .in(-13700, I18n.t("demo.valen.3"))
                .react(-13800, "🔥", I18n.t("demo.me"))
                .done();

        ChatBuilder.dm(svc, "sofia", Network.SIGNAL, "Sofía")
                .in(-40000, I18n.t("demo.sofia.1"))
                .out(-39900, I18n.t("demo.sofia.2"))
                .in(-39800, I18n.t("demo.sofia.3"))
                .done();

        ChatBuilder.group(svc, "gaming", Network.DISCORD, "Gaming • #general", "xNico", "Mafe", "Pipe")
                .in(-20000, "xNico", I18n.t("demo.gaming.1"))
                .in(-19900, "Mafe", I18n.t("demo.gaming.2"))
                .in(-19800, "Pipe", I18n.t("demo.gaming.3"))
                .out(-19700, I18n.t("demo.gaming.4"))
                .in(-900, "xNico", I18n.t("demo.gaming.5"))
                .unread(4).muted().done();

        // A Discord server (a Matrix space) with two joined channels and one not joined yet.

        ChatBuilder.dm(svc, "banco", Network.GOOGLE_MESSAGES, I18n.t("demo.title.bank"))
                .in(-30000, I18n.t("demo.bank.1"))
                .lowPriority().done();

        ChatBuilder.dm(svc, "carlos", Network.MESSENGER, "Carlos Pérez")
                .in(-250000, I18n.t("demo.carlos.1"))
                .out(-249000, I18n.t("demo.carlos.2"))
                .archived().done();

        ChatBuilder.dm(svc, "notas", Network.MATRIX, I18n.t("demo.title.notes"))
                .out(-500000, I18n.t("demo.notes.1"))
                .out(-400000, I18n.t("demo.notes.2"))
                .pinned().done();
    }

    private static void discordServer(MockChatService svc) {
        Chat server = new Chat("!servidor:demo");
        server.setNetwork(Network.DISCORD);
        server.setTitle(I18n.t("demo.title.server"));
        server.setSpace(true);
        server.setSpaceChildren(List.of("!srv-general:demo", "!srv-memes:demo", MockChatService.UNJOINED_CHANNEL_ID));
        svc.adopt(server);
        svc.chats.put(server.id(), server);
        ChatBuilder.group(svc, "srv-general", Network.DISCORD, "#general", "xNico", "Mafe", "Pipe")
                .in(-5000, "xNico", I18n.t("demo.srv.1"))
                .html(-4000, "Pipe", I18n.t("demo.srv.html"))
                .html(-3000, "Mafe", "https://fixupx.com/mafe/status/1234567890123456789012345678901234567890\n"
                        + "<blockquote class=\"discord-embed\" background-color=\"#6364FF\"><p class=\"discord-embed-author\">"
                        + "<img data-mx-emoticon height=\"24\" src=\"demo://avatar\" alt=\"\">&nbsp;<a href=\"https://x.com/mafe/status/1\">Mafe (@mafe)</a></p>"
                        + "<p class=\"discord-embed-description\"><p>" + I18n.t("demo.srv.embed") + "<br><a href=\"https://x.com/hashtag/torneo\">#" + I18n.t("demo.srv.embedTag") + "</a></p></p>"
                        + "<p class=\"discord-embed-image\"><img src=\"demo://torneo\" alt=\"\"></p><p class=\"discord-embed-footer\"><sub>FixupX</sub></p></blockquote>")
                .unread(2).done();
        ChatBuilder.group(svc, "srv-memes", Network.DISCORD, "#memes", "Mafe")
                .in(-7000, "Mafe", "😂")
                .done();
    }

    /** Small fluent helper for seed chats; times are seconds relative to now. */
    static final class ChatBuilder {
        private final MockChatService svc;
        private final Chat chat;
        private final List<Message> list = new ArrayList<>();
        private final Map<String, Member> people = new java.util.HashMap<>();
        private final Instant now = Instant.now();

        private ChatBuilder(MockChatService svc, String key, Network network, String title, boolean group, String... names) {
            this.svc = svc;
            this.chat = new Chat("!" + key + ":demo");
            chat.setNetwork(network);
            chat.setTitle(title);
            chat.setGroup(group);
            List<Member> members = new ArrayList<>(List.of(new Member(ME, I18n.t("demo.me"), null)));
            for (String n : names) {
                Member m = new Member("@" + network.protocolId() + "_" + n.toLowerCase().replaceAll("[^a-z0-9]", "") + ":demo.unichat", n, null);
                people.put(n, m);
                members.add(m);
            }
            chat.setMembers(members);
        }

        static ChatBuilder dm(MockChatService svc, String key, Network network, String name) {
            return new ChatBuilder(svc, key, network, name, false, name);
        }

        static ChatBuilder group(MockChatService svc, String key, Network network, String title, String... names) {
            return new ChatBuilder(svc, key, network, title, true, names);
        }

        private Member single() { return people.values().iterator().next(); }

        ChatBuilder in(long secs, String text) { return in(secs, single().name(), text); }

        ChatBuilder in(long secs, String who, String text) {
            Member m = who.equals(I18n.t("demo.me")) ? new Member(ME, I18n.t("demo.me"), null) : people.get(who);
            Message msg = new Message(UUID.randomUUID().toString(), chat.id(), m.id(), m.name(), m.id().equals(ME), now.plusSeconds(secs));
            msg.setBody(text);
            list.add(msg);
            return this;
        }

        ChatBuilder out(long secs, String text) {
            Message msg = new Message(UUID.randomUUID().toString(), chat.id(), ME, I18n.t("demo.me"), true, now.plusSeconds(secs));
            msg.setBody(text);
            msg.setStatus(MessageStatus.READ);
            list.add(msg);
            return this;
        }

        ChatBuilder photo(long secs, String who, String name) {
            in(secs, who, name.replace('-', ' '));
            Message msg = list.get(list.size() - 1);
            msg.setType(MessageType.IMAGE);
            msg.setMediaUrl("demo://" + name);
            msg.setFileName(name + ".png");
            msg.setSize(640, 420);
            return this;
        }

        ChatBuilder html(long secs, String who, String html) {
            in(secs, who, html.replaceAll("<[^>]+>", " ").strip());
            list.get(list.size() - 1).setHtml(html.replace("demo://", "mxc://demo/"));
            return this;
        }

        ChatBuilder voice(long secs, String who) {
            in(secs, who, "");
            Message msg = list.get(list.size() - 1);
            msg.setType(MessageType.AUDIO);
            msg.setMediaUrl("demo://nota-de-voz.ogg");
            msg.setFileName(I18n.t("demo.voiceNoteFile"));
            msg.setMimeType("audio/ogg");
            msg.setFileSize(18_400);
            return this;
        }

        ChatBuilder reply(long secs, String text, long toSecs) {
            in(secs, text);
            list.get(list.size() - 1).setReplyToId(at(toSecs).id());
            return this;
        }

        ChatBuilder react(long secs, String emoji, String... who) {
            Message target = at(secs);
            for (String w : who) target.toggleReaction(emoji, w.equals(I18n.t("demo.me")) ? ME : people.get(w).id());
            return this;
        }

        private Message at(long secs) {
            Instant t = now.plusSeconds(secs);
            return list.stream().filter(m -> m.timestamp().equals(t)).findFirst().orElseThrow();
        }

        ChatBuilder unread(int n) { chat.setUnreadCount(n); return this; }
        ChatBuilder mention() { chat.setMentionCount(1); return this; }
        ChatBuilder favorite() { chat.setFavorite(true); return this; }
        ChatBuilder lowPriority() { chat.setLowPriority(true); return this; }
        ChatBuilder archived() { chat.setArchived(true); return this; }
        ChatBuilder muted() { chat.setMuted(true); return this; }
        ChatBuilder pinned() { chat.setPinned(true); return this; }
        ChatBuilder account(String id) { chat.setAccountId(id); return this; }

        void done() {
            if (chat.accountId() == null) {
                chat.setAccountId(chat.network() == Network.WHATSAPP ? WA_PERSONAL : chat.network().protocolId());
            }
            list.sort(Comparator.comparing(Message::timestamp));
            if (!list.isEmpty()) chat.applyLastMessage(list.get(list.size() - 1));
            svc.adopt(chat);
            svc.chats.put(chat.id(), chat);
            svc.messages.put(chat.id(), list);
        }
    }
}

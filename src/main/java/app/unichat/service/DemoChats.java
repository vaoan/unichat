package app.unichat.service;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
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
        ChatBuilder.dm(svc, "andrea", Network.WHATSAPP, "Andrea (cliente)")
                .in(-1500, "Buenas tardes, ¿me confirma la cotización?")
                .out(-1400, "Hola Andrea, se la envío hoy antes de las 5")
                .in(-240, "Perfecto, quedo atenta 🙌")
                .account(WA_WORK).unread(1).done();

        ChatBuilder.dm(svc, "mama", Network.WHATSAPP, "Mamá")
                .in(-2900, "Hola mijo, ¿cómo estás?")
                .out(-2850, "Bien ma, trabajando")
                .in(-2800, "¿Vienes el domingo a almorzar?")
                .out(-2700, "Sí claro, llevo el postre 🍰")
                .in(-180, "Perfecto, te espero a la 1")
                .in(-120, "No olvides traer el cargador que dejaste")
                .unread(2).favorite().done();

        ChatBuilder.group(svc, "familia", Network.WHATSAPP, "Familia 🏡", "Papá", "Laura", "Tío Jorge")
                .in(-7200, "Papá", "Buenos días familia ☀️")
                .in(-7100, "Laura", "Buenos días!!")
                .photo(-7000, "Tío Jorge", "Paseo-al-lago")
                .in(-6900, "Tío Jorge", "Así quedó el paseo del sábado")
                .out(-6800, "Qué bonito, ¡la próxima voy!")
                .react(-7000, "❤️", "Papá", "Laura")
                .voice(-6700, "Papá")
                .in(-600, "Laura", "¿Quién trae qué el domingo?")
                .unread(1).done();
    }

    private static void otherChats(MockChatService svc) {
        ChatBuilder.dm(svc, "andres", Network.TELEGRAM, "Andrés Mejía")
                .out(-90000, "¿Pudiste revisar el PR?")
                .in(-89000, "Sí, dejé unos comentarios")
                .in(-88900, "Lo demás está perfecto 👍")
                .out(-3600, "Ya corregí lo que dijiste")
                .reply(-3500, "Genial, lo apruebo ahora", -3600)
                .favorite().done();

        ChatBuilder.group(svc, "devs", Network.SLACK, "#desarrollo", "Camila Rojas", "Santiago", "Bot de CI")
                .in(-5400, "Bot de CI", "✅ Build #482 pasó en main")
                .in(-5000, "Camila Rojas", "Hoy hacemos deploy a las 4pm")
                .in(-4900, "Santiago", "Ok, congelo merges desde las 3")
                .out(-4800, "Listo, yo reviso los logs después")
                .in(-300, "Camila Rojas", "@Yo ¿puedes revisar el ticket 1234?")
                .unread(3).mention().done();

        ChatBuilder.dm(svc, "valen", Network.INSTAGRAM, "valentina.gomez")
                .in(-14000, "jajaja viste la historia que subí?")
                .out(-13900, "Sí 😂 qué buena")
                .photo(-13800, "valentina.gomez", "Atardecer")
                .in(-13700, "mira este atardecer")
                .react(-13800, "🔥", "Yo")
                .done();

        ChatBuilder.dm(svc, "sofia", Network.SIGNAL, "Sofía")
                .in(-40000, "¿Me pasas la dirección?")
                .out(-39900, "Calle 72 #10-34, apto 501")
                .in(-39800, "Gracias 🙏")
                .done();

        ChatBuilder.group(svc, "gaming", Network.DISCORD, "Gaming • #general", "xNico", "Mafe", "Pipe")
                .in(-20000, "xNico", "¿Partida hoy a las 9?")
                .in(-19900, "Mafe", "Yo me apunto")
                .in(-19800, "Pipe", "Llego un poco tarde")
                .out(-19700, "Dale, yo creo la sala")
                .in(-900, "xNico", "Ya estamos en el canal de voz")
                .unread(4).muted().done();

        // A Discord server (a Matrix space) with two joined channels and one not joined yet.

        ChatBuilder.dm(svc, "banco", Network.GOOGLE_MESSAGES, "Banco 85432")
                .in(-30000, "Tu código de verificación es 482913. No lo compartas.")
                .lowPriority().done();

        ChatBuilder.dm(svc, "carlos", Network.MESSENGER, "Carlos Pérez")
                .in(-250000, "Feliz cumpleaños!! 🎉🎂")
                .out(-249000, "Gracias Carlos!!")
                .archived().done();

        ChatBuilder.dm(svc, "notas", Network.MATRIX, "Notas personales")
                .out(-500000, "Lista de compras: café, pan, huevos")
                .out(-400000, "Idea: app de chats unificada en Java ☕")
                .pinned().done();
    }

    private static void discordServer(MockChatService svc) {
        Chat server = new Chat("!servidor:demo");
        server.setNetwork(Network.DISCORD);
        server.setTitle("Servidor Unichat");
        server.setSpace(true);
        server.setSpaceChildren(List.of("!srv-general:demo", "!srv-memes:demo", MockChatService.UNJOINED_CHANNEL_ID));
        svc.adopt(server);
        svc.chats.put(server.id(), server);
        ChatBuilder.group(svc, "srv-general", Network.DISCORD, "#general", "xNico", "Mafe", "Pipe")
                .in(-5000, "xNico", "Bienvenidos al servidor")
                .html(-4000, "Pipe", "<h2>Novedades</h2><p>Una <strong>lista</strong> con <em>formato</em> y <code>código</code>:</p><ul><li>uno</li><li>dos</li></ul>"
                        + "<table><tr><th>Equipo</th><th>Puntos</th></tr><tr><td>Rojo</td><td>12</td></tr></table>")
                .html(-3000, "Mafe", "https://fixupx.com/mafe/status/1234567890123456789012345678901234567890\n"
                        + "<blockquote class=\"discord-embed\" background-color=\"#6364FF\"><p class=\"discord-embed-author\">"
                        + "<img data-mx-emoticon height=\"24\" src=\"demo://avatar\" alt=\"\">&nbsp;<a href=\"https://x.com/mafe/status/1\">Mafe (@mafe)</a></p>"
                        + "<p class=\"discord-embed-description\"><p>Foto del torneo ❤️<br><a href=\"https://x.com/hashtag/torneo\">#torneo</a></p></p>"
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
            List<Member> members = new ArrayList<>(List.of(new Member(ME, "Yo", null)));
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
            Member m = who.equals("Yo") ? new Member(ME, "Yo", null) : people.get(who);
            Message msg = new Message(UUID.randomUUID().toString(), chat.id(), m.id(), m.name(), m.id().equals(ME), now.plusSeconds(secs));
            msg.setBody(text);
            list.add(msg);
            return this;
        }

        ChatBuilder out(long secs, String text) {
            Message msg = new Message(UUID.randomUUID().toString(), chat.id(), ME, "Yo", true, now.plusSeconds(secs));
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
            msg.setFileName("Nota de voz.ogg");
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
            for (String w : who) target.toggleReaction(emoji, w.equals("Yo") ? ME : people.get(w).id());
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

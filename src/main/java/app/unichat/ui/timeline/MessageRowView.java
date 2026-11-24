package app.unichat.ui.timeline;

import app.unichat.ui.A11yText;
import app.unichat.ui.Formats;
import app.unichat.ui.Icons;
import app.unichat.ui.Theme;
import app.unichat.ui.chats.Avatar;
import app.unichat.ui.emoji.EmojiInline;
import app.unichat.ui.media.MessageMedia;
import app.unichat.model.Chat;
import app.unichat.model.LinkPreview;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.service.ChatService;
import app.unichat.stt.Transcripts;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import org.jspecify.annotations.Nullable;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Draws one message of the timeline, and knows the loaded messages it needs for that: what a reply answers, whose media follows. */
final class MessageRowView {
    private final ChatService svc;
    private final MessageMedia media;
    private final MessageActionsUi actions;
    private final Consumer<String> openLink;
    private final Supplier<@Nullable Chat> chat;
    private final Function<String, @Nullable Member> member;
    private final Consumer<Message> jumpTo;
    private final java.util.Map<String, Message> loaded = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> mediaAt = new java.util.HashMap<>();

    MessageRowView(ChatService svc, MessageMedia media, MessageActionsUi actions, Consumer<String> openLink, Supplier<@Nullable Chat> chat,
                   Function<String, @Nullable Member> member, Consumer<Message> jumpTo) {
        this.svc = svc;
        this.media = media;
        this.actions = actions;
        this.openLink = openLink;
        this.chat = chat;
        this.member = member;
        this.jumpTo = jumpTo;
    }

    /** Asked while drawing each row, so it can't walk the whole chat for each of them. */
    void index(List<Message> msgs) {
        loaded.clear();
        mediaAt.clear();
        for (Message m : msgs) {
            loaded.put(m.id(), m);
            if (isOwnMedia(m)) mediaAt.merge(mediaKey(m), 1, Integer::sum);
        }
    }

    private static boolean isOwnMedia(Message m) {
        return m.type() == MessageType.VIDEO || m.type() == MessageType.AUDIO;
    }

    private static String mediaKey(Message m) {
        return m.timestamp() + "|" + m.senderId();
    }

    private @Nullable Message findLoaded(@Nullable String id) {
        return id == null || chat.get() == null ? null : loaded.get(id);
    }

    /** What {@code m} answers: loaded, or already fetched on its own ({@link ChatService#message}); null otherwise. */
    private @Nullable Message replied(Message m) {
        String id = m.replyToId();
        Message r = findLoaded(id);
        return r != null || id == null ? r : svc.message(m.chatId(), id).getNow(null);
    }

    /** The piece of the message {@code m} answers, above it. */
    private VBox replyQuote(Message m, String replyToId, double maxWidth) {
        Message replied = findLoaded(replyToId);
        VBox quote = new VBox(1);
        quote.getStyleClass().add("reply-quote");
        Label who = new Label(I18n.t("message.original"));
        who.getStyleClass().add("reply-sender");
        Label text = new Label(I18n.t("message.notLoaded"));
        text.getStyleClass().add("reply-text");
        text.setMaxWidth(maxWidth - 30);
        quote.getChildren().addAll(who, text);
        if (replied != null) {
            quote(who, text, replied);
            quote.setOnMouseClicked(e -> jumpTo.accept(replied));
        } else {
            // Further back than what's loaded (a big chat): asked for on its own, shown when it comes.
            java.util.concurrent.CompletableFuture<@Nullable Message> asked = svc.message(m.chatId(), replyToId);
            Message known = asked.getNow(null);
            if (known != null) {
                quote(who, text, known);
            } else if (!asked.isDone()) {
                text.setText(I18n.t("message.loadingOriginal"));
                var _ = asked.thenAccept(r -> Platform.runLater(() -> {
                    if (r != null) quote(who, text, r);
                    else text.setText(I18n.t("message.notLoaded"));
                }));
            }
        }
        return quote;
    }

    private static void quote(Label who, Label text, Message replied) {
        who.setText(replied.outgoing() ? I18n.t("common.you") : replied.senderName());
        text.setText(replied.preview());
    }

    /** What a screen reader says for a message row. */
    String describe(Message m) {
        String transcript = m.type() == MessageType.AUDIO && Transcripts.get().state(m.mediaUrl()) instanceof Transcripts.Done d ? d.text() : null;
        return A11yText.message(m, replied(m), transcript, ZoneId.systemDefault());
    }

    /** How wide a bubble may be in a list this wide: within 200 and 560, so most widths change nothing. */
    static double bubbleWidth(double listWidth) {
        return Math.max(200, Math.min(560, listWidth * 0.68));
    }

    /** A day's line between messages ("Hoy", "Ayer", a date), as the chat and a capture of it draw it. */
    static Node dayRow(ThreadRows.DayRow d) {
        Label l = new Label(Formats.daySeparator(d.day(), java.time.Instant.now(), ZoneId.systemDefault()));
        l.getStyleClass().add("day-separator");
        HBox box = new HBox(l);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("day-row");
        return box;
    }

    Node node(ThreadRows.MessageRow row, double width) {
        Message m = row.message();
        if (m.type() == MessageType.SYSTEM) {
            Label l = new Label(m.body());
            l.getStyleClass().add("system-message");
            l.setWrapText(true);
            HBox box = new HBox(l);
            box.setAlignment(Pos.CENTER);
            return box;
        }
        boolean out = m.outgoing();
        boolean showSender = chat.get().group() && !out && row.first();
        double maxBubble = bubbleWidth(width);

        VBox column = new VBox(2);
        column.setAlignment(out ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        if (showSender) {
            Label name = new Label(m.senderName());
            name.getStyleClass().add("sender-name");
            name.setStyle("-fx-text-fill: " + senderColor(m.senderId()) + ";");
            column.getChildren().add(name);
        }

        Node bubble = bubble(m, row, maxBubble);
        column.getChildren().add(bubble);
        if (!m.reactions().isEmpty()) column.getChildren().add(actions.reactions(m));

        HBox hover = actions.hoverActions(m, bubble);
        MessageLine line = new MessageLine(hover);
        if (out) {
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            line.getChildren().addAll(grow, hover, column);
        } else {
            if (chat.get().group()) {
                if (row.last()) {
                    Avatar a = new Avatar(28);
                    Member mem = member.apply(m.senderId());
                    a.set(m.senderName(), m.senderId(), mem == null ? null : mem.avatarUrl(), svc);
                    a.setAlignment(Pos.BOTTOM_CENTER);
                    VBox av = new VBox(a);
                    av.setAlignment(Pos.BOTTOM_CENTER);
                    line.getChildren().add(av);
                } else {
                    Region spacer = new Region();
                    spacer.setMinWidth(28);
                    line.getChildren().add(spacer);
                }
            }
            line.getChildren().addAll(column, hover);
        }
        line.setAlignment(Pos.BOTTOM_LEFT);
        line.getStyleClass().add("message-line");
        if (row.last()) line.getStyleClass().add("group-last");
        return line;
    }

    private Node bubble(Message m, ThreadRows.MessageRow row, double maxWidth) {
        boolean out = m.outgoing();
        VBox bubble = new VBox(4);
        bubble.getStyleClass().addAll("bubble", out ? "out" : "in");
        if (!Theme.highContrast()) {
            if (!row.first()) bubble.getStyleClass().add("cont-top");
            if (!row.last()) bubble.getStyleClass().add("cont-bottom");
        }
        bubble.setMaxWidth(maxWidth);
        bubble.setFillWidth(false);
        // What's narrower than the bubble (a sticker under a longer quote) keeps to the chat's side, not the quote's text.
        if (out) bubble.setAlignment(Pos.TOP_RIGHT);

        String replyToId = m.replyToId();
        if (replyToId != null) bubble.getChildren().add(replyQuote(m, replyToId, maxWidth));

        content(m, bubble, maxWidth);

        if (!m.deleted() && !m.buttons().isEmpty()) bubble.getChildren().add(BusinessButtons.of(m.buttons(), maxWidth - 24));

        bubble.getChildren().add(metaRow(m));
        bubble.setFillWidth(true);

        bubble.setOnContextMenuRequested(e -> actions.messageMenu(m).show(bubble, e.getScreenX(), e.getScreenY()));
        return bubble;
    }

    /** What the message is, drawn by its kind: deleted, a video or picture, a voice note, a file, big emoji or text. */
    private void content(Message m, VBox bubble, double maxWidth) {
        List<Node> into = bubble.getChildren();
        boolean picture = m.type() == MessageType.IMAGE || m.type() == MessageType.STICKER;
        if (m.deleted()) {
            Label l = new Label(I18n.t("message.deleted"));
            l.getStyleClass().add("deleted-text");
            into.add(l);
        } else if (m.playsInline() || picture) {
            bubble.getStyleClass().add(m.type() == MessageType.STICKER ? "sticker" : "media");
            into.add(m.playsInline() ? media.inlineVideoNode(m) : media.imageNode(m));
        } else if (m.type() == MessageType.AUDIO && m.mediaUrl() != null) {
            into.add(media.audioNode(m, chat.get()));
            into.add(media.transcriptNode(m, maxWidth));
        } else if (m.isMedia()) {
            into.add(media.fileNode(m));
        } else if (m.type() == MessageType.TEXT && RichText.isJumboEmoji(m.body()) && m.replyToId() == null) {
            bubble.getStyleClass().add("jumbo");
            into.add(jumbo(m));
        } else {
            text(m, into, maxWidth - 24);
        }
    }

    /** Big, and whole: a flag or joined emoji as its picture (EmojiInline), the rest as text. */
    private static Node jumbo(Message m) {
        HBox big = new HBox();
        big.setAlignment(Pos.CENTER_LEFT);
        for (Node n : EmojiInline.nodes(m.body().strip(), Label::new)) {
            n.getStyleClass().add("jumbo-emoji");
            big.getChildren().add(n);
        }
        return big;
    }

    /** A text message: its HTML or text, its link preview, and players for the videos its links lead to. */
    private void text(Message m, List<Node> into, double textWidth) {
        if (m.html() != null && m.type() != MessageType.EMOTE) {
            into.add(HtmlView.of(m.html(), textWidth, media, openLink));
        } else {
            String body = m.type() == MessageType.EMOTE ? "* " + m.senderName() + " " + m.body() : m.body();
            TextFlow flow = new TextFlow();
            flow.getChildren().addAll(RichText.nodes(body, openLink, textWidth));
            flow.getStyleClass().add("message-text");
            flow.setLineSpacing(Theme.lineSpacing());
            if (m.type() == MessageType.NOTICE) flow.getStyleClass().add("notice");
            flow.setMaxWidth(textWidth);
            into.add(flow);
        }
        boolean ownMedia = followedByItsOwnMedia(m);
        for (LinkPreview p : m.linkPreviews()) into.add(cardWithItsVideo(p, ownMedia, textWidth));
        if (!m.linkPreviews().isEmpty() || ownMedia) return;
        List<String> links = links(m);
        // An embed (Discord's) already shows the page: only its video is missing. Otherwise the first link gets its card,
        // as Telegram gives one per message.
        if (m.htmlText() != null) into.add(media.linkPlayers(links, chat.get(), textWidth));
        else if (!links.isEmpty()) into.add(fetchedCard(links.get(0), textWidth));
    }

    /** A bridge's card has the page's picture only: the video it leads to takes its place once found. */
    private Node cardWithItsVideo(LinkPreview p, boolean ownMedia, double width) {
        VBox box = new VBox(LinkPreviewCard.of(p, width, media, openLink, chat.get()));
        if (ownMedia || p.url().isEmpty()) return box;
        var _ = svc.linkMedia(p.url()).thenAcceptAsync(found -> found.ifPresent(v ->
                box.getChildren().setAll(LinkPreviewCard.of(p.withVideo(v, false), width, media, openLink, chat.get()))), Platform::runLater);
        return box;
    }

    /** A link no bridge described: Unichat reads the page, as Telegram's server does for its apps. */
    private Node fetchedCard(String link, double width) {
        VBox box = new VBox();
        var _ = svc.linkPreview(link).thenAcceptAsync(found -> found.ifPresent(p ->
                box.getChildren().setAll(LinkPreviewCard.of(p, width, media, openLink, chat.get()))), Platform::runLater);
        return box;
    }

    /** "Edited", the time and, on one's own, whether it went; on the bubble's right. */
    private Node metaRow(Message m) {
        HBox meta = new HBox(4);
        meta.getStyleClass().add("bubble-meta");
        meta.setAlignment(Pos.CENTER_RIGHT);
        if (actions.fixing(m)) meta.getChildren().add(fixingNote());
        if (m.edited() && !m.deleted()) meta.getChildren().add(new Label(I18n.t("message.edited")));
        meta.getChildren().add(new Label(Formats.time(m.timestamp(), ZoneId.systemDefault())));
        if (m.outgoing()) meta.getChildren().add(statusIcon(m.status()));
        HBox metaRow = new HBox(meta);
        metaRow.setAlignment(Pos.CENTER_RIGHT);
        metaRow.setMaxWidth(Double.MAX_VALUE);
        return metaRow;
    }

    /** "Corrigiendo con IA…" with a turning wheel, on the message being corrected (and only on it). */
    private static Node fixingNote() {
        javafx.scene.control.ProgressIndicator wheel = new javafx.scene.control.ProgressIndicator();
        wheel.getStyleClass().add("message-fixing-wheel"); // as big as the text (app.css)
        Label l = new Label(I18n.t("messageFix.working"), wheel);
        l.getStyleClass().addAll("transcript-status", "message-fixing");
        return l;
    }

    private static List<String> links(Message m) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(m.html() != null ? HtmlView.links(m.html()) : RichText.addresses(m.body()));
        m.linkPreviews().stream().map(LinkPreview::url).filter(u -> !u.isEmpty()).forEach(out::add);
        return List.copyOf(out);
    }

    /** A bridge sends the video it could get as a message of its own right after, at the same moment: no second player. */
    private boolean followedByItsOwnMedia(Message m) {
        return mediaAt.getOrDefault(mediaKey(m), 0) > (isOwnMedia(m) ? 1 : 0);
    }

    /** The icon's style class, as app.css names it ("status-failed"), whatever the computer's language. */
    static String statusClass(MessageStatus s) {
        return "status-" + s.name().toLowerCase(Locale.ROOT);
    }

    private Node statusIcon(MessageStatus s) {
        Node icon = switch (s) {
            case SENDING -> Icons.icon(Icons.CLOCK, 12);
            case SENT -> Icons.icon(Icons.CHECK, 13);
            case DELIVERED, READ -> Icons.icon(Icons.CHECK_DOUBLE, 14);
            case FAILED -> Icons.icon(Icons.ERROR, 13);
        };
        icon.getStyleClass().add(statusClass(s));
        return icon;
    }

    private static final String[] SENDER_COLORS = {"#D6409F", "#E5484D", "#F76B15", "#46A758", "#12A594", "#0090FF", "#8E4EC6", "#3E63DD"};

    static String senderColor(String id) {
        return SENDER_COLORS[Math.floorMod(id.hashCode(), SENDER_COLORS.length)];
    }
}

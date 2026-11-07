package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.service.ChatService;
import app.unichat.stt.Transcripts;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Draws one message of the timeline, and knows the loaded messages it needs for that: what a reply answers, whose media follows. */
final class MessageRowView {
    private final ChatService svc;
    private final MessageMedia media;
    private final MessageActionsUi actions;
    private final Consumer<String> openLink;
    private final Supplier<Chat> chat;
    private final Function<String, Member> member;
    private final Consumer<Message> jumpTo;
    private final java.util.Map<String, Message> loaded = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> mediaAt = new java.util.HashMap<>();

    MessageRowView(ChatService svc, MessageMedia media, MessageActionsUi actions, Consumer<String> openLink, Supplier<Chat> chat,
                   Function<String, Member> member, Consumer<Message> jumpTo) {
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

    private Message findLoaded(String id) {
        return id == null || chat.get() == null ? null : loaded.get(id);
    }

    /** What a screen reader says for a message row. */
    String describe(Message m) {
        String transcript = m.type() == MessageType.AUDIO && Transcripts.get().state(m.mediaUrl()) instanceof Transcripts.Done d ? d.text() : null;
        return A11yText.message(m, findLoaded(m.replyToId()), transcript, ZoneId.systemDefault());
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
        double maxBubble = Math.max(200, Math.min(560, width * 0.68));

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

        Message replied = findLoaded(m.replyToId());
        if (m.replyToId() != null) {
            VBox quote = new VBox(1);
            quote.getStyleClass().add("reply-quote");
            Label who = new Label(replied == null ? "Mensaje original" : replied.outgoing() ? "Tú" : replied.senderName());
            who.getStyleClass().add("reply-sender");
            Label text = new Label(replied == null ? "No cargado" : replied.preview());
            text.getStyleClass().add("reply-text");
            text.setMaxWidth(maxWidth - 30);
            quote.getChildren().addAll(who, text);
            if (replied != null) quote.setOnMouseClicked(e -> jumpTo.accept(replied));
            bubble.getChildren().add(quote);
        }

        if (m.deleted()) {
            Label l = new Label("Mensaje eliminado");
            l.getStyleClass().add("deleted-text");
            bubble.getChildren().add(l);
        } else if (m.playsInline()) {
            bubble.getStyleClass().add(m.type() == MessageType.STICKER ? "sticker" : "media");
            bubble.getChildren().add(media.inlineVideoNode(m));
        } else if (m.type() == MessageType.IMAGE || m.type() == MessageType.STICKER) {
            bubble.getStyleClass().add(m.type() == MessageType.STICKER ? "sticker" : "media");
            bubble.getChildren().add(media.imageNode(m));
        } else if (m.type() == MessageType.AUDIO && m.mediaUrl() != null) {
            bubble.getChildren().add(media.audioNode(m, chat.get()));
            bubble.getChildren().add(media.transcriptNode(m, maxWidth));
        } else if (m.isMedia()) {
            bubble.getChildren().add(media.fileNode(m));
        } else if ((m.type() == MessageType.TEXT) && RichText.isJumboEmoji(m.body()) && m.replyToId() == null) {
            bubble.getStyleClass().add("jumbo");
            Label l = new Label(m.body().strip());
            l.getStyleClass().add("jumbo-emoji");
            bubble.getChildren().add(l);
        } else {
            String body = m.type() == MessageType.EMOTE ? "* " + m.senderName() + " " + m.body() : m.body();
            double textWidth = maxWidth - 24;
            if (m.html() != null && m.type() != MessageType.EMOTE) {
                bubble.getChildren().add(HtmlView.of(m.html(), textWidth, media, openLink));
            } else {
                TextFlow flow = new TextFlow();
                flow.getChildren().addAll(RichText.nodes(body, openLink, textWidth));
                flow.getStyleClass().add("message-text");
                flow.setLineSpacing(Theme.lineSpacing());
                if (m.type() == MessageType.NOTICE) flow.getStyleClass().add("notice");
                flow.setMaxWidth(textWidth);
                bubble.getChildren().add(flow);
            }
            if (m.previewHtml() != null) bubble.getChildren().add(HtmlView.of(m.previewHtml(), textWidth, media, openLink));
            if (!followedByItsOwnMedia(m)) bubble.getChildren().add(media.linkPlayers(links(m), chat.get(), textWidth));
        }

        HBox meta = new HBox(4);
        meta.getStyleClass().add("bubble-meta");
        meta.setAlignment(Pos.CENTER_RIGHT);
        if (m.edited() && !m.deleted()) {
            Label ed = new Label("editado");
            meta.getChildren().add(ed);
        }
        meta.getChildren().add(new Label(Formats.time(m.timestamp(), ZoneId.systemDefault())));
        if (out) meta.getChildren().add(statusIcon(m.status()));
        HBox metaRow = new HBox(meta);
        metaRow.setAlignment(Pos.CENTER_RIGHT);
        metaRow.setMaxWidth(Double.MAX_VALUE);
        bubble.getChildren().add(metaRow);
        bubble.setFillWidth(true);

        bubble.setOnContextMenuRequested(e -> actions.messageMenu(m).show(bubble, e.getScreenX(), e.getScreenY()));
        bubble.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !m.deleted()
                    && !chat.get().features().reactionsOffered(java.util.List.of("❤️")).isEmpty()) {
                svc.toggleReaction(chat.get().id(), m.id(), "❤️");
            }
        });
        return bubble;
    }

    private static java.util.List<String> links(Message m) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(m.html() != null ? HtmlView.links(m.html()) : RichText.addresses(m.body()));
        if (m.previewHtml() != null) out.addAll(HtmlView.links(m.previewHtml()));
        return java.util.List.copyOf(out);
    }

    /** A bridge sends the video it could get as a message of its own right after, at the same moment: no second player. */
    private boolean followedByItsOwnMedia(Message m) {
        return mediaAt.getOrDefault(mediaKey(m), 0) > (isOwnMedia(m) ? 1 : 0);
    }

    private Node statusIcon(MessageStatus s) {
        Node icon = switch (s) {
            case SENDING -> Icons.icon(Icons.CLOCK, 12);
            case SENT -> Icons.icon(Icons.CHECK, 13);
            case DELIVERED, READ -> Icons.icon(Icons.CHECK_DOUBLE, 14);
            case FAILED -> Icons.icon(Icons.ERROR, 13);
        };
        icon.getStyleClass().add("status-" + s.name().toLowerCase());
        return icon;
    }

    private static final String[] SENDER_COLORS = {"#D6409F", "#E5484D", "#F76B15", "#46A758", "#12A594", "#0090FF", "#8E4EC6", "#3E63DD"};

    static String senderColor(String id) {
        return SENDER_COLORS[Math.floorMod(id.hashCode(), SENDER_COLORS.length)];
    }
}

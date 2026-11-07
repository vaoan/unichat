package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.service.ChatService;
import app.unichat.stt.Transcripts;
import app.unichat.support.I18n;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.stage.Popup;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javafx.scene.Scene;
import java.util.function.Supplier;
import java.util.function.Function;

/** What you can do with a message from the screen: reactions, hover buttons and its context menu. */
final class MessageActionsUi {
    private final ChatService svc;
    private final Consumer<String> toast;
    private final Composer composer;
    private final MessageMedia media;
    private final Supplier<Chat> chat;
    private final MediaLibrary library;
    private final Supplier<Scene> scene;
    private final Function<String, Member> member;

    MessageActionsUi(ChatService svc, Consumer<String> toast, Composer composer, MessageMedia media, Supplier<Chat> chat,
                     MediaLibrary library, Supplier<Scene> scene, Function<String, Member> member) {
        this.svc = svc;
        this.toast = toast;
        this.composer = composer;
        this.media = media;
        this.chat = chat;
        this.library = library;
        this.scene = scene;
        this.member = member;
    }

    Node reactions(Message m) {
        HBox box = new HBox(4);
        box.getStyleClass().add("reactions");
        for (Map.Entry<String, Set<String>> r : m.reactions().entrySet()) {
            Label emoji = ReactionEmoji.of(r.getKey(), svc);
            emoji.getStyleClass().add("reaction-emoji");
            HBox content = new HBox(4, emoji);
            content.setAlignment(Pos.CENTER);
            if (r.getValue().size() > 1) {
                Label count = new Label(Integer.toString(r.getValue().size()));
                count.getStyleClass().add("reaction-count");
                content.getChildren().add(count);
            }
            Button b = new Button();
            b.setGraphic(content);
            b.getStyleClass().add("reaction-pill");
            if (r.getValue().contains(svc.myUserId())) b.getStyleClass().add("mine");
            b.setTooltip(new javafx.scene.control.Tooltip((Message.isCustomReaction(r.getKey()) ? m.reactionLabel(r.getKey()) + ": " : "")
                    + String.join(", ", r.getValue().stream().map(this::nameOf).toList())));
            b.setOnAction(e -> svc.toggleReaction(chat.get().id(), m.id(), r.getKey()));
            b.setFocusTraversable(false);
            boolean mine = r.getValue().contains(svc.myUserId());
            b.setAccessibleText(I18n.t(mine ? "message.reactionMine" : "message.reactionOther",
                    m.reactionLabel(r.getKey()), r.getValue().size()));
            box.getChildren().add(b);
        }
        return box;
    }

    private String nameOf(String userId) {
        if (userId.equals(svc.myUserId())) return I18n.t("common.you");
        Member mem = member.apply(userId);
        return mem == null ? userId : mem.displayName();
    }

    HBox hoverActions(Message m, Node bubble) {
        HBox box = new HBox(2);
        box.getStyleClass().add("hover-actions");
        box.setAlignment(Pos.CENTER);
        if (m.deleted()) return box;
        if (!chat.get().features().reactionsOffered(java.util.List.of(EmojiPicker.QUICK)).isEmpty() || chat.get().features().anyReaction()) {
            Button react = Icons.iconButton(Icons.EMOJI, I18n.t("message.react"), null);
            react.setOnAction(e -> reactionPopup(m, react));
            box.getChildren().add(react);
        }
        if (allows(m, MessageActions.Action.REPLY)) box.getChildren().add(Icons.iconButton(Icons.REPLY, I18n.t("message.reply"), () -> composer.reply(m)));
        Button more = Icons.iconButton(Icons.MORE, I18n.t("message.more"), null);
        more.setOnAction(e -> messageMenu(m).show(more, Side.BOTTOM, 0, 0));
        box.getChildren().add(more);
        // Reachable with the timeline keys instead; tabbing through every message would be unusable.
        box.getChildren().forEach(n -> n.setFocusTraversable(false));
        return box;
    }

    private void reactionPopup(Message m, Node anchor) {
        Popup p = new Popup();
        p.setAutoHide(true);
        HBox row = new HBox(2);
        row.getStyleClass().addAll("popup-surface", "quick-reactions");
        row.getStylesheets().addAll(scene.get().getStylesheets());
        row.getStyleClass().addAll(scene.get().getRoot().getStyleClass());
        row.setStyle(scene.get().getRoot().getStyle());
        for (String e : chat.get().features().reactionsOffered(java.util.List.of(EmojiPicker.QUICK))) {
            Button b = new Button(e);
            b.getStyleClass().add("emoji-button");
            b.setOnAction(ev -> {
                svc.toggleReaction(chat.get().id(), m.id(), e);
                p.hide();
            });
            row.getChildren().add(b);
        }
        if (chat.get().features().anyReaction()) {
            Button plus = Icons.iconButton(Icons.PLUS, I18n.t("message.moreEmojis"), null);
            plus.setOnAction(ev -> {
                p.hide();
                EmojiPicker.show(anchor, e -> svc.toggleReaction(chat.get().id(), m.id(), e));
            });
            row.getChildren().add(plus);
        }
        p.getContent().add(row);
        var b = anchor.localToScreen(anchor.getBoundsInLocal());
        p.show(anchor, b.getMinX() - 120, b.getMinY() - 52);
    }

    /** Same rules as the message menu, for the shortcuts and hover buttons. */
    boolean allows(Message m, MessageActions.Action a) {
        return MessageActions.of(m, chat.get().features(), java.time.Instant.now()).contains(a);
    }

    ContextMenu messageMenu(Message m) {
        ContextMenu menu = new ContextMenu();
        java.util.Set<MessageActions.Action> a = MessageActions.of(m, chat.get().features(), Instant.now());
        if (a.isEmpty()) return menu;
        if (a.contains(MessageActions.Action.REPLY)) menu.getItems().add(ChatMenus.item(I18n.t("message.reply"), () -> composer.reply(m)));
        if (a.contains(MessageActions.Action.COPY)) menu.getItems().add(ChatMenus.item(I18n.t("message.copyText"), () -> Clipboards.copy(m.body())));
        if (a.contains(MessageActions.Action.OPEN_FILE)) menu.getItems().add(ChatMenus.item(I18n.t("message.openFile"), () -> media.openFile(m)));
        if (a.contains(MessageActions.Action.SAVE_AS)) menu.getItems().add(ChatMenus.item(I18n.t("message.saveAs"), () -> {
            if (scene.get() != null) MediaSaver.save(scene.get().getWindow(), svc, m, toast);
        }));
        if (a.contains(MessageActions.Action.TRANSCRIBE)) {
            if (Transcripts.get().state(m.mediaUrl()) instanceof Transcripts.Done d) {
                if (!d.text().isEmpty()) menu.getItems().add(ChatMenus.item(I18n.t("message.copyTranscript"), () -> Clipboards.copy(d.text())));
            } else {
                menu.getItems().add(ChatMenus.item(I18n.t("message.transcribe"), () -> media.transcribe(m)));
            }
        }
        if (a.contains(MessageActions.Action.FAVORITE)) {
            boolean fav = library.isFavorite(MediaItems.of(m));
            menu.getItems().add(ChatMenus.item(I18n.t(fav ? "message.removeFavorite" : "message.addFavorite"), () -> {
                boolean now = library.toggleFavorite(MediaItems.of(m));
                toast.accept(I18n.t(now ? "message.favoriteAdded" : "message.favoriteRemoved"));
            }));
        }
        if (a.contains(MessageActions.Action.EDIT)) menu.getItems().add(ChatMenus.item(I18n.t("message.edit"), () -> composer.edit(m)));
        java.util.List<String> reactions = chat.get().features().reactionsOffered(java.util.List.of(EmojiPicker.QUICK));
        if (!reactions.isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
            for (String e : reactions) menu.getItems().add(ChatMenus.item(I18n.t("message.reactWith", e), () -> svc.toggleReaction(chat.get().id(), m.id(), e)));
        }
        if (a.contains(MessageActions.Action.DELETE_FOR_EVERYONE)) {
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(ChatMenus.danger(ChatMenus.item(I18n.t("message.deleteForAll"), () -> svc.deleteMessage(chat.get().id(), m.id()))));
        }
        return menu;
    }
}

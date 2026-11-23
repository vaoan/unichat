package app.unichat.ui.timeline;

import app.unichat.autosave.AutoSave;
import app.unichat.autosave.SaveFolders;
import app.unichat.ui.Clipboards;
import app.unichat.ui.Icons;
import app.unichat.ui.chats.ChatMenus;
import app.unichat.ui.emoji.EmojiPicker;
import app.unichat.ui.emoji.LibraryMenu;
import app.unichat.ui.emoji.MediaItems;
import app.unichat.ui.emoji.ReactionEmoji;
import app.unichat.ui.media.MediaSaver;
import app.unichat.ui.media.MessageMedia;
import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.ChatService;
import app.unichat.stt.Transcripts;
import app.unichat.support.I18n;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.stage.Popup;
import java.time.Instant;
import java.util.ArrayList;
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
    private final Consumer<Message> jumpTo;
    private Consumer<String> onShowStickerSet = url -> { };
    /** Links offered in a message's menu: the keyboard has no other way to them. */
    private static final int MENU_LINKS = 5;

    private Consumer<Message> onForward = m -> { }, onDelete = m -> { }, onDeleteSeveral = m -> { }, onSavedText = m -> { },
            onCapture = m -> { };

    /** "Capturar conversación…": picking the stretch to capture starts with {@code m}. */
    void setOnCapture(Consumer<Message> capture) {
        this.onCapture = capture;
    }

    private MessageFixer fixer;

    /** "Corregir con IA": corrects a message already sent, apart from the message box. */
    void setFixer(MessageFixer fixer) {
        this.fixer = fixer;
    }

    /** Whether {@code m} is being corrected now: its row says so. */
    boolean fixing(Message m) {
        return fixer != null && fixer.fixing(m.id());
    }

    /** "Ver lo que decía…": what {@code m} said, from the copy of the chat's texts. */
    void setOnSavedText(Consumer<Message> show) {
        this.onSavedText = show;
    }

    /** "Reenviar…": picking messages to forward starts with {@code m}. */
    void setOnForward(Consumer<Message> forward) {
        this.onForward = forward;
    }

    /** "Eliminar para todos…" asks about {@code m} ({@code one}); "Eliminar varios…" starts picking with it ({@code several}). */
    void setOnDelete(Consumer<Message> one, Consumer<Message> several) {
        this.onDelete = one;
        this.onDeleteSeveral = several;
    }

    /** Where "see this sticker's set" opens it (the side panel's Stickers tab). */
    void setOnShowStickerSet(Consumer<String> c) { this.onShowStickerSet = c; }

    MessageActionsUi(ChatService svc, Consumer<String> toast, Composer composer, MessageMedia media, Supplier<Chat> chat,
                     MediaLibrary library, Supplier<Scene> scene, Function<String, Member> member, Consumer<Message> jumpTo) {
        this.jumpTo = jumpTo;
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
            b.setOnAction(e -> { var _ = svc.toggleReaction(chat.get().id(), m.id(), r.getKey()); });
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
        // A deleted message has only its menu left, with what it said.
        if (!m.deleted() && (!quick().isEmpty() || chat.get().features().anyReaction())) {
            Button react = Icons.iconButton(Icons.EMOJI, I18n.t("message.react"), null);
            react.setOnAction(e -> reactionPopup(m, react));
            box.getChildren().add(react);
        }
        if (allows(m, MessageActions.Action.REPLY)) box.getChildren().add(Icons.iconButton(Icons.REPLY, I18n.t("message.reply"), () -> composer.reply(m)));
        box.getChildren().add(more(m));
        // Reachable with the timeline keys instead; tabbing through every message would be unusable.
        box.getChildren().forEach(n -> n.setFocusTraversable(false));
        return box;
    }

    /** The "⋯" that opens the message's menu. */
    private Button more(Message m) {
        Button more = Icons.iconButton(Icons.MORE, I18n.t("message.more"), null);
        more.setOnAction(e -> messageMenu(m).show(more, Side.BOTTOM, 0, 0));
        return more;
    }

    private void reactionPopup(Message m, Node anchor) {
        Popup p = new Popup();
        p.setAutoHide(true);
        HBox row = new HBox(2);
        row.getStyleClass().addAll("popup-surface", "quick-reactions");
        row.getStylesheets().addAll(scene.get().getStylesheets());
        row.getStyleClass().addAll(scene.get().getRoot().getStyleClass());
        row.setStyle(scene.get().getRoot().getStyle());
        for (String e : quick()) {
            Button b = new Button(e);
            b.getStyleClass().add("emoji-button");
            b.setAccessibleText(I18n.t("message.reactWith", e));
            b.setOnAction(ev -> {
                react(m, e);
                p.hide();
            });
            row.getChildren().add(b);
        }
        if (chat.get().features().anyReaction()) {
            Button plus = Icons.iconButton(Icons.PLUS, I18n.t("message.moreEmojis"), null);
            plus.setOnAction(ev -> {
                p.hide();
                EmojiPicker.showForReaction(anchor, library, e -> react(m, e));
            });
            row.getChildren().add(plus);
        }
        p.getContent().add(row);
        var b = anchor.localToScreen(anchor.getBoundsInLocal());
        p.show(anchor, b.getMinX() - 120, b.getMinY() - 52);
    }

    /** The quick reactions: the ones the person reacts with most (the usual six until there's history), as this chat allows. */
    List<String> quick() {
        return chat.get().features().reactionsOffered(library.mostReacted(EmojiPicker.QUICK.size(), EmojiPicker.QUICK));
    }

    /** Adds or takes away the person's {@code e} on {@code m}; an added one counts towards the quick ones. */
    void react(Message m, String e) {
        Set<String> who = m.reactions().get(e);
        if (who == null || !who.contains(svc.myUserId())) library.reacted(e);
        var _ = svc.toggleReaction(chat.get().id(), m.id(), e);
    }

    /** Same rules as the message menu, for the shortcuts and hover buttons. */
    boolean allows(Message m, MessageActions.Action a) {
        return actionsOf(m).contains(a);
    }

    /** What {@code m} allows in the open chat, which may keep what I send (its versions to see). */
    private Set<MessageActions.Action> actionsOf(Message m) {
        Chat c = chat.get();
        boolean mineKept = svc.autoSaves(c.id(), true).contains(app.unichat.model.SaveKind.TEXTS);
        return MessageActions.of(m, c.features(), Instant.now(), mineKept);
    }

    /** The message {@code m} answers, if it's in the timeline to go to. */
    private Message replied(Message m) {
        if (m.replyToId() == null) return null;
        for (Message x : svc.messages(m.chatId())) if (x.id().equals(m.replyToId())) return x;
        return null;
    }

    /**
     * Saves what {@code url} leads to into the chat's folder, as auto-save would, on or off for this chat; the menu-bar
     * ring and the notices tell how it went ({@link AutoSave.Progress}), this only where it's going.
     */
    private void saveLink(Message m, String url) {
        AutoSave save = AutoSave.of(svc);
        toast.accept(I18n.t("message.savingLink", SaveFolders.shown(save.folder(chat.get()))));
        var _ = save.saveLink(m, url);
    }

    /** The menu's order for what a message allows; the quick reactions and "delete for everyone" come after, set apart. */
    private static final List<MessageActions.Action> MENU_ORDER = List.of(MessageActions.Action.SAVE_LINK,
            MessageActions.Action.REPLY, MessageActions.Action.COPY, MessageActions.Action.FORWARD, MessageActions.Action.OPEN_FILE,
            MessageActions.Action.SAVE_AS, MessageActions.Action.TRANSCRIBE, MessageActions.Action.FAVORITE, MessageActions.Action.PIN,
            MessageActions.Action.EDIT, MessageActions.Action.FIX, MessageActions.Action.SAVED_TEXT, MessageActions.Action.CAPTURE);

    ContextMenu messageMenu(Message m) {
        ContextMenu menu = new ContextMenu();
        Set<MessageActions.Action> a = actionsOf(m);
        if (a.isEmpty()) return menu;
        List<MenuItem> items = menu.getItems();
        items.addAll(ways(m));
        for (MessageActions.Action act : MENU_ORDER) if (a.contains(act)) items.addAll(itemsFor(act, m));
        List<String> reactions = quick();
        if (!reactions.isEmpty()) {
            items.add(new SeparatorMenuItem());
            for (String e : reactions) items.add(ChatMenus.item(I18n.t("message.reactWith", e), () -> react(m, e)));
        }
        if (a.contains(MessageActions.Action.DELETE_FOR_EVERYONE)) {
            items.add(new SeparatorMenuItem());
            // Never at once: the sheet asks twice (DeleteSheet).
            items.add(ChatMenus.danger(ChatMenus.item(I18n.t("message.deleteForAll"), () -> onDelete.accept(m))));
            items.add(ChatMenus.danger(ChatMenus.item(I18n.t("message.deleteSeveral"), () -> onDeleteSeveral.accept(m))));
        }
        return menu;
    }

    /** What only the mouse could do otherwise: view it, enlarge a sticker, go to what it answers, open its links. */
    private List<MenuItem> ways(Message m) {
        List<MenuItem> out = new ArrayList<>();
        if (media.canView(m)) out.add(ChatMenus.item(I18n.t("message.view"), () -> media.view(m)));
        if (m.type() == MessageType.STICKER && m.mediaUrl() != null) {
            out.add(ChatMenus.item(I18n.t("library.enlarge"), () -> {
                if (scene.get() != null) LibraryMenu.enlarge(scene.get().getWindow(), svc, MediaItems.of(m), toast);
            }));
        }
        Message replied = replied(m);
        if (replied != null) out.add(ChatMenus.item(I18n.t("message.goToReplied"), () -> jumpTo.accept(replied)));
        for (String url : RichText.addresses(m.body()).stream().distinct().limit(MENU_LINKS).toList()) {
            out.add(ChatMenus.item(I18n.t("message.openLink", RichText.shown(url, 40)), () -> media.openLink(url)));
        }
        return out;
    }

    /** The menu's items for {@code act}, which {@code m} allows. */
    private List<MenuItem> itemsFor(MessageActions.Action act, Message m) {
        return switch (act) {
            case SAVE_LINK -> AutoSave.links(m).stream().limit(MENU_LINKS)
                    .map(url -> ChatMenus.item(I18n.t("message.saveLink", RichText.shown(url, 40)), () -> saveLink(m, url))).toList();
            case REPLY -> AiReplyButton.SHOWN.get()
                    ? List.of(item("message.reply", act, m), ChatMenus.item(I18n.t("message.replyWithAi"), () -> composer.replyWithAi(m)))
                    : List.of(item("message.reply", act, m));
            case COPY -> List.of(ChatMenus.item(I18n.t("message.copyText"), () -> Clipboards.copy(m.body())));
            case FORWARD -> List.of(ChatMenus.item(I18n.t("message.forward"), () -> onForward.accept(m)));
            case OPEN_FILE -> List.of(ChatMenus.item(I18n.t("message.openFile"), () -> media.openFile(m)));
            case SAVE_AS -> List.of(ChatMenus.item(I18n.t("message.saveAs"), () -> {
                if (scene.get() != null) MediaSaver.save(scene.get().getWindow(), svc, m, toast);
            }));
            case TRANSCRIBE -> transcript(m);
            case FAVORITE -> favorite(m);
            case PIN -> {
                app.unichat.service.PinnedMessages pins = app.unichat.service.PinnedMessages.get();
                String chatId = chat.get().id();
                yield pins.isPinned(chatId, m.id())
                        ? List.of(ChatMenus.item(I18n.t("message.unpin"), () -> pins.unpin(chatId, m.id())))
                        : List.of(ChatMenus.item(I18n.t("message.pin"), () -> pins.pin(chatId, m)));
            }
            case EDIT -> List.of(item("message.edit", act, m));
            case CAPTURE -> List.of(ChatMenus.item(I18n.t("message.capture"), () -> onCapture.accept(m)));
            // With the AI turned on, and not twice: its row already says it's being corrected.
            case FIX -> fixer == null || !AiReplyButton.SHOWN.get() || fixer.fixing(m.id()) ? List.of()
                    : List.of(ChatMenus.item(I18n.t("message.fix"), () -> { var _ = fixer.fix(m); }));
            case SAVED_TEXT -> List.of(ChatMenus.item(I18n.t(m.deleted() ? "message.savedText" : "message.versions"), () -> onSavedText.accept(m)));
            case DELETE_FOR_EVERYONE, DELETE_FOR_ME -> List.of();
        };
    }

    private MenuItem item(String key, MessageActions.Action act, Message m) {
        return ChatMenus.item(I18n.t(key), () -> run(act, m));
    }

    /**
     * Does {@code act} on {@code m}, for the actions a key does too (R, T, E on the timeline): the menu and the keys
     * do the same thing.
     */
    void run(MessageActions.Action act, Message m) {
        switch (act) {
            case REPLY -> composer.reply(m);
            case TRANSCRIBE -> media.transcribe(m);
            case EDIT -> composer.edit(m);
            default -> throw new IllegalArgumentException("no key does " + act);
        }
    }

    /** A voice note's transcript to copy once it's done (none when it's empty), else the way to make one. */
    private List<MenuItem> transcript(Message m) {
        if (!(Transcripts.get().state(m.mediaUrl()) instanceof Transcripts.Done d)) return List.of(item("message.transcribe", MessageActions.Action.TRANSCRIBE, m));
        return d.text().isEmpty() ? List.of() : List.of(ChatMenus.item(I18n.t("message.copyTranscript"), () -> Clipboards.copy(d.text())));
    }

    /** Adding to or taking from the favourites, and a sticker's set. */
    private List<MenuItem> favorite(Message m) {
        List<MenuItem> out = new ArrayList<>();
        boolean fav = library.isFavorite(MediaItems.of(m));
        out.add(ChatMenus.item(I18n.t(fav ? "message.removeFavorite" : "message.addFavorite"), () -> {
            boolean now = library.toggleFavorite(MediaItems.of(m));
            toast.accept(I18n.t(now ? "message.favoriteAdded" : "message.favoriteRemoved"));
        }));
        String set = m.type() == MessageType.STICKER ? app.unichat.model.StickerPack.urlOf(m.mediaInfo()) : null;
        if (set != null) out.add(ChatMenus.item(I18n.t("sticker.showSet"), () -> onShowStickerSet.accept(set)));
        return out;
    }
}

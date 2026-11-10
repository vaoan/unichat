package app.unichat.ui.chats;

import app.unichat.ui.A11yText;
import app.unichat.ui.Formats;
import app.unichat.ui.Icons;
import app.unichat.ui.ListRows;
import app.unichat.ui.Nodes;
import app.unichat.model.Chat;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** A row of the chat lists: a chat, a server, a server still to add, or a channel still to join. */
final class ChatRowCell extends ListCell<Chat> implements ListRows.Redrawable {
    private final ChatListPane pane;
    private final ChatService svc;
    private final Spaces spaces;
    private final Avatar avatar = new Avatar(44);
    private final Label name = new Label();
    private final Label stamp = new Label();
    private final Label accountTag = new Label();
    private final Label preview = new Label();
    private final Label badge = new Label();
    private final HBox flags = new HBox(4);
    private final Label disclosure = new Label();
    private static final Insets COMPACT = new Insets(4, 10, 4, 10);
    private static final Insets NORMAL = new Insets(8, 10, 8, 10);
    /** Built once per row and only shown or hidden: rows are drawn again on every change, and new icons repaint them. */
    private final Node reminderFlag = Icons.icon(Icons.CLOCK, 13);
    private final Node mutedFlag = Icons.icon(Icons.BELL_OFF, 13);
    private final Node pinnedFlag = Icons.icon(Icons.PIN, 13);
    /** The account tag shown now: drawn again only when it changes, and always the first time (or an empty one shows). */
    private String shownAccount;
    private boolean accountDrawn;
    private final HBox root;

    ChatRowCell(ChatListPane pane, ChatService svc, Spaces spaces) {
        this.pane = pane;
        this.svc = svc;
        this.spaces = spaces;
        getStyleClass().add("chat-cell");
        name.getStyleClass().add("chat-name");
        stamp.getStyleClass().add("chat-stamp");
        stamp.setMinWidth(Region.USE_PREF_SIZE);
        preview.getStyleClass().add("chat-preview");
        badge.getStyleClass().add("unread-badge");
        badge.setMinWidth(Region.USE_PREF_SIZE);
        flags.setMinWidth(Region.USE_PREF_SIZE);
        flags.setAlignment(Pos.CENTER_RIGHT);
        flags.getStyleClass().add("chat-flags");
        flags.getChildren().addAll(reminderFlag, mutedFlag, pinnedFlag);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        name.setMinWidth(0);
        accountTag.getStyleClass().add("account-tag");
        accountTag.setMinWidth(Region.USE_PREF_SIZE);
        HBox top = new HBox(6, name, grow, accountTag, stamp);
        top.setAlignment(Pos.CENTER_LEFT);
        Region grow2 = new Region();
        HBox.setHgrow(grow2, Priority.ALWAYS);
        preview.setMinWidth(0);
        HBox bottom = new HBox(6, preview, grow2, flags, badge);
        bottom.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(2, top, bottom);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        disclosure.getStyleClass().add("disclosure");
        disclosure.setMinWidth(Region.USE_PREF_SIZE);
        root = new HBox(12, avatar, text, disclosure);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setMinWidth(0);
        // Size cells to the viewport instead of their content, so no horizontal scrollbar appears.
        setPrefWidth(0);
        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && getItem() != null) pane.open(getItem());
        });
        setOnContextMenuRequested(e -> {
            // Anchored to the column: rows are redrawn as messages arrive, and a menu closes with its owner.
            ContextMenu menu = getItem() == null ? null : pane.rowMenu(getItem());
            if (menu != null) menu.show(pane, e.getScreenX(), e.getScreenY());
        });
    }

    /** Draws its chat again, keeping it: the chat's fields changed, not which chat the row shows. */
    @Override public void redraw() {
        if (getItem() != null && !isEmpty()) updateItem(getItem(), false);
    }

    @Override
    protected void updateItem(Chat c, boolean isEmpty) {
        super.updateItem(c, isEmpty);
        if (isEmpty || c == null) {
            setGraphic(null);
            setAccessibleText(null);
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("current"), false);
            return;
        }
        boolean compact = Settings.get().compactList();
        pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("compact"), compact);
        String a11y = drawChat(c);
        boolean unjoined = pane.channels().isUnjoined(c);
        if (c.space() && disclosure.getGraphic() == null) disclosure.setGraphic(Icons.icon(Icons.CHEVRON_RIGHT, 14));
        Nodes.show(disclosure, c.space());
        Nodes.style(name, "unjoined", unjoined);
        if (Spaces.isOffer(c)) a11y = drawOffer(c);
        else if (c.space()) a11y = drawServer(c);
        else if (unjoined) a11y = drawUnjoined(c);
        setGraphic(root);
        setAccessibleText(a11y);
        Insets padding = compact ? COMPACT : NORMAL;
        if (!padding.equals(getPadding())) setPadding(padding);
        pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("current"), Objects.equals(c.id(), pane.selectedId()));
    }

    /** What every row starts from. Returns what a screen reader says for it. */
    private String drawChat(Chat c) {
        avatar.set(c.title(), c.id(), c.avatarUrl(), svc);
        avatar.setNetwork(c.network(), Settings.get().showNetworkBadges());
        name.setText(c.title());
        String account = pane.filterProperty().get().accountId() == null ? Accounts.forChat(pane.accounts(), c) : null;
        // Several accounts on this network: a tiny avatar says which one (full name on hover / screen reader).
        if (!accountDrawn || !java.util.Objects.equals(account, shownAccount)) {
            accountDrawn = true;
            shownAccount = account;
            accountTag.setText(account == null ? "" : Formats.accountShort(account));
            accountTag.setStyle(account == null ? "" : "-fx-background-color: " + Avatar.colorFor(account) + ";");
            accountTag.setTooltip(account == null ? null : new javafx.scene.control.Tooltip(I18n.t("chatRow.account", account)));
            Nodes.show(accountTag, account != null);
        }
        stamp.setText(Formats.listStamp(c.lastActivity(), Instant.now(), ZoneId.systemDefault()));
        boolean unread = c.isUnread();
        Nodes.style(name, "unread", unread);
        Nodes.style(stamp, "unread", unread && !c.muted());
        Nodes.style(preview, "typing", !c.typing().isEmpty());
        if (!c.typing().isEmpty()) {
            TypingDots.show(preview, c.group() ? Formats.typing(c.typing()) : I18n.t("chatRow.typing"));
        } else {
            String who = c.lastOutgoing() ? I18n.t("chatRow.you") : c.group() && c.lastSenderName() != null
                    ? c.lastSenderName().split(" ")[0] + ": " : "";
            TypingDots.show(preview, null); // cells are recycled: this one may have been someone typing
            preview.setText(who + c.lastPreview());
        }
        Nodes.style(preview, "unread", unread);
        Nodes.show(reminderFlag, c.reminderAt() != null);
        Nodes.show(mutedFlag, c.muted());
        Nodes.show(pinnedFlag, c.pinned());
        int n = c.unreadCount();
        badge.setText(c.mentionCount() > 0 ? "@" : n > 0 ? Formats.badge(n) : "");
        Nodes.show(badge, unread);
        Nodes.style(badge, "muted", c.muted());
        Nodes.style(badge, "dot", n == 0 && c.mentionCount() == 0);
        return A11yText.chat(c, Accounts.forChat(pane.accounts(), c), Instant.now(), ZoneId.systemDefault());
    }

    private String drawOffer(Chat c) {
        boolean busy = spaces.adding(Spaces.offerId(c));
        stamp.setText("");
        TypingDots.show(preview, null);
        preview.setText(busy ? I18n.t("chatRow.adding") : I18n.t("chatRow.offer"));
        Nodes.show(badge, false);
        return A11yText.offer(c, busy);
    }

    private String drawServer(Chat c) {
        Chat latest = pane.rows().latest().get(c.id());
        int u = pane.rows().unread().getOrDefault(c.id(), 0);
        stamp.setText(latest == null ? "" : Formats.listStamp(latest.lastActivity(), Instant.now(), ZoneId.systemDefault()));
        TypingDots.show(preview, null);
        preview.setText(latest == null ? I18n.t("chatRow.noMessages") : latest.title() + ": " + latest.lastPreview());
        boolean un = u > 0;
        Nodes.style(name, "unread", un);
        Nodes.style(stamp, "unread", un && !c.muted());
        Nodes.style(preview, "unread", un);
        badge.setText(Formats.badge(u));
        Nodes.show(badge, un);
        Nodes.style(badge, "dot", false);
        return A11yText.server(c, u, latest);
    }

    private String drawUnjoined(Chat c) {
        stamp.setText("");
        TypingDots.show(preview, null);
        preview.setText(I18n.t("chatRow.tapToJoin"));
        Nodes.show(badge, false);
        return A11yText.unjoined(c);
    }
}

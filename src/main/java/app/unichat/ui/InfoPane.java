package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.ChatService;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/** Collapsible right panel: chat identity, quick actions, members and shared media. */
public final class InfoPane extends ScrollPane {
    private final ChatService svc;
    private final VBox box = new VBox(16);
    private final Runnable close;
    private final Consumer<String> toast;
    private final ProfileMedia media;
    private Consumer<Chat> onOptions;
    private Chat chat;
    /** What the panel was last built from ({@link #shown}): unchanged, it isn't built again. */
    private String built;

    public InfoPane(ChatService svc, Runnable close, Consumer<String> toast) {
        this.media = new ProfileMedia(svc, () -> getScene() == null ? null : getScene().getWindow(), toast);
        this.svc = svc;
        this.close = close;
        this.toast = toast;
        getStyleClass().add("info-pane");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        box.getStyleClass().add("info-content");
        setContent(box);
        setPrefWidth(300);
        setMinWidth(260);
    }

    public void show(Chat c) {
        chat = c;
        refresh();
    }

    /**
     * Builds the panel again only when something on it changed: the window calls this on every new message and list
     * change, and building it anew blanked the photo and the pictures until they loaded again (a flash each time).
     */
    public void refresh() {
        String now = chat == null ? null : shown(chat);
        if (now != null && now.equals(built)) return;
        built = now;
        box.getChildren().clear();
        if (chat == null) return;
        Chat c = chat;

        Button x = Icons.iconButton(Icons.CLOSE, I18n.t("info.close"), close);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Label head = new Label(I18n.t("info.heading"));
        head.getStyleClass().add("info-heading");
        HBox top = new HBox(head, grow, x);
        top.setAlignment(Pos.CENTER_LEFT);

        Avatar a = new Avatar(84);
        a.set(c.title(), c.id(), c.avatarUrl(), svc);
        a.setNetwork(c.network(), true);
        enlargeable(a, c.avatarUrl(), c.title());
        Label title = new Label(c.title());
        title.getStyleClass().add("info-title");
        title.setWrapText(true);
        Label net = new Label(c.network().displayName() + (c.group() ? I18n.t("info.group") : I18n.t("info.direct")));
        net.getStyleClass().add("info-subtitle");
        VBox ident = new VBox(8, a, title, net);
        ident.setAlignment(Pos.CENTER);
        if (c.topic() != null && !c.topic().isBlank()) {
            Label topic = new Label(c.topic());
            topic.setWrapText(true);
            topic.getStyleClass().add("info-topic");
            ident.getChildren().add(topic);
        }

        // Six round icons (their names in a tooltip and for screen readers): all in a row when they fit, else three and
        // three (ActionGrid decides from the width it gets).
        ActionGrid actions = new ActionGrid(8);
        actions.getChildren().addAll(
                action(Icons.BELL_OFF, c.muted() ? I18n.t("info.unmute") : I18n.t("info.mute"), c.muted(), () -> svc.setMuted(c.id(), !c.muted())),
                action(Icons.STAR, I18n.t("info.favorite"), c.favorite(), () -> svc.setFavorite(c.id(), !c.favorite())),
                action(Icons.PIN, I18n.t("info.pin"), c.pinned(), () -> svc.setPinned(c.id(), !c.pinned())),
                action(Icons.ARCHIVE, c.archived() ? I18n.t("info.archived") : I18n.t("info.archive"), c.archived(), () -> svc.setArchived(c.id(), !c.archived())),
                action(Icons.LOW, c.lowPriority() ? I18n.t("info.toInbox") : I18n.t("info.toLowPriority"), c.lowPriority(),
                        () -> svc.setLowPriority(c.id(), !c.lowPriority())),
                action(Icons.SETTINGS, I18n.t("thread.chatOptions"), false, () -> {
                    if (onOptions != null) onOptions.accept(c);
                }));
        box.getChildren().addAll(top, ident, actions);

        if (c.reminderAt() != null) {
            Label rem = new Label(I18n.t("info.reminder", Formats.listStamp(c.reminderAt(), Instant.now(), ZoneId.systemDefault()),
                    Formats.time(c.reminderAt(), ZoneId.systemDefault())));
            rem.getStyleClass().add("info-reminder");
            box.getChildren().add(rem);
        }

        List<javafx.scene.Node> shared = media.section(c);
        if (!shared.isEmpty()) {
            box.getChildren().add(section(I18n.t("info.mediaTitle")));
            box.getChildren().addAll(shared);
        }

        if (c.group() || c.members().size() > 2) {
            box.getChildren().add(section(I18n.t("info.members", c.members().size())));
            VBox people = new VBox(2);
            for (Member m : c.members().stream().limit(100).toList()) {
                Avatar pa = new Avatar(30);
                pa.set(m.displayName(), m.id(), m.avatarUrl(), svc);
                enlargeable(pa, m.avatarUrl(), m.displayName());
                Label n = new Label(m.id().equals(svc.myUserId()) ? I18n.t("info.you", m.displayName()) : m.displayName());
                n.getStyleClass().add("member-name");
                HBox row = new HBox(10, pa, n);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("member-row");
                people.getChildren().add(row);
            }
            box.getChildren().add(people);
        }

    }

    /** Everything the panel draws from {@code c}, as one string to compare. */
    private String shown(Chat c) {
        StringBuilder b = new StringBuilder().append(c.id()).append('|').append(c.title()).append('|').append(c.avatarUrl())
                .append('|').append(c.network()).append('|').append(c.group()).append('|').append(c.topic())
                .append('|').append(c.muted()).append(c.favorite()).append(c.pinned()).append(c.archived()).append(c.lowPriority())
                .append('|').append(I18n.locale());
        if (c.reminderAt() != null) b.append('|').append(Formats.listStamp(c.reminderAt(), Instant.now(), ZoneId.systemDefault()))
                .append(c.reminderAt());
        b.append("|media ").append(media.shown(c));
        if (c.group() || c.members().size() > 2) {
            b.append("|members ").append(c.members().size());
            for (Member m : c.members().stream().limit(100).toList()) b.append(' ').append(m.id()).append(m.displayName()).append(m.avatarUrl());
        }
        return b.toString();
    }

    /**
     * A photo opens large in the chat's photo viewer (same controls: zoom, turn, full screen, save), by click, Enter or
     * Space. Initials alone have nothing to show.
     */
    private void enlargeable(Avatar a, String url, String name) {
        if (url == null || url.isBlank()) return;
        a.getStyleClass().add("avatar-enlargeable");
        a.setCursor(javafx.scene.Cursor.HAND);
        a.setFocusTraversable(true);
        a.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        a.setAccessibleText(I18n.t("info.avatarOpen", name));
        Runnable open = () -> {
            if (getScene() == null) return;
            javafx.stage.Window owner = getScene().getWindow();
            MediaViewer.show(owner, svc.media(url, 0, 0), name, () -> MediaSaver.save(owner, svc, url, name, toast));
        };
        a.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) open.run();
        });
        a.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                open.run();
                e.consume();
            }
        });
    }

    private Node section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("info-section");
        return l;
    }

    /** A round icon; its name in a tooltip and, with whether it's on, for screen readers. */
    private Node action(String icon, String text, boolean active, Runnable run) {
        Button b = new Button(null, Icons.icon(icon, 18));
        b.getStyleClass().add("info-action");
        if (active) b.getStyleClass().add("active");
        b.setOnAction(e -> run.run());
        b.setTooltip(new javafx.scene.control.Tooltip(active ? I18n.t("info.activated", text) : text));
        b.setAccessibleText(active ? I18n.t("info.activated", text) : text);
        return b;
    }

    /** The gear among the actions: the chat's own options ({@link ChatOptions}). */
    void setOnOptions(Consumer<Chat> r) {
        onOptions = r;
    }

}

package app.unichat.ui.chats;

import app.unichat.model.Chat;
import app.unichat.service.MediaSource;
import app.unichat.settings.Settings;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** A chat in a list to choose from (⌘K, forwarding): its picture with the network's badge, its name, a hint at the end. */
public final class ChatLine extends HBox {
    private final MediaSource media;
    private final Avatar avatar = new Avatar(26);
    private final Label name = new Label();
    private final Label hint = new Label();

    public ChatLine(MediaSource media) {
        super(10);
        this.media = media;
        name.getStyleClass().add("command-label");
        name.setMnemonicParsing(false); // a name from a network: "_" isn't a shortcut mark
        hint.getStyleClass().add("command-hint");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        getChildren().addAll(avatar, name, grow, hint);
        setAlignment(Pos.CENTER_LEFT);
    }

    public void show(Chat c, String hintText) {
        avatar.set(c.title(), c.id(), c.avatarUrl(), media);
        avatar.setNetwork(c.network(), Settings.get().showNetworkBadges());
        name.setText(c.title());
        hint.setText(hintText);
    }
}

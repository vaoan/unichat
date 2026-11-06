package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.function.Consumer;

/** On top of the window while an audio is loaded: it keeps playing as you change chats, and this controls it from anywhere. */
final class AudioBar extends HBox {
    private final AudioPlayback playback;
    private final Hyperlink who = new Hyperlink();

    AudioBar(AudioPlayback playback, Consumer<Chat> openChat) {
        super(10);
        this.playback = playback;
        getStyleClass().add("audio-bar");
        setAlignment(Pos.CENTER_LEFT);
        who.getStyleClass().add("audio-who");
        who.setMinWidth(Region.USE_PREF_SIZE);
        who.setMaxWidth(320);
        who.setOnAction(e -> {
            if (playback.chat() != null) openChat.accept(playback.chat());
        });
        AudioControls controls = new AudioControls(playback, playback::current, playback::togglePlay);
        controls.setMaxWidth(480);
        HBox.setHgrow(controls, Priority.ALWAYS);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.SOMETIMES);
        Button close = Composer.iconButton(Icons.CLOSE, "Cerrar el reproductor", playback::stop);
        getChildren().addAll(who, controls, grow, close);
        playback.addListener(this::refresh);
        refresh();
    }

    private void refresh() {
        Message m = playback.current();
        boolean showing = m != null;
        setVisible(showing);
        setManaged(showing);
        if (!showing) return;
        String sender = m.outgoing() ? "Tú" : m.senderName();
        Chat c = playback.chat();
        String title = c == null || c.title() == null || c.title().equals(sender) ? sender : sender + " · " + c.title();
        if (!title.equals(who.getText())) {
            who.setText(title);
            who.setAccessibleText("Audio de " + title + ", ir al chat");
        }
    }
}

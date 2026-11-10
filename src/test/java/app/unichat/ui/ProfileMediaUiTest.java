package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The profile's media: photos keep their shape, kinds in tabs, and a chat can hide it. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ProfileMediaUiTest {
    @Test
    void photosKeepTheirShapeInTabsAndAChatCanHideThem() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        String id = "!familia:demo";
        svc.setMediaHidden(id, false).get(); // the test data folder is the whole run's: start from a known state
        InfoPane pane = FxWait.fx(() -> {
            InfoPane p = new InfoPane(svc, () -> { }, t -> { });
            p.setAudio(new AudioPlayback(svc, t -> { }));
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(p, 360, 900));
            stage.show();
            p.show(svc.chat(id));
            return p;
        });
        FxWait.until("a photo loaded", () -> pane.lookup(".media-thumb") instanceof ImageView v && v.getImage() != null);
        ImageView v = FxWait.fx(() -> (ImageView) pane.lookup(".media-thumb"));
        assertTrue(FxWait.fx(v::isPreserveRatio), "never stretched to the square");
        double shownRatio = FxWait.fx(() -> v.getLayoutBounds().getWidth() / v.getLayoutBounds().getHeight());
        double ratio = FxWait.fx(() -> v.getImage().getWidth() / v.getImage().getHeight());
        assertEquals(ratio, shownRatio, 0.05, "the picture's own shape");
        List<String> tabs = FxWait.fx(() -> pane.lookupAll(".media-tab").stream().map(n -> ((ToggleButton) n).getAccessibleText()).toList());
        assertTrue(tabs.stream().anyMatch(t -> t.startsWith("Fotos (")), tabs.toString());
        // From the media to where it was shared: a file's button, a photo's menu.
        java.util.concurrent.atomic.AtomicReference<app.unichat.model.Message> jumped = new java.util.concurrent.atomic.AtomicReference<>();
        FxWait.fx(() -> { pane.setOnJump(jumped::set); return null; });
        javafx.scene.Node photoTile = FxWait.fx(() -> pane.lookup(".media-thumb-tile"));
        FxWait.fx(() -> {
            var p = photoTile.localToScreen(5, 5);
            javafx.event.Event.fireEvent(photoTile, new javafx.scene.input.ContextMenuEvent(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    5, 5, p.getX(), p.getY(), false, null));
            return null;
        });
        javafx.scene.control.ContextMenu menu = FxWait.fx(() -> javafx.stage.Window.getWindows().stream()
                .filter(w -> w instanceof javafx.scene.control.ContextMenu && w.isShowing()).map(w -> (javafx.scene.control.ContextMenu) w)
                .findFirst().orElseThrow());
        FxWait.fx(() -> { menu.getItems().get(0).fire(); menu.hide(); return null; });
        assertEquals(app.unichat.model.MessageType.IMAGE, jumped.get().type(), "the photo's message");
        // A voice note is on "Audios", not with the documents, and plays right there.
        ToggleButton audios = FxWait.fx(() -> pane.lookupAll(".media-tab").stream().map(n -> (ToggleButton) n)
                .filter(t -> t.getAccessibleText().startsWith("Audios")).findFirst().orElseThrow());
        FxWait.fx(() -> { audios.fire(); return null; });
        assertTrue(FxWait.fx(() -> pane.lookup(".profile-audio .audio-controls") != null), "its own player");
        javafx.scene.control.Button go = FxWait.fx(() -> pane.lookupAll(".button").stream().map(n -> (javafx.scene.control.Button) n)
                .filter(b -> "Ir al mensaje".equals(b.getAccessibleText())).findFirst().orElseThrow());
        FxWait.fx(() -> { go.fire(); return null; });
        assertEquals(app.unichat.model.MessageType.AUDIO, jumped.get().type(), "the voice note's message");

        svc.setMediaHidden(id, true).get();
        FxWait.fx(() -> { pane.refresh(); return null; });
        assertTrue(FxWait.fx(() -> pane.lookup(".media-thumb") == null), "hidden for this chat");
        svc.setMediaHidden(id, false).get(); // and leave it as found
        FxWait.fx(() -> { pane.getScene().getWindow().hide(); return null; });
        svc.stop();
    }

    /** The voice note playing pauses for a video opened from the info panel, as it does for one opened in the chat. */
    @Test
    void aVideoOpenedHerePausesTheVoiceNotePlaying(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        String id = "!familia:demo";
        svc.setMediaHidden(id, false).get();
        java.nio.file.Path clip = java.nio.file.Files.writeString(dir.resolve("clip.mp4"), "not really a video");
        svc.sendFile(id, clip, null, app.unichat.service.SendAs.AS_IS, p -> { });
        app.unichat.model.Message video = svc.messages(id).getLast();
        video.setType(app.unichat.model.MessageType.VIDEO);
        app.unichat.model.Message note = svc.messages(id).stream()
                .filter(m -> m.type() == app.unichat.model.MessageType.AUDIO && m.mediaUrl() != null).findFirst().orElseThrow();
        List<String> toasts = new java.util.concurrent.CopyOnWriteArrayList<>();
        AudioPlayback audio = FxWait.fx(() -> {
            AudioPlayback a = new AudioPlayback(svc, toasts::add);
            a.setVolume(0);
            return a;
        });
        InfoPane pane = FxWait.fx(() -> {
            InfoPane p = new InfoPane(svc, () -> { }, toasts::add);
            p.setAudio(audio);
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(p, 360, 900));
            stage.show();
            p.show(svc.chat(id));
            audio.toggle(note, svc.chat(id));
            return p;
        });
        FxWait.until("the voice note plays (or says it can't)", () -> !toasts.isEmpty() || audio.state() == AudioPlayback.State.PLAYING);
        assertEquals(List.of(), toasts, "the voice note didn't play");
        ToggleButton videos = FxWait.fx(() -> pane.lookupAll(".media-tab").stream().map(n -> (ToggleButton) n)
                .filter(t -> t.getAccessibleText().startsWith("Videos")).findFirst().orElseThrow());
        FxWait.fx(() -> { videos.fire(); return null; });
        javafx.scene.Node tile = FxWait.fx(() -> pane.lookupAll(".media-thumb-tile").stream()
                .filter(n -> app.unichat.support.I18n.t("media.videoOpen").equals(n.getAccessibleText())).findFirst().orElseThrow());
        List<javafx.stage.Window> before = FxWait.fx(() -> List.copyOf(javafx.stage.Window.getWindows()));
        FxWait.fx(() -> {
            javafx.event.Event.fireEvent(tile, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            return null;
        });
        FxWait.until("the voice note pauses for the video", () -> audio.state() == AudioPlayback.State.PAUSED);
        FxWait.fx(() -> {
            audio.stop();
            List.copyOf(javafx.stage.Window.getWindows()).stream().filter(w -> !before.contains(w)).forEach(javafx.stage.Window::hide);
            pane.getScene().getWindow().hide();
            return null;
        });
        svc.stop();
    }
}

package app.unichat.ui;

import app.unichat.TestPictures;
import app.unichat.model.MessageType;
import app.unichat.service.ScriptedChatService;
import app.unichat.settings.Settings;
import app.unichat.ui.chats.SidePanel;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sticker or picture shows its download as it really goes (a ring with the share that arrived), and one that couldn't
 * be downloaded offers to try again, in the chat and in the panel's sticker sets.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class MediaLoadingUiTest {
    private TimelineWindow w;

    @AfterEach
    void close() throws Exception {
        if (w != null) w.close();
    }

    private String sticker() throws Exception {
        Files.createDirectories(Settings.dataDir());
        Path p = Files.createTempFile(Settings.dataDir(), "sticker", ".png");
        return TestPictures.still(p, "png", 256, 256).toUri().toString();
    }

    private Node ring(Node in) {
        return in.lookup(".media-loading");
    }

    private Button retry(Node in) {
        return (Button) in.lookup(".media-retry");
    }

    /** The newest message's bubble. */
    private Node newestBubble() {
        javafx.scene.control.IndexedCell<?> last = w.flow().getLastVisibleCell();
        return last == null ? null : last.getGraphic();
    }

    /** {@code -Dunichat.loadingShot=<prefix>} saves what {@code n} looks like (a load is too short for a snapshot run). */
    private static void shot(Node n, String name) throws Exception {
        String prefix = System.getProperty("unichat.loadingShot");
        if (prefix == null) return;
        var img = FxWait.fx(() -> n.snapshot(null, null));
        javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(img, null), "png", new java.io.File(prefix + name + ".png"));
    }

    private boolean drawn(Node in) {
        return in.lookupAll("ImageView").stream().anyMatch(n -> n instanceof ImageView v && v.getImage() != null);
    }

    @Test
    void aStickerShowsHowMuchArrivedThenItsPicture() throws Exception {
        w = TimelineWindow.open(5);
        String url = sticker();
        ScriptedChatService.Gate downloads = w.svc.holdDownloads();
        w.svc.receive(CHAT, m -> {
            m.setType(MessageType.STICKER);
            m.setMediaUrl(url);
            m.setMimeType("image/png");
            m.setSize(256, 256);
            m.setBody("sticker");
        });
        FxWait.until("its ring shows", () -> w.showsNewest() && ring(newestBubble()) != null);
        assertEquals("Cargando…", FxWait.fx(() -> ring(newestBubble()).getAccessibleText()), "nothing known yet: it goes round");
        downloads.report(0.4);
        FxWait.until("the ring says 40 % (and a screen reader hears it)",
                () -> "Cargando, 40 %".equals(ring(newestBubble()).getAccessibleText()));
        shot(FxWait.fx(this::newestBubble), "ring");
        downloads.release();
        FxWait.until("the picture, and no ring", () -> drawn(newestBubble()) && ring(newestBubble()) == null);
    }

    @Test
    void aStickerThatCouldntBeDownloadedOffersToTryAgain() throws Exception {
        w = TimelineWindow.open(5);
        String url = sticker();
        ScriptedChatService.Gate downloads = w.svc.holdDownloads();
        w.svc.receive(CHAT, m -> {
            m.setType(MessageType.STICKER);
            m.setMediaUrl(url);
            m.setMimeType("image/png");
            m.setSize(256, 256);
            m.setBody("sticker");
        });
        FxWait.until("its ring shows", () -> w.showsNewest() && ring(newestBubble()) != null);
        downloads.fail();
        FxWait.until("a way to try again", () -> retry(newestBubble()) != null && ring(newestBubble()) == null);
        shot(FxWait.fx(this::newestBubble), "retry");
        int windows = FxWait.fx(() -> Window.getWindows().size());
        FxWait.fx(() -> {
            retry(newestBubble()).fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true,
                    false, true, null));
            return null;
        });
        FxWait.settled();
        assertEquals(windows, FxWait.fx(() -> Window.getWindows().size()), "a click on it opens nothing else (the viewer)");

        ScriptedChatService.Gate again = w.svc.holdDownloads();
        FxWait.fx(() -> { retry(newestBubble()).fire(); return null; });
        FxWait.until("loading again", () -> ring(newestBubble()) != null && retry(newestBubble()) == null);
        again.release();
        FxWait.until("the picture this time", () -> drawn(newestBubble()) && ring(newestBubble()) == null);
    }

    @Test
    void aStickerSetsTilesShowTheirLoadAndCanTryAgain() throws Exception {
        w = TimelineWindow.open(5);
        ScriptedChatService.Gate downloads = w.svc.holdDownloads();
        FxWait.fx(() -> { w.view.showPanel(SidePanel.Tab.STICKERS); return null; });
        FxWait.until("the demo's set is listed", () -> w.scene.getRoot().lookup(".sticker-set") != null);
        FxWait.fx(() -> { ((Button) w.scene.getRoot().lookup(".sticker-set")).fire(); return null; });
        FxWait.until("its tiles load", () -> w.scene.getRoot().lookupAll(".sticker-tile").stream().anyMatch(t -> ring(t) != null));
        downloads.fail();
        FxWait.until("each tile offers to try again", () -> w.scene.getRoot().lookupAll(".sticker-tile").stream()
                .filter(t -> retry(t) != null).count() >= 6);
        shot(FxWait.fx(() -> w.scene.getRoot().lookup(".sticker-set").getParent()), "set");
        ScriptedChatService.Gate again = w.svc.holdDownloads();
        Node tile = FxWait.fx(() -> w.scene.getRoot().lookupAll(".sticker-tile").stream().filter(t -> retry(t) != null).findFirst().orElseThrow());
        FxWait.fx(() -> { retry(tile).fire(); return null; });
        again.release();
        FxWait.until("that tile's sticker shows", () -> drawn(tile) && retry(tile) == null && ring(tile) == null);
    }
}

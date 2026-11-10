package app.unichat.ui.media;

import app.unichat.ui.FxWait;
import app.unichat.ui.chats.InfoPane;
import app.unichat.service.MockChatService;
import app.unichat.service.SendAs;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The profile's five media tabs together: equal shares of the bar, each one whole and inside it, at any width and text size. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ProfileMediaTabsUiTest {
    @TempDir
    Path dir;

    @Test
    void fiveTabsFitAtAnyWidthAndTextSize() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        String id = "!familia:demo"; // a photo and a voice note already
        // Counts as a real chat has them, two digits: one digit hid how a crowded bar cuts them.
        StringBuilder links = new StringBuilder("mira");
        for (int i = 0; i < 48; i++) links.append(" https://example.org/receta").append(i);
        svc.sendText(id, links.toString(), null).get();
        for (int i = 0; i < 37; i++) {
            svc.sendFile(id, Files.writeString(dir.resolve("contrato" + i + ".pdf"), "%PDF"), null, SendAs.DOCUMENT, null).get();
            svc.sendFile(id, Files.write(dir.resolve("paseo" + i + ".mp4"), new byte[16]), null, SendAs.AS_IS, null).get();
        }
        List<String> bad = new ArrayList<>();
        for (int px : new int[] {13, 16, 21}) {
            for (int width : new int[] {280, 340, 420}) {
                Stage[] stage = new Stage[1];
                InfoPane pane = FxWait.fx(() -> {
                    InfoPane p = new InfoPane(svc, () -> { }, t -> { });
                    stage[0] = new Stage();
                    stage[0].setOpacity(0);
                    Scene scene = new Scene(p, width, 900);
                    scene.getStylesheets().add(ProfileMediaTabsUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
                    scene.getRoot().setStyle("-fx-font-size: " + px + "px;");
                    stage[0].setScene(scene);
                    stage[0].show();
                    p.show(svc.chat(id));
                    return p;
                });
                FxWait.until("the tabs show", () -> pane.lookupAll(".media-tab").size() == 5);
                FxWait.settled();
                bad.addAll(FxWait.fx(() -> {
                    List<String> out = new ArrayList<>();
                    Node bar = pane.lookup(".media-tabs");
                    var barBounds = bar.localToScene(bar.getBoundsInLocal());
                    double widest = 0, narrowest = Double.MAX_VALUE;
                    for (Node n : pane.lookupAll(".media-tab")) {
                        widest = Math.max(widest, n.getLayoutBounds().getWidth());
                        narrowest = Math.min(narrowest, n.getLayoutBounds().getWidth());
                        ToggleButton t = (ToggleButton) n;
                        var b = t.localToScene(t.getBoundsInLocal());
                        if (t.isTextTruncated()) out.add(px + "px in " + width + ": " + t.getAccessibleText() + " cut");
                        if (b.getMaxX() > barBounds.getMaxX() + 0.5) out.add(px + "px in " + width + ": " + t.getAccessibleText() + " out of the bar");
                    }
                    if (widest - narrowest > 1) out.add(px + "px in " + width + ": uneven, " + Math.round(narrowest) + " to " + Math.round(widest) + " px");
                    return out;
                }));
                FxWait.fx(() -> { stage[0].close(); return null; });
            }
        }
        svc.stop();
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }
}

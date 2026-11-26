package app.unichat.ui.media;

import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MediaSource;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A picture sent as a file shows in its chip decoded for its square, not into a 152 px box. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class FilePreviewDecodeUiTest {
    @TempDir
    Path dir;

    @Test
    void aLandscapePictureIsDecodedJustBigEnoughForItsSquare() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        Path png = dir.resolve("wide.png");
        ImageIO.write(new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        MediaSource source = (url, w, h) -> CompletableFuture.completedFuture(png);
        Stage[] stage = new Stage[1];
        StackPane icon = FxWait.fx(() -> {
            stage[0] = new Stage();
            stage[0].setOpacity(0);
            stage[0].setScene(new Scene(new StackPane(), 400, 200));
            stage[0].show();
            MessageMedia media = new MessageMedia(source, s -> { }, s -> { },
                    new MediaLibrary(dir.resolve("media-library.json")), stage[0]::getScene,
                    new AudioPlayback(source, s -> { }));
            Message m = new Message("$f", "!c", "@a:s", "Ana", false, Instant.EPOCH);
            m.setType(MessageType.FILE);
            m.setMediaUrl("mxc://s/wide");
            m.setFileName("wide-" + System.nanoTime() + ".png");
            m.setMimeType("image/png");
            m.setFileSize(1000);
            Node chip = media.fileNode(m);
            ((StackPane) stage[0].getScene().getRoot()).getChildren().add(chip);
            return (StackPane) chip.lookup(".file-icon");
        });
        FxWait.until("the preview shows", () -> icon.getChildren().stream().anyMatch(n -> n instanceof ImageView));
        Image img = FxWait.fx(() -> ((ImageView) icon.getChildren().get(0)).getImage());
        double scale = ScreenScale.max();
        assertTrue(img.getWidth() <= Math.ceil(Math.ceil(38 * scale) * 1600 / 900.0), "width " + img.getWidth());
        assertTrue(img.getHeight() >= Math.ceil(38 * scale) - 1, "height " + img.getHeight());
        assertTrue(img.getHeight() <= Math.ceil(38 * scale) + 1, "height " + img.getHeight());
        FxWait.onFx(stage[0]::close);
    }
}

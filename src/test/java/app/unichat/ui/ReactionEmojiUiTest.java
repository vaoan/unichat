package app.unichat.ui;

import app.unichat.service.MediaSource;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A reaction shows an emoji, whatever it is made of; never the address of one. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ReactionEmojiUiTest {
    @TempDir Path dir;

    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c);
        Platform.runLater(t);
        return t.get(20, TimeUnit.SECONDS);
    }

    private Label shown(String key, MediaSource source) throws Exception {
        return fx(() -> {
            Label l = ReactionEmoji.of(key, source);
            Stage s = new Stage();
            s.setScene(new Scene(new StackPane(l), 100, 100));
            s.show();
            return l;
        });
    }

    private static void await(Callable<Boolean> c, String what) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (fx(c)) return;
            Thread.sleep(50);
        }
        assertTrue(fx(c), what);
    }

    @Test
    void aCommonEmojiIsItsText() throws Exception {
        Label l = shown("👍", (u, w, h) -> CompletableFuture.failedFuture(new AssertionError("nothing to download")));
        assertEquals("👍", fx(l::getText));
    }

    @Test
    void aCustomOneIsItsPicture() throws Exception {
        Path png = dir.resolve("e.png");
        assertTrue(javax.imageio.ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), "png", png.toFile()));
        Label l = shown("mxc://s/bien-" + System.nanoTime(), (u, w, h) -> CompletableFuture.completedFuture(png));
        await(() -> l.getGraphic() instanceof ImageView v && v.getImage() != null, "shows the picture");
        assertFalse(fx(l::getText).contains("mxc"));
    }

    @Test
    void oneThatCantBeShownIsStillNotItsAddress() throws Exception {
        Path notImage = Files.writeString(dir.resolve("e.tgs"), "un lottie comprimido");
        Label l = shown("mxc://s/roto-" + System.nanoTime(), (u, w, h) -> CompletableFuture.completedFuture(notImage));
        await(() -> ReactionEmoji.PLACEHOLDER.equals(l.getText()), "a placeholder");
        assertFalse(fx(l::getText).contains("mxc"));
    }
}

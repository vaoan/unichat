package app.unichat.ui.chats;

import app.unichat.service.MediaSource;
import app.unichat.ui.FxWait;
import app.unichat.ui.media.ImageCache;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Avatars take the tier of their size; a large view shows the small picture at once, then its own. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AvatarTierUiTest {
    @TempDir Path dir;

    @BeforeAll
    static void toolkit() throws Exception {
        CompletableFuture<Void> up = new CompletableFuture<>();
        try {
            Platform.startup(() -> up.complete(null));
        } catch (IllegalStateException running) {
            up.complete(null);
        }
        up.get();
        Platform.setImplicitExit(false);
    }

    private Path png() throws Exception {
        Path png = dir.resolve("a.png");
        assertTrue(javax.imageio.ImageIO.write(new BufferedImage(168, 168, BufferedImage.TYPE_INT_ARGB), "png", png.toFile()));
        return png;
    }

    private static ImageView photo(Avatar a) {
        return (ImageView) a.getChildren().stream().filter(n -> n instanceof ImageView).findFirst().orElseThrow();
    }

    @Test
    void aLargeViewShowsTheSmallPictureAtOnceThenTheLargeOne() throws Exception {
        String url = "mxc://s/tier-" + System.nanoTime();
        Path file = png();
        assertNotNull(ImageCache.load(url, (u, w, h) -> CompletableFuture.completedFuture(file), ImageCache.Tier.SMALL).get());
        Image small = ImageCache.get(url, ImageCache.Tier.SMALL);
        CompletableFuture<Path> download = new CompletableFuture<>();
        MediaSource slow = (u, w, h) -> download;

        Avatar avatar = FxWait.fx(() -> new Avatar(84));
        ImageView view = FxWait.fx(() -> photo(avatar));
        FxWait.onFx(() -> avatar.set("Ana", "!ana", url, slow));
        assertTrue(FxWait.fx(view::isVisible), "visible at once, no initials");
        assertSame(small, FxWait.fx(view::getImage), "the small picture meanwhile");

        download.complete(file);
        FxWait.until("the large picture replaces it", () -> view.getImage() != small);
        assertSame(ImageCache.get(url, ImageCache.Tier.LARGE), FxWait.fx(view::getImage));
        assertNotSame(small, FxWait.fx(view::getImage));
    }

    @Test
    void aListAvatarAsksTheSmallTier() throws Exception {
        String url = "mxc://s/list-" + System.nanoTime();
        Path file = png();
        List<String> asked = new CopyOnWriteArrayList<>();
        MediaSource svc = (u, w, h) -> {
            asked.add(u);
            return CompletableFuture.completedFuture(file);
        };
        Avatar avatar = FxWait.fx(() -> new Avatar(44));
        ImageView view = FxWait.fx(() -> photo(avatar));
        FxWait.onFx(() -> avatar.set("Ana", "!ana", url, svc));
        FxWait.until("the photo shows", view::isVisible);
        assertNotNull(ImageCache.get(url, ImageCache.Tier.SMALL));
        assertNull(ImageCache.get(url, ImageCache.Tier.LARGE), "only the small tier was decoded");
        assertEquals(List.of(url), asked);
    }
}

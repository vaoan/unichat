package app.unichat.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The demo drew a picture straight into its file, and a second caller that found the file there took it half written:
 * the info panel's avatar and the photo viewer ask for the same picture at once, and on Linux the viewer got the half
 * and showed "couldn't open" (InfoAvatarUiTest, every CI run after the test order changed).
 */
class DemoMediaTest {
    @Test
    void whoeverAsksAtTheSameTimeGetsTheWholePicture() throws Exception {
        for (int round = 0; round < 20; round++) {
            String name = "race-" + System.nanoTime();
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<Path>> asks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                asks.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                        return DemoMedia.demoImage(name);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            go.countDown();
            for (CompletableFuture<Path> ask : asks) {
                Path file = ask.get();
                assertNotNull(ImageIO.read(file.toFile()), "a whole picture, round " + round + ": " + file);
            }
        }
    }
}

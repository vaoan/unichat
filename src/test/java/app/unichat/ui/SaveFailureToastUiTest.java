package app.unichat.ui;

import app.unichat.service.MockChatService;
import app.unichat.service.PreferencesFile;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A preferences save that fails while running reaches the person as a toast. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SaveFailureToastUiTest {
    private static final String TEXT = "No se pudieron guardar tus preferencias. Lo que cambies puede perderse al cerrar.";

    @TempDir Path dir;

    @Test
    void aFailedSaveShowsAToast() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        var svc = new MockChatService(new PreferencesFile(dir.resolve("preferences.json"), dir.resolve("server-seen.json")));
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            Scene sc = new Scene(new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { }), 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(dir));
            svc.createFolder("Trabajo");
            long end = System.currentTimeMillis() + 5000;
            while (!TEXT.equals(fx(() -> toast(scene).getText()))) {
                if (System.currentTimeMillis() > end) fail("no toast: " + fx(() -> toast(scene).getText()));
                Thread.sleep(50);
            }
            assertEquals(TEXT, fx(() -> toast(scene).getAccessibleText()));
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            svc.stop();
            fx(() -> {
                ((MainView) scene.getRoot()).dispose();
                ((Stage) scene.getWindow()).close();
                return null;
            });
        }
    }

    private static Label toast(Scene scene) { return (Label) scene.getRoot().lookup(".toast"); }

    private static <T> T fx(Callable<T> call) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                done.complete(call.call());
            } catch (Exception | AssertionError e) {
                done.completeExceptionally(e);
            }
        });
        return done.get(10, TimeUnit.SECONDS);
    }
}

package app.unichat.ui;

import app.unichat.ui.desktop.Notifier;
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
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            started.await();
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
            FxWait.until("a toast saying the preferences weren't saved", () -> TEXT.equals(toast(scene).getText()));
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
        return FxWait.fx(call);
    }
}

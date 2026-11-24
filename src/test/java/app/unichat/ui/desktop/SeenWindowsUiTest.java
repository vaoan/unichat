package app.unichat.ui.desktop;

import app.unichat.service.ScriptedChatService;
import app.unichat.support.Seen;
import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether a Unichat window is on screen, as {@link Seen} hears it from {@link AppWindows}. Headless. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SeenWindowsUiTest {
    private final List<Stage> opened = new ArrayList<>();
    private Runnable stopWatching = () -> { };

    @BeforeAll
    static void toolkit() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
    }

    @AfterEach
    void close() throws Exception {
        FxWait.onFx(() -> {
            opened.forEach(Stage::close);
            stopWatching.run();
        });
        Seen.reset(true);
    }

    private Stage stage(String title) {
        Stage s = new Stage();
        s.setOpacity(0);
        s.setTitle(title);
        s.setScene(new Scene(new StackPane(), 200, 150));
        opened.add(s);
        return s;
    }

    /** Closes whatever other tests left showing, so only this test's windows count; then starts watching. */
    private void watchFromNothing() throws InterruptedException {
        FxWait.onFx(() -> {
            for (javafx.stage.Window w : new ArrayList<>(javafx.stage.Window.getWindows())) w.hide();
            stopWatching = AppWindows.watchShown(Seen.get()::windows);
        });
        assertFalse(Seen.get().now(), "nothing showing");
    }

    @Test
    void showingHidingAndMinimisingFollowsSeen() throws Exception {
        Stage main = FxWait.fx(() -> stage("main"));
        watchFromNothing();
        List<Boolean> got = new ArrayList<>();
        FxWait.onFx(() -> {
            main.show();
            got.add(Seen.get().now());
            main.hide();
            got.add(Seen.get().now());
            main.show();
            main.setIconified(true);
            got.add(Seen.get().now());
            main.setIconified(false);
            got.add(Seen.get().now());
        });
        assertEquals(List.of(true, false, false, true), got, "shown, hidden, minimised, restored");
    }

    @Test
    void aViewerShownWithTheMainWindowInTheTrayIsSeen() throws Exception {
        Stage main = FxWait.fx(() -> stage("main"));
        Stage photo = FxWait.fx(() -> stage("photo"));
        watchFromNothing();
        assertTrue(FxWait.fx(() -> {
            main.show();
            photo.show();
            main.hide();
            return Seen.get().now();
        }), "the photo viewer is on screen");
        assertFalse(FxWait.fx(() -> {
            photo.hide();
            return Seen.get().now();
        }), "and once it closes, nothing is");
    }

    @Test
    void aPopupDoesNotCount() throws Exception {
        Stage main = FxWait.fx(() -> stage("main"));
        watchFromNothing();
        assertFalse(FxWait.fx(() -> {
            main.show();
            ContextMenu menu = new ContextMenu(new MenuItem("x"));
            menu.show(main);
            main.setIconified(true);
            boolean now = Seen.get().now();
            menu.hide();
            return now;
        }), "a menu left over a minimised window isn't a window on screen");
    }

    @Test
    void aNotificationCardWithTheMainWindowInTheTrayIsNotSeen() throws Exception {
        Stage main = FxWait.fx(() -> stage("main"));
        watchFromNothing();
        DesktopToast toast = FxWait.fx(() -> new DesktopToast(id -> { }));
        try {
            assertFalse(FxWait.fx(() -> {
                main.show();
                main.hide();
                toast.show("Andrés", "¿Estás?", "!andres:demo");
                return Seen.get().now();
            }), "a card nobody looks at isn't someone seeing Unichat");
        } finally {
            FxWait.onFx(() -> {
                toast.hide();
            });
        }
    }

    @Test
    void aMessageWhileNotSeenStillNotifies() throws Exception {
        ScriptedChatService svc = new ScriptedChatService();
        Notifier notifier = FxWait.fx(() -> new Notifier(() -> { }, () -> { }, id -> { }, () -> { }));
        MainView view = FxWait.fx(() -> {
            Stage stage = stage("main");
            MainView v = new MainView(svc, stage, notifier, () -> { }, url -> { });
            stage.setScene(new Scene(v, 900, 600));
            stage.show();
            return v;
        });
        try {
            FxWait.until("the chat list shows", () -> !view.lookupAll(".chat-list .list-cell").isEmpty());
            FxWait.onFx(() -> {
                opened.get(0).hide();
            });
            Seen.reset(false);
            svc.receive("!andres:demo", "¿Estás?");
            FxWait.until("the notification posted", () -> "!andres:demo".equals(notifier.lastChatId()));
        } finally {
            FxWait.onFx(() -> {
                view.dispose();
                notifier.dispose();
            });
            svc.stop();
        }
    }
}

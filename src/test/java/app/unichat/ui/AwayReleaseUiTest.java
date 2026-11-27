package app.unichat.ui;

import app.unichat.Present;
import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import app.unichat.support.I18n;
import app.unichat.ui.chats.ChatFilter;
import app.unichat.ui.desktop.Notifier;
import app.unichat.ui.media.ImageCache;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closed to the menu bar a while, the window lets its view and pictures go (1.1 GB with the window shown, most of it what
 * it drew); notifications and the menu bar's count go on without it, and opening it builds it again behind a loading
 * screen, with the chat that was open.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AwayReleaseUiTest {
    private static final String FAMILY = "!family:demo";
    private static final String MOM = "!mom:demo";

    @TempDir Path dir;
    private final List<Stage> stages = new ArrayList<>(); // emptied at the end, so nothing of the test holds the window
    @SuppressWarnings("NullAway.Init") // open() sets it first, in every test
    private MockChatService svc;
    @SuppressWarnings("NullAway.Init") // open() sets it first, in every test
    private Notifier notifier;
    @SuppressWarnings("NullAway.Init") // open() sets it first, in every test
    private Scene scene;
    @SuppressWarnings("NullAway.Init") // open() sets it first, in every test
    private AwayRelease window;
    private @Nullable SessionAlerts alerts;

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

    @AfterEach
    void close() throws Exception {
        FxWait.onFx(() -> {
            SessionAlerts a = alerts;
            if (a != null) a.stop();
            window.clear();
            for (Stage s : stages) s.close();
            stages.clear();
        });
        svc.stop();
    }

    /** The window as UnichatApp builds it: its view through {@link AwayRelease}, its alerts beside it. */
    private void open(Duration after) throws InterruptedException {
        open(new MockChatService(), after);
    }

    private void open(MockChatService service, Duration after) throws InterruptedException {
        svc = service;
        FxWait.onFx(() -> {
            Stage stage = new Stage();
            stages.add(stage);
            stage.setOpacity(0);
            scene = new Scene(new StackPane(), 1000, 700);
            Theme.register(scene);
            stage.setScene(scene);
            notifier = new Notifier(() -> { }, () -> { }, id -> { }, () -> { });
            window = new AwayRelease(stage, scene, after);
            window.start(() -> new MainView(svc, stage, notifier, () -> { }, url -> { }));
            SessionAlerts a = new SessionAlerts(svc, stage, notifier, window::currentChatId);
            alerts = a;
            a.start();
            stage.show();
        });
        FxWait.settled();
    }

    private Stage stage() {
        return stages.getFirst();
    }

    private MainView view() throws InterruptedException {
        return Present.of(FxWait.fx(window::view));
    }

    private void hideAndLetGo() throws InterruptedException {
        int before = FxWait.fx(window::released);
        FxWait.onFx(stage()::hide);
        FxWait.until("the view let go", () -> window.released() == before + 1);
    }

    @Test
    @SuppressFBWarnings(value = "DM_GC", justification = "it measures what is kept after a collection (as its NOPMD says)")
    void aWindowHiddenLongEnoughLetsItsViewGo() throws Exception {
        open(Duration.ZERO);
        WeakReference<MainView> old = new WeakReference<>(view());
        // Changed right before it hides: the scene lists it among the nodes to draw, a frame that never comes while hidden.
        FxWait.onFx(() -> {
            view().setTranslateX(1);
            stage().hide();
        });
        FxWait.until("the view let go", () -> window.released() == 1);
        assertNull(FxWait.fx(window::view), "no view while let go");
        assertTrue(FxWait.fx(() -> !(scene.getRoot() instanceof MainView)), "the scene no longer holds it");
        FxWait.until("nothing holds the view let go", () -> {
            System.gc(); // NOPMD: DoNotCallGarbageCollectionExplicitly, it measures what is kept after a collection
            return old.get() == null;
        });
    }

    @Test
    void itsPicturesGoToo() throws Exception {
        open(Duration.ZERO);
        Path png = dir.resolve("a.png");
        assertTrue(javax.imageio.ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), "png", png.toFile()));
        String avatar = "mxc://s/away-" + System.nanoTime();
        assertNotNull(ImageCache.load(avatar, (u, w, h) -> CompletableFuture.completedFuture(png), ImageCache.Tier.SMALL).get());
        assertNotNull(ImageCache.any(avatar), "kept while the window is there");
        hideAndLetGo();
        assertNull(ImageCache.any(avatar), "let go with the window");
    }

    @Test
    void showingAgainBuildsItWithTheSameChatOpen() throws Exception {
        open(Duration.ZERO);
        MainView before = view();
        FxWait.onFx(() -> before.openChatId(FAMILY));
        FxWait.settled();
        hideAndLetGo();
        FxWait.onFx(() -> {
            stage().show();
            LoadingView loading = assertInstanceOf(LoadingView.class, scene.getRoot(), "a loading screen first, never a blank window");
            assertEquals(I18n.t("main.opening"), loading.text());
            assertEquals(I18n.t("main.opening"), loading.getAccessibleText(), "a screen reader says what it waits for");
        });
        FxWait.settled(); // the view is built once the loading screen was drawn
        MainView after = view();
        assertNotSame(before, after, "built again");
        assertSame(after, FxWait.fx(scene::getRoot));
        assertEquals(FAMILY, FxWait.fx(after::currentChatId));
    }

    /** As UnichatApp's openFromNotification: the window shown, then its chat once the view is there. */
    @Test
    void aNotificationClickWhileLetGoOpensItsChat() throws Exception {
        open(Duration.ZERO);
        FxWait.onFx(() -> view().openChatId(FAMILY));
        hideAndLetGo();
        FxWait.onFx(() -> {
            Platform.runLater(() -> {
                stage().show();
                stage().toFront();
            });
            Platform.runLater(() -> window.whenBuilt(v -> v.openChatId(MOM)));
            Platform.runLater(() -> assertInstanceOf(LoadingView.class, scene.getRoot(), "the loading screen while it's built"));
        });
        FxWait.settled();
        assertTrue(FxWait.fx(stage()::isShowing));
        assertEquals(MOM, FxWait.fx(() -> view().currentChatId()), "the notification's chat, not the one open before");
    }

    @Test
    void theMenuBarCountStillFollowsWhileLetGo() throws Exception {
        open(Duration.ZERO);
        hideAndLetGo();
        Chat read = svc.chats().stream().filter(c -> !c.isUnread() && !c.space() && !c.archived() && !c.muted()
                && !c.lowPriority() && !c.bridgeBot()).findFirst().orElseThrow(); // one marking unread counts
        int before = (int) svc.chats().stream().filter(ChatFilter::inboxUnread).count();
        FxWait.until("the count as it was", () -> notifier.unread() == before);
        svc.setMarkedUnread(read.id(), true).get();
        FxWait.until("the menu bar counts one more", () -> notifier.unread() == before + 1);
        FxWait.until("the title too", () -> ("Unichat (" + (before + 1) + ")").equals(stage().getTitle()));
    }

    @Test
    void hidingAndShowingQuicklyKeepsTheSameView() throws Exception {
        open(Duration.ofDays(1)); // never comes in the test: shown again long before
        MainView before = view();
        FxWait.onFx(stage()::hide);
        FxWait.settled();
        FxWait.onFx(stage()::show);
        FxWait.settled();
        assertSame(before, view());
        assertSame(before, FxWait.fx(scene::getRoot), "no loading screen, no flash");
        assertEquals(0, FxWait.fx(window::released));
    }

    @Test
    void nothingIsLetGoUnderAnOpenSettingsWindow() throws Exception {
        open(Duration.ZERO);
        MainView before = view();
        FxWait.onFx(before::showSettings);
        FxWait.onFx(stage()::hide);
        FxWait.until("it came due and was kept", () -> window.held() > 0);
        assertSame(before, FxWait.fx(window::view), "the settings come from the view: it stays");
        assertEquals(0, FxWait.fx(window::released));
        FxWait.onFx(() -> Present.of(app.unichat.ui.settings.SettingsWindow.current()).close());
        FxWait.until("let go once the settings are closed", () -> window.released() == 1);
    }

    @Test
    void nothingIsLetGoWhileAudioPlays() throws Exception {
        open(new MockChatService() {
            @Override public CompletableFuture<Path> media(@Nullable String url, int width, int height) {
                // The voice note never arrives: it stays loading, as one about to play.
                if (url != null && url.endsWith(".ogg")) return new CompletableFuture<>();
                return super.media(url, width, height);
            }
        }, Duration.ZERO);
        Message voice = svc.chats().stream().flatMap(c -> svc.messages(c.id()).stream())
                .filter(m -> String.valueOf(m.mediaUrl()).endsWith(".ogg")).findFirst().orElseThrow();
        FxWait.onFx(() -> view().audio().toggle(voice, null));
        FxWait.onFx(stage()::hide);
        FxWait.until("it came due and was kept", () -> window.held() > 0);
        assertEquals(0, FxWait.fx(window::released), "the audio isn't cut");
        FxWait.onFx(() -> view().audio().stop());
        FxWait.until("let go once the audio stopped", () -> window.released() == 1);
    }

    @Test
    void aDraftIsThereWhenTheWindowIsBuiltAgain() throws Exception {
        open(Duration.ZERO);
        FxWait.onFx(() -> {
            view().openChatId(FAMILY);
            view().thread().composer().insert("half written");
        });
        hideAndLetGo();
        FxWait.onFx(stage()::show);
        FxWait.settled();
        assertEquals("half written", FxWait.fx(() -> box().getText()));
    }

    private TextArea box() {
        return (TextArea) Present.of(scene.getRoot().lookup(".composer-row")).lookup(".text-area");
    }

    @Test
    void aLanguageChangeWhileLetGoBuildsTheNewLanguageOnShow() throws Exception {
        open(Duration.ZERO);
        FxWait.onFx(() -> view().openChatId(FAMILY)); // its message box says it
        hideAndLetGo();
        String spanish = I18n.t("composer.placeholder"); // tests run in Spanish
        try {
            FxWait.onFx(() -> {
                I18n.use("en");
                assertNull(window.rebuild(), "nothing to build while let go");
                assertNull(window.view(), "and nothing built hidden");
            });
            FxWait.onFx(stage()::show);
            FxWait.settled();
            String english = I18n.t("composer.placeholder");
            assertNotEquals(spanish, english);
            assertEquals(english, FxWait.fx(() -> box().getAccessibleText()), "the new view is in the new language");
        } finally {
            I18n.use(System.getProperty("unichat.language", "es"));
        }
    }

    @Test
    void aLogoutWhileLetGoBuildsNothingOnShow() throws Exception {
        open(Duration.ZERO);
        hideAndLetGo();
        StackPane login = new StackPane();
        FxWait.onFx(() -> {
            window.clear();
            scene.setRoot(login);
            stage().show();
        });
        FxWait.settled();
        assertNull(FxWait.fx(window::view));
        assertSame(login, FxWait.fx(scene::getRoot), "the login screen stays");
    }
}

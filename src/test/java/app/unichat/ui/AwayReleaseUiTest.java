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
    /** Times the app was told to log out (UnichatApp::logout). */
    private final java.util.concurrent.atomic.AtomicInteger logouts = new java.util.concurrent.atomic.AtomicInteger();
    /** Building the view throws from now on (a bug in it, the disk gone…). */
    private volatile boolean buildFails;

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
            window.start(() -> { // its logout counts too
                if (buildFails) throw new IllegalStateException("broken (test)");
                return new MainView(svc, stage, notifier, logouts::incrementAndGet, url -> { });
            });
            SessionAlerts a = new SessionAlerts(svc, stage, notifier, window::currentChatId, logouts::incrementAndGet);
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
    void theLastCleanupCollectsThenDrawsAnImageThenCollectsThenDrawsTheRoot() throws Exception {
        open(Duration.ZERO);
        hideAndLetGo();
        // JavaFX frees textures only in a frame or snapshot after the GC queued them (Disposer.cleanUp)
        FxWait.until("the cleanup ended", () -> window.cleanupSteps.size() == 4);
        assertEquals(List.of("gc", "image", "gc", "root"), List.copyOf(window.cleanupSteps));
    }

    @Test
    void showingTheWindowMidCleanupStopsTheRestOfIt() throws Exception {
        open(Duration.ZERO);
        FxWait.onFx(() -> window.collector = () -> Platform.runLater(stage()::show)); // queued before the step after the GC
        hideAndLetGo();
        FxWait.until("the first collection", () -> window.cleanupSteps.size() == 1);
        FxWait.settled(); // shown, the view built again
        FxWait.onFx(() -> { });
        assertEquals(List.of("gc"), List.copyOf(window.cleanupSteps), "no snapshot of a window that is back");
        assertInstanceOf(MainView.class, FxWait.fx(scene::getRoot));
    }

    @Test
    void anotherRootMidCleanupStopsTheRestOfIt() throws Exception {
        open(Duration.ZERO);
        StackPane other = new StackPane();
        FxWait.onFx(() -> window.collector = () -> Platform.runLater(() -> scene.setRoot(other))); // queued before the step after the GC
        hideAndLetGo();
        FxWait.until("the first collection", () -> window.cleanupSteps.size() == 1);
        FxWait.onFx(() -> { }); // behind the step after the GC
        assertEquals(List.of("gc"), List.copyOf(window.cleanupSteps), "no snapshot of a root that isn't the empty one");
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
            window.openNotified(MOM); // UnichatApp.openFromNotification's own step
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

    /** Revoked on another device: the server says so to the service, and the app logs out even with no view to tell. */
    @Test
    void aSessionEndedElsewhereWhileLetGoLogsOut() throws Exception {
        open(Duration.ZERO);
        hideAndLetGo();
        svc.logout().get(); // the demo's way to report LOGGED_OUT, as a server does for a session ended elsewhere
        FxWait.until("the app is told to log out", () -> logouts.get() == 1);
    }

    @Test
    void aSessionEndedElsewhereWithTheViewThereLogsOutOnce() throws Exception {
        open(Duration.ZERO);
        svc.logout().get();
        FxWait.until("the app is told to log out", () -> logouts.get() >= 1);
        FxWait.settled(); // the view has heard it too
        assertEquals(1, logouts.get(), "the view doesn't log out a second time");
    }

    /** Settings adopted from another computer while let go: what the whole app reads once follows at once. */
    @Test
    void aSettingAdoptedWhileLetGoAppliesAtOnce() throws Exception {
        open(Duration.ZERO);
        hideAndLetGo();
        boolean was = app.unichat.assist.AiSettings.enabled();
        try {
            app.unichat.assist.AiSettings.setEnabled(!was); // as the cloud writes it
            FxWait.onFx(window::settingsSynced);
            assertEquals(!was, FxWait.fx(app.unichat.ui.timeline.AiReplyButton.SHOWN::get), "applied now, not at the next opening");
            assertNull(FxWait.fx(window::view), "and no view built for it");
        } finally {
            app.unichat.assist.AiSettings.setEnabled(was);
            FxWait.onFx(() -> app.unichat.ui.timeline.AiReplyButton.SHOWN.set(was));
        }
    }

    /** A view built again shows what the cloud is doing on its gear, not nothing until the next change. */
    @Test
    void aViewBuiltAgainShowsWhatTheCloudIsDoing() throws Exception {
        open(Duration.ZERO);
        app.unichat.cloud.Cloud.Status up = new app.unichat.cloud.Cloud.Status(true, java.time.Instant.now(), null, 0, 9,
                new app.unichat.cloud.Cloud.Activity(app.unichat.cloud.Cloud.Step.UPLOADING, 2, 12, "foto.jpg", 450, 1000, 2_400_000),
                0, null, 0, null);
        FxWait.onFx(() -> window.cloudChanged(up)); // told while the view was there
        hideAndLetGo();
        FxWait.onFx(stage()::show);
        FxWait.settled();
        assertTrue(FxWait.fx(() -> view().lookup(".rail-cloud-ring").isVisible()), "the gear's ring, as the cloud last said");
    }

    // ------------------------------------------------------------------ a forward going on

    /** A forward still going when the view is let go: the view built again follows it, and the old one isn't kept for it. */
    @Test
    @SuppressFBWarnings(value = "DM_GC", justification = "it measures what is kept after a collection (as its NOPMD says)")
    void aForwardGoingOnIsFollowedByTheViewBuiltAgain() throws Exception {
        CompletableFuture<Void> going = new CompletableFuture<>(); // the forward never ends until the test says
        open(new MockChatService() {
            @Override public CompletableFuture<Void> forward(String chatId, Message message, boolean gifs) { return going; }
        }, Duration.ZERO);
        try {
            app.unichat.service.Forwarding.Job job = forwardOne();
            FxWait.until("it's going", () -> job.current() != null);
            WeakReference<MainView> old = new WeakReference<>(view());
            hideAndLetGo();
            FxWait.until("the view let go isn't kept by the forward going on", () -> {
                System.gc(); // NOPMD: DoNotCallGarbageCollectionExplicitly, it measures what is kept after a collection
                return old.get() == null;
            });
            FxWait.onFx(stage()::show);
            FxWait.settled();
            assertTrue(FxWait.fx(() -> forwardBar().isVisible()), "the bar built again shows the forward");
            assertTrue(FxWait.fx(() -> barSays().contains(I18n.t("forwardBar.count", 0, 1))), FxWait.fx(this::barSays));
            going.complete(null);
            FxWait.until("and follows it to its end", () -> barSays().contains(I18n.t("forwardBar.count", 1, 1)));
        } finally {
            going.complete(null);
        }
    }

    /** A forward that failed while nobody looked still says so when the window is built again, until it's closed. */
    @Test
    void aForwardThatFailedStillSaysSoAfterALetGo() throws Exception {
        open(new MockChatService() {
            @Override public CompletableFuture<Void> forward(String chatId, Message message, boolean gifs) {
                return CompletableFuture.failedFuture(new java.io.IOException("connection reset (test)"));
            }
        }, Duration.ZERO);
        forwardOne().done().get();
        hideAndLetGo();
        FxWait.onFx(stage()::show);
        FxWait.settled();
        assertTrue(FxWait.fx(() -> forwardBar().isVisible()), "the failure is still there to read");
        assertTrue(FxWait.fx(() -> barSays().startsWith(I18n.n("forwardBar.failed", 1, 0))), FxWait.fx(this::barSays));
    }

    /** One message of the family's to mom, followed by the view's bar as the forward sheet does. */
    private app.unichat.service.Forwarding.Job forwardOne() throws InterruptedException {
        List<Message> one = svc.messages(FAMILY).stream().filter(m -> m.type() == app.unichat.model.MessageType.TEXT).limit(1).toList();
        app.unichat.service.Forwarding.Job job = app.unichat.service.Forwarding.send(svc, one, List.of(MOM), m -> null, true);
        FxWait.onFx(() -> forwardBar().show(job));
        FxWait.settled();
        return job;
    }

    private app.unichat.ui.timeline.ForwardBar forwardBar() {
        return (app.unichat.ui.timeline.ForwardBar) Present.of(scene.getRoot().lookup(".forward-bar"));
    }

    /** What the bar says to a screen reader: what happened, how far, why. */
    private String barSays() {
        return String.valueOf(Present.of(forwardBar().lookup(".progress-bar")).getAccessibleText());
    }

    // ------------------------------------------------------------------ notifications while let go

    @Test
    void aMessageWhileLetGoNotifies() throws Exception {
        app.unichat.service.ScriptedChatService scripted = scripted(Duration.ZERO);
        String chat = notifying();
        hideAndLetGo();
        scripted.receive(chat, "¿Estás?");
        FxWait.until("the notification posted", () -> chat.equals(app.unichat.ui.desktop.NotifierAccess.lastChatId(notifier)));
        assertNull(FxWait.fx(window::view), "with no view built for it");
    }

    @Test
    void aReminderWhileLetGoNotifies() throws Exception {
        app.unichat.service.ScriptedChatService scripted = scripted(Duration.ZERO);
        String chat = notifying();
        hideAndLetGo();
        scripted.remindNow(chat).get();
        FxWait.until("the reminder posted", () -> chat.equals(app.unichat.ui.desktop.NotifierAccess.lastChatId(notifier)));
        assertNull(FxWait.fx(window::view), "with no view built for it");
    }

    /** The chat open and the window focused in front: what arrives there is being read, so no notification. */
    @Test
    void aMessageInTheChatBeingReadDoesntNotify() throws Exception {
        app.unichat.service.ScriptedChatService scripted = scripted(Duration.ofDays(1));
        String chat = notifying();
        FxWait.onFx(() -> {
            view().openChatId(chat);
            stage().requestFocus();
        });
        FxWait.settled();
        assertTrue(FxWait.fx(stage()::isFocused), "the window has the focus, as the person reading");
        scripted.receive(chat, "¿Estás?");
        FxWait.settled(); // the service's listener has handed it to the FX thread, which ran it
        assertNull(FxWait.fx(() -> app.unichat.ui.desktop.NotifierAccess.lastChatId(notifier)), "nothing posted for it");
    }

    /** The same chat with the window let go: nobody is reading it, so it notifies. */
    @Test
    void aMessageInTheChatThatWasOpenNotifiesOnceLetGo() throws Exception {
        app.unichat.service.ScriptedChatService scripted = scripted(Duration.ZERO);
        String chat = notifying();
        FxWait.onFx(() -> {
            view().openChatId(chat);
            stage().requestFocus();
        });
        FxWait.settled();
        hideAndLetGo();
        scripted.receive(chat, "¿Estás?");
        FxWait.until("the notification posted", () -> chat.equals(app.unichat.ui.desktop.NotifierAccess.lastChatId(notifier)));
    }

    /** The window over a demo where the test plays the others: nothing arrives unless it says so. */
    private app.unichat.service.ScriptedChatService scripted(Duration after) throws InterruptedException {
        app.unichat.service.ScriptedChatService s = new app.unichat.service.ScriptedChatService();
        open(s, after);
        return s;
    }

    /** A chat whose messages notify: not muted, archived nor a bridge's bot. */
    private String notifying() {
        return svc.chats().stream().filter(c -> !c.muted() && !c.archived() && !c.bridgeBot() && !c.space()
                && c.members().size() > 1).findFirst().orElseThrow().id();
    }

    // ------------------------------------------------------------------ what holds it, what doesn't

    /** A notification card (a window nobody looks at) and a popup over it don't keep the view, as a settings window does. */
    @Test
    void aNotificationCardOrAPopupDoesntHoldIt() throws Exception {
        open(Duration.ZERO);
        FxWait.onFx(() -> {
            Stage card = new Stage();
            stages.add(card);
            card.setOpacity(0);
            card.setScene(new Scene(new StackPane(), 200, 80));
            card.getProperties().put(app.unichat.ui.desktop.AppWindows.NOT_LOOKED_AT, true); // as DesktopToast marks it
            card.show();
            javafx.stage.Popup popup = new javafx.stage.Popup();
            popup.getContent().add(new StackPane());
            popup.show(card);
            assertTrue(popup.isShowing());
        });
        hideAndLetGo();
        assertEquals(0, FxWait.fx(window::held), "nothing kept it");
    }

    // ------------------------------------------------------------------ a build that fails

    @Test
    void aViewThatCantBeBuiltAgainSaysSoAndTheNextOpeningTriesAgain() throws Exception {
        open(Duration.ZERO);
        hideAndLetGo();
        buildFails = true;
        FxWait.onFx(stage()::show);
        FxWait.settled();
        String said = I18n.t("main.openFailed", "broken (test)");
        LoadingView loading = assertInstanceOf(LoadingView.class, FxWait.fx(scene::getRoot), "still the loading screen");
        assertEquals(said, FxWait.fx(loading::text), "it says what went wrong, not a wheel spinning for good");
        assertEquals(said, FxWait.fx(loading::getAccessibleText));
        buildFails = false;
        FxWait.onFx(stage()::hide);
        FxWait.onFx(stage()::show);
        FxWait.settled();
        assertSame(view(), FxWait.fx(scene::getRoot), "built on the next opening");
    }
}

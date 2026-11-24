package app.unichat.ui.timeline;

import app.unichat.service.MockChatService;
import app.unichat.service.SendAs;
import app.unichat.service.UploadQueue;
import app.unichat.support.Seen;
import app.unichat.ui.FxWait;
import app.unichat.ui.Showing;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.DoubleConsumer;

/**
 * While nobody can see Unichat the timeline's counters rest: the AI's "writing… N s" and the upload bar's countdown to
 * the next try. They count again once it's seen.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SeenTimelineAnimationsUiTest {
    @TempDir Path dir;
    private final List<Stage> stages = new ArrayList<>();
    private final List<Runnable> after = new ArrayList<>();
    @SuppressWarnings("NullAway.Init") // each test sets it before use
    private UploadBar bar;

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
        Seen.reset(true);
        after.forEach(Runnable::run);
        FxWait.onFx(() -> {
            if (bar != null) bar.dispose();
            stages.forEach(Stage::close);
            AiReplyButton.SHOWN.set(false);
        });
    }

    private void window(Parent root) throws InterruptedException {
        FxWait.onFx(() -> {
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(root, 700, 160));
            s.show();
            stages.add(s);
        });
    }

    /** Hides Unichat, then shows it again, checking {@code animating} each time. */
    private static void restsWhileUnseen(String what, java.util.concurrent.Callable<Boolean> animating) {
        FxWait.until(what + " counts while seen", animating);
        Seen.reset(false);
        FxWait.until(what + " rests while nobody can see it", () -> !animating.call());
        Seen.reset(true);
        FxWait.until(what + " counts again once seen", animating);
    }

    @Test
    void everyTimelineStopsWhenUnseenAndStartsWhenSeen() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        after.add(release::countDown);
        Composer composer = FxWait.fx(() -> {
            AiReplyButton.SHOWN.set(true);
            Composer c = new Composer(new MockChatService(), e -> { });
            c.setChat("!family:demo");
            return c;
        });
        window(composer);
        FxWait.onFx(() -> {
            ((TextArea) composer.lookup(".text-area")).setText("ola");
            composer.useFixWriter(() -> p -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            ((Button) composer.lookup(".ai-fix-button")).fire();
        });
        restsWhileUnseen("the AI's working line", () -> composer.lookupAll(".ai-status").stream().anyMatch(Showing::animating));

        // A file that can't go up waits for its next try, counting the seconds down.
        Path file = Files.writeString(dir.resolve("nota.txt"), "hola");
        MockChatService failing = new MockChatService() {
            @Override
            public CompletableFuture<Void> sendFile(String chatId, Path f, @Nullable String replyToId, SendAs as, DoubleConsumer progress) {
                return CompletableFuture.failedFuture(new java.io.IOException("sin red"));
            }
        };
        UploadQueue queue = UploadQueue.of(failing, null);
        after.add(queue::cancel);
        bar = FxWait.fx(() -> new UploadBar(queue, failing, id -> { }));
        window(bar);
        queue.addAll(List.of(new UploadQueue.Upload("!family:demo", file, null, SendAs.DOCUMENT)));
        FxWait.until("waiting for the next try", () -> queue.status().phase() == UploadQueue.Phase.RETRYING);
        restsWhileUnseen("the upload bar's countdown", () -> Showing.animating(bar));
    }

    @Test
    void aTextWaitingToBeCorrectedHasAWheelThatRestsWhileUnseen() throws Exception {
        SendQueue queue = new SendQueue(new MockChatService(), () -> new AiReplyButton.Writer() {
            @Override public @Nullable String write(String prompt) { return null; }
            @Override public void cancel() { /* the fake ignores it */ }
        }, said -> { }, never -> { }); // its correction never runs: it waits for good
        SendQueueView view = FxWait.fx(() -> new SendQueueView(queue, () -> "!family:demo"));
        after.add(() -> Platform.runLater(view::dispose));
        window(view);
        FxWait.onFx(() -> queue.submit("!family:demo", "ola", null));
        restsWhileUnseen("the waiting text's wheel", () -> view.lookupAll(".message-fixing-wheel").stream()
                .anyMatch(n -> ((javafx.scene.control.ProgressIndicator) n).isIndeterminate()));
    }
}

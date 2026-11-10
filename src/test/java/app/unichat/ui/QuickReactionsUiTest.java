package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import app.unichat.settings.Settings;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The quick reactions are the ones the person reacts with most; taking a reaction away doesn't count. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class QuickReactionsUiTest {
    @Test
    void theEmojiReactedWithMostComesFirst() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        // The data folder is the whole run's: no history in, none left for the next test.
        Path library = Settings.dataDir().resolve("media-library.json");
        Files.deleteIfExists(library);
        try {
            MockChatService svc = new MockChatService();
            Stage[] window = new Stage[1];
            ThreadPane thread = FxWait.fx(() -> {
                window[0] = new Stage();
                Stage stage = window[0];
                MainView view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
                stage.setScene(new Scene(view, 1100, 700));
                stage.show();
                view.openChatId("!familia:demo");
                return find(view);
            });
            MessageActionsUi actions = thread.actions();
            assertEquals(List.of(EmojiPicker.QUICK), FxWait.fx(actions::quick), "no history: the usual six");
            List<Message> msgs = svc.messages("!familia:demo");
            Message a = msgs.get(msgs.size() - 1), b = msgs.get(msgs.size() - 2);
            FxWait.fx(() -> {
                actions.react(a, "🔥"); // added: counts
                actions.react(a, "🔥"); // taken away: doesn't
                actions.react(b, "🫠");
                actions.react(b, "🫠");
                actions.react(a, "🫠"); // 🫠 added twice, 🔥 once
                return null;
            });
            List<String> quick = FxWait.fx(actions::quick);
            assertEquals(List.of("🫠", "🔥"), quick.subList(0, 2), "most first; a removal isn't a use");
            assertEquals(EmojiPicker.QUICK.length, quick.size(), "still six");
            svc.stop();
            FxWait.fx(() -> { window[0].close(); return null; });
        } finally {
            Files.deleteIfExists(library);
        }
    }

    private static ThreadPane find(Node n) {
        if (n instanceof ThreadPane t) return t;
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) {
            ThreadPane t = find(c);
            if (t != null) return t;
        }
        return null;
    }
}

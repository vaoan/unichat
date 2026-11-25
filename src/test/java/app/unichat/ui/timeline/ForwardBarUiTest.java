package app.unichat.ui.timeline;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.CantSend;
import app.unichat.service.Forwarding;
import app.unichat.service.MockChatService;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The forward bar, once done with failures, says why one couldn't go when it knows (a GIF no MP4 could be made of). */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ForwardBarUiTest {
    private static final String FROM = "!family:demo";
    private static final String TO = "!mom:demo";

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

    @Test
    void aGifThatCouldntBeMadeAnMp4SaysWhy() throws Exception {
        String why = I18n.t("gif.cantMakeMp4", "Telegram");
        List<String> shown = finishedBar(new CantSend(why));
        assertTrue(shown.contains(why), "the reason is on the bar: " + shown);
        assertTrue(shown.getLast().contains(why), "and read out with it: " + shown.getLast());
    }

    @Test
    void anyOtherFailureOnlySaysItFailed() throws Exception {
        String why = I18n.t("gif.cantMakeMp4", "Telegram");
        List<String> shown = finishedBar(new java.io.IOException("connection reset"));
        assertFalse(shown.stream().anyMatch(s -> s.contains(why) || s.contains("connection reset")), shown.toString());
    }

    /** A forward of one message that fails with {@code err}, shown once done: its labels' texts, then the bar's spoken text. */
    private static List<String> finishedBar(Exception err) throws InterruptedException, java.util.concurrent.ExecutionException {
        MockChatService svc = new MockChatService() {
            @Override public CompletableFuture<Void> forward(String chatId, Message message, boolean gifs) {
                return CompletableFuture.failedFuture(new CompletionException(err));
            }
        };
        svc.noAutoReplies();
        List<Message> one = svc.messages(FROM).stream().filter(m -> m.type() == MessageType.TEXT).limit(1).toList();
        Forwarding.Job job = Forwarding.send(svc, one, List.of(TO), m -> null, true);
        job.done().get();
        return FxWait.fx(() -> {
            ForwardBar bar = new ForwardBar(svc, id -> { });
            bar.show(job);
            List<String> texts = new java.util.ArrayList<>();
            for (Node n : bar.lookupAll(".label")) {
                if (n instanceof Labeled l && l.getText() != null) texts.add(l.getText());
            }
            texts.add(String.valueOf(bar.lookup(".progress-bar").getAccessibleText()));
            return texts;
        });
    }
}

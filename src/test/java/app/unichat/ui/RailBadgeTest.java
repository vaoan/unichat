package app.unichat.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for "the inbox says 1 but nothing is unread": the persistent inbox button kept an old badge.
 * Needs the JavaFX toolkit: {@code -Dunichat.uiTests=true}.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class RailBadgeTest {
    private static List<String> badges(Button b) {
        return b.getGraphic().lookupAll(".rail-badge").stream().map(n -> ((Label) n).getText()).toList();
    }

    @Test
    void badgeFollowsTheCountAcrossRefreshes() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Button inbox = new Button();
                inbox.setGraphic(new Label("icon"));
                Rail.setUnreadBadge(inbox, 1);
                assertEquals(List.of("1"), badges(inbox));
                Rail.setUnreadBadge(inbox, 3);
                assertEquals(List.of("3"), badges(inbox));
                Rail.setUnreadBadge(inbox, 0);
                assertEquals(List.of(), badges(inbox), "no stale badge once everything is read");
                Node g = inbox.getGraphic();
                Rail.setUnreadBadge(inbox, 0);
                assertEquals(g, inbox.getGraphic(), "the wrapper is reused, not nested again");
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }
}

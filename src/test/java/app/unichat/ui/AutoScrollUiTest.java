package app.unichat.ui;

import app.unichat.ui.desktop.Notifier;
import app.unichat.ui.timeline.ThreadRows;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The open chat follows the newest message: on open, when content grows late, and when you send. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AutoScrollUiTest {
    private static final String CHAT = "!andres:demo";
    private MockChatService svc;
    private MainView view;

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
    }

    /** A chat taller than the window but with fewer than 20 messages (so opening it also loads older ones). */
    private Scene start() throws Exception {
        return start(12);
    }

    private Scene start(int messages) throws Exception {
        svc = new MockChatService();
        for (int i = 0; i < messages; i++) {
            svc.sendText(CHAT, "Mensaje largo " + i + "\nsegunda línea\ntercera línea\ncuarta línea", null).get();
        }
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1000, 520);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        FxWait.until("the chat list shows", () -> !scene.getRoot().lookupAll(".chat-list .list-cell").isEmpty());
        fx(() -> { view.openChatId("!familia:demo"); return null; });
        FxWait.until("the first chat's timeline shows", () -> showsLast(scene, "!familia:demo"));
        FxWait.settled();
        fx(() -> { view.openChatId(CHAT); return null; });
        FxWait.until("the long chat's timeline shows", () -> showsLast(scene, CHAT));
        FxWait.settled(); // including ThreadPane's rewrap after the width settled, which rebuilds every bubble
        return scene;
    }

    /** The timeline lists {@code chatId}'s newest message. */
    private boolean showsLast(Scene scene, String chatId) {
        String last = svc.messages(chatId).getLast().id();
        return timeline(scene).getItems().stream()
                .anyMatch(r -> r instanceof ThreadRows.MessageRow mr && mr.message().id().equals(last));
    }

    @SuppressWarnings("unchecked")
    private static ListView<Object> timeline(Scene s) {
        return (ListView<Object>) s.getRoot().lookup(".timeline");
    }

    /** The last row is the last visible cell and its bottom edge is inside the viewport. */
    @Test
    void clickingTheQuoteOfAReplyGoesToTheOriginalAndStaysThere() throws Exception {
        Scene scene = start();
        String firstId = fx(() -> svc.messages(CHAT).get(0).id());
        svc.sendText(CHAT, "te respondo", firstId).get();
        FxWait.until("the sent message shows", () -> showsLast(scene, CHAT));
        endsAtBottom(scene);

        fx(() -> {
            // The demo chat has replies of its own: this one's is the lowest on screen.
            Node quote = timeline(scene).lookupAll(".reply-quote").stream()
                    .max(java.util.Comparator.comparingDouble(n -> n.localToScene(n.getBoundsInLocal()).getMinY())).orElseThrow();
            quote.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false, true, null));
            return null;
        });
        FxWait.until("the original is on screen", () -> onScreen(scene, firstId));
        FxWait.settled();
        assertTrue(!"bottom".equals(bottomState(scene)), "it stayed up: " + bottomState(scene));
        assertTrue(fx(() -> onScreen(scene, firstId)), "the original is on screen");
        assertTrue(fx(() -> scene.getRoot().lookup(".jump-down").isVisible()), "a way back down");
        svc.stop();
    }

    private static boolean onScreen(Scene scene, String id) {
        ListView<Object> list = timeline(scene);
        VirtualFlow<?> flow = (VirtualFlow<?>) list.lookup(".virtual-flow");
        for (int i = 0; i < list.getItems().size(); i++) {
            if (list.getItems().get(i) instanceof ThreadRows.MessageRow mr && mr.message().id().equals(id)) {
                return flow.getFirstVisibleCell().getIndex() <= i && i <= flow.getLastVisibleCell().getIndex();
            }
        }
        return false;
    }

    private static String bottomState(Scene s) throws Exception {
        return fx(() -> {
            ListView<Object> list = timeline(s);
            VirtualFlow<?> flow = (VirtualFlow<?>) list.lookup(".virtual-flow");
            IndexedCell<?> last = flow.getLastVisibleCell();
            int lastIndex = list.getItems().size() - 1;
            if (last == null) return "no cells";
            double bottom = last.localToScene(last.getBoundsInLocal()).getMaxY();
            double viewport = flow.localToScene(flow.getBoundsInLocal()).getMaxY();
            boolean ok = last.getIndex() == lastIndex && bottom <= viewport + 2;
            return ok ? "bottom" : "last visible " + last.getIndex() + "/" + lastIndex + ", bottom " + bottom + " vs " + viewport;
        });
    }

    @Test
    void openingAShortChatEndsAtTheNewestMessage() throws Exception {
        Scene scene = start();
        untilAtBottom(scene);
        FxWait.settled(); // and it stays there
        assertTrue(fx(() -> timeline(scene).lookupAll(".scroll-bar").stream()
                .anyMatch(n -> n instanceof ScrollBar sb && sb.isVisible() && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL)),
                "precondition: the chat is taller than the window");
        assertTrue("bottom".equals(bottomState(scene)), bottomState(scene));
        svc.stop();
    }

    @Test
    void contentThatGrowsAfterwardsKeepsTheBottomInView() throws Exception {
        Scene scene = start();
        // Like a photo or sticker finishing its download: the newest bubble gets taller after it was shown.
        Region grown = fx(() -> {
            VirtualFlow<?> flow = (VirtualFlow<?>) timeline(scene).lookup(".virtual-flow");
            IndexedCell<?> last = flow.getLastVisibleCell();
            Region r = (Region) last.getGraphic();
            r.setMinHeight(r.getHeight() + 220);
            return r;
        });
        FxWait.until("the bubble is laid out taller", () -> grown.getHeight() >= grown.getMinHeight());
        endsAtBottom(scene);
        svc.sendText(CHAT, "nuevo", null).get();
        FxWait.until("the sent message shows", () -> showsLast(scene, CHAT));
        endsAtBottom(scene);
        svc.stop();
    }

    /**
     * A chat opened before its embed's picture loaded (Discord's embeds, link previews: HTML pictures, size unknown):
     * the picture took its shape after the chat was at its newest message, and what it added stayed below the view.
     */
    @Test
    void anEmbedsPictureLoadingAfterTheChatOpenedKeepsTheBottomInView() throws Exception {
        svc = new MockChatService();
        for (int i = 0; i < 6; i++) svc.sendText(CHAT, "Mensaje " + i + "\nsegunda línea", null).get();
        svc.sendText(CHAT, "mira esto", null).get();
        svc.messages(CHAT).getLast().setHtml("<p>mira esto</p><img src=\"mxc://demo/embed-" + System.nanoTime() + "\">");
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1000, 520);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId(CHAT);
            return sc;
        });
        FxWait.until("the chat's timeline shows", () -> showsLast(scene, CHAT));
        FxWait.until("the embed's picture loaded", () -> scene.getRoot().lookupAll(".timeline ImageView").stream()
                .anyMatch(n -> n instanceof javafx.scene.image.ImageView v && v.getImage() != null));
        endsAtBottom(scene);
        svc.stop();
    }

    /**
     * The newest message grew taller than the window (a long embed loading): following the bottom showed its top and
     * left the rest below. Its end is what's in view.
     */
    @Test
    void aNewestMessageTallerThanTheWindowShowsItsEnd() throws Exception {
        Scene scene = start();
        Region grown = fx(() -> {
            VirtualFlow<?> flow = (VirtualFlow<?>) timeline(scene).lookup(".virtual-flow");
            Region r = (Region) flow.getLastVisibleCell().getGraphic();
            r.setMinHeight(flow.getHeight() + 400);
            return r;
        });
        FxWait.until("the bubble is laid out taller than the window", () -> grown.getHeight() >= grown.getMinHeight());
        endsAtBottom(scene);
        svc.stop();
    }

    /**
     * Reading older messages, a picture above loads and grows: what's being read stays where it was, as chat apps anchor on
     * the message in view and make up for the height that came above it.
     */
    @Test
    void readingOlderMessagesAPictureGrowingAboveDoesntMoveThem() throws Exception {
        Scene scene = start(30);
        for (int i = 0; i < 10; i++) {
            fx(() -> {
                javafx.scene.Node flow = timeline(scene).lookup(".virtual-flow");
                javafx.event.Event.fireEvent(flow, new javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL,
                        200, 200, 200, 200, false, false, false, false, false, false, 0, 120, 0, 120,
                        javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                        javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
                return null;
            });
            FxWait.pulses(1);
        }
        FxWait.until("the list stops following the bottom", () -> scene.getRoot().lookup(".jump-down").isVisible());
        FxWait.settled();
        // The first cell wholly in view is what's being read; the one above it grows.
        Object[] read = fx(() -> {
            VirtualFlow<?> flow = (VirtualFlow<?>) timeline(scene).lookup(".virtual-flow");
            IndexedCell<?> above = flow.getFirstVisibleCell();
            IndexedCell<?> reading = flow.getVisibleCell(above.getIndex() + 1);
            ((Region) above.getGraphic()).setMinHeight(above.getHeight() + 200);
            return new Object[] {reading.getItem(), reading.localToScene(0, 0).getY()};
        });
        FxWait.settled();
        double now = fx(() -> {
            VirtualFlow<?> flow = (VirtualFlow<?>) timeline(scene).lookup(".virtual-flow");
            for (int i = flow.getFirstVisibleCell().getIndex(); i <= flow.getLastVisibleCell().getIndex(); i++) {
                IndexedCell<?> c = flow.getVisibleCell(i);
                if (c != null && c.getItem() == read[0]) return c.localToScene(0, 0).getY();
            }
            return Double.NaN;
        });
        assertEquals((double) read[1], now, 2, "the message being read stayed put");
        svc.stop();
    }

    @Test
    void sendingWhileScrolledUpJumpsToYourMessage() throws Exception {
        Scene scene = start();
        // Scroll up with the wheel, as a person would (a programmatic scroll doesn't unpin the bottom).
        for (int i = 0; i < 8; i++) {
            fx(() -> {
                javafx.scene.Node flow = timeline(scene).lookup(".virtual-flow");
                javafx.event.Event.fireEvent(flow, new javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL,
                        200, 200, 200, 200, false, false, false, false, false, false, 0, 120, 0, 120,
                        javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                        javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
                return null;
            });
            FxWait.pulses(1); // the list lays out each turn of the wheel
        }
        FxWait.until("the list stops following the bottom", () -> scene.getRoot().lookup(".jump-down").isVisible());
        FxWait.settled();
        assertTrue(!"bottom".equals(bottomState(scene)), "precondition: scrolled up");
        svc.sendText(CHAT, "lo que acabo de escribir", null).get();
        FxWait.until("the sent message shows", () -> showsLast(scene, CHAT));
        endsAtBottom(scene);
        svc.stop();
    }

    /**
     * A small turn of the wheel up in a long chat stays where it is: the list used to count itself "at the bottom"
     * within 0.2 % of the whole chat (hundreds of pixels in a long one) and pulled straight back down.
     */
    @Test
    void aSmallScrollUpInALongChatIsNotUndone() throws Exception {
        Scene scene = start(200);
        untilAtBottom(scene);
        fx(() -> {
            javafx.scene.Node flow = timeline(scene).lookup(".virtual-flow");
            javafx.event.Event.fireEvent(flow, new javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL,
                    200, 200, 200, 200, false, false, false, false, false, false, 0, 30, 0, 30,
                    javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            return null;
        });
        FxWait.settled();
        FxWait.pulses(10); // the frames in which it used to be pulled back
        assertTrue(!"bottom".equals(bottomState(scene)), "stays scrolled up: " + bottomState(scene));
        assertTrue(fx(() -> scene.getRoot().lookup(".jump-down").isVisible()), "and offers the way back down");
        svc.stop();
    }

    /**
     * Where it ends up once the window has done all it was asked: a growing bubble is caught up with on the frame after
     * it grew, so checking at one moment right after could see that frame (it did with JavaFX 27).
     */
    private static void endsAtBottom(Scene scene) throws Exception {
        untilAtBottom(scene);
        FxWait.settled();
        untilAtBottom(scene);
    }

    /** The list settles over several layout passes. */
    private static void untilAtBottom(Scene scene) throws Exception {
        FxWait.until("the newest message is fully in view", () -> "bottom".equals(bottomState(scene)));
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }
}

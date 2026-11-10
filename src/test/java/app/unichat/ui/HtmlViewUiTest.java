package app.unichat.ui;

import app.unichat.model.LinkMedia;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A message's HTML in the timeline, drawn by its tags; and the players for what its links hold. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class HtmlViewUiTest {
    private static final String CHAT = "!andres:demo";
    private static final String EMBED = "https://fixupx.com/a/1\n<blockquote class=\"discord-embed\" background-color=\"#6364FF\">"
            + "<p class=\"discord-embed-description\"><p>Hola <strong>mundo</strong><br>"
            + "<a href=\"https://x.com/hashtag/b\">#b</a></p></p>"
            + "<p class=\"discord-embed-image\"><img src=\"mxc://demo/foto\" alt=\"\"></p></blockquote>"
            + "<h1>Título</h1><ul><li>uno</li></ul><table><tr><th>A</th></tr><tr><td>celda</td></tr></table>"
            + "<script>alert(1)</script><iframe src=\"https://malo.example/\"></iframe>"
            + "<img src=\"https://rastreo.example/a.png\" alt=\"sin cargar\">"
            + "<span data-mx-spoiler=\"\">secreto</span>";

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

    private static <T> T fx(Callable<T> c) throws Exception {
        return FxWait.fx(c);
    }

    private static String texts(Node root) {
        List<String> out = new ArrayList<>();
        root.lookupAll(".msg-text").forEach(n -> out.add(((Text) n).getText()));
        return String.join("", out);
    }

    private Node bubbleWith(Scene scene, String text) {
        return scene.getRoot().lookupAll(".bubble").stream().filter(n -> shown(n) && texts(n).contains(text)).findFirst().orElseThrow();
    }

    /** A list keeps spare cells that are hidden, and they can still hold an earlier drawing of the same message. */
    private static boolean shown(Node n) {
        for (Node p = n; p != null; p = p.getParent()) if (!p.isVisible()) return false;
        return true;
    }

    /** Links are opened on another thread, and the row can be drawn again before they answer. */
    private boolean chipShown(Scene scene) {
        return scene.getRoot().lookupAll(".bubble").stream().filter(n -> shown(n) && texts(n).contains("Hola "))
                .findFirst().map(b -> !b.lookupAll(".file-chip").isEmpty()).orElse(false);
    }

    private static int depthOf(Node n) {
        int deepest = 0;
        if (n instanceof javafx.scene.Parent p) for (Node c : p.getChildrenUnmodifiable()) deepest = Math.max(deepest, depthOf(c));
        return deepest + 1;
    }

    /** A stranger's message fits ten thousand nested tags; walking them one call each would overflow the UI thread's stack. */
    @Test
    void aDeeplyNestedMessageIsDrawnAsPlainTextPastTheLimit() throws Exception {
        for (String tag : List.of("b", "div", "blockquote")) {
            String html = ("<" + tag + ">").repeat(10_000) + "hondo";
            Node drawn = fx(() -> HtmlView.of(html, 400, null, url -> { }));
            assertTrue(texts(drawn).contains("hondo"), tag);
            assertTrue(fx(() -> depthOf(drawn)) < 200, tag + ": " + fx(() -> depthOf(drawn)) + " levels on screen");
        }
    }

    @Test
    void itDrawsWhatTheHtmlSaysAndLetsThroughNothingThatActsOnItsOwn() throws Exception {
        MockChatService svc = new MockChatService();
        String audioLink = "https://a.example/cancion";
        String videoLink = "https://a.example/pelicula";
        svc.addLinkMedia("https://fixupx.com/a/1", new LinkMedia(LinkMedia.Kind.AUDIO, audioLink, "audio/mpeg", 10));
        svc.addLinkMedia("https://x.com/hashtag/b", new LinkMedia(LinkMedia.Kind.VIDEO, videoLink, "video/mp4", 50_000_000));
        svc.sendText(CHAT, "cuerpo", null).get();
        fx(() -> {
            var ms = svc.messages(CHAT);
            ms.get(ms.size() - 1).setHtml(EMBED);
            return null;
        });
        Scene scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            MainView view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1000, 1000);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            view.openChatId(CHAT);
            return sc;
        });
        // Checked in the same turn that sees the chip: the row can be drawn again at any moment (the list settling its
        // width), and a new drawing has no chip until its links answer, a turn later.
        // A failed check is kept and thrown here: FxWait.until would take it for "not yet" and wait on.
        Throwable[] failed = {null};
        FxWait.until("the chip showed", () -> {
            if (!chipShown(scene)) return false;
            try {
                checkBubble(scene);
            } catch (AssertionError | RuntimeException e) {
                failed[0] = e;
            }
            return true;
        });
        if (failed[0] instanceof AssertionError e) throw e;
        if (failed[0] != null) throw new AssertionError(failed[0]);
    }

    private void checkBubble(Scene scene) {
        Node bubble = bubbleWith(scene, "Hola ");
        String all = texts(bubble);
        for (String shown : List.of("mundo", "Título", "uno", "celda", "sin cargar")) assertTrue(all.contains(shown), shown + " in: " + all);
        for (String gone : List.of("alert", "malo", "secreto")) assertFalse(all.contains(gone), gone + " in: " + all);
        assertEquals(1, bubble.lookupAll(".html-quote").size());
        assertEquals(1, bubble.lookupAll(".media-box").size(), "the picture from our server, and only that");

        assertEquals(1, bubble.lookupAll(".file-chip").size(), "a long video waits in a chip");
        assertTrue(bubble.lookupAll(".file-name").stream().anyMatch(n -> ((Label) n).getText().equals("pelicula")));
        assertTrue(bubble.lookupAll(".hover-actions").isEmpty());
        assertTrue(scene.getRoot().lookupAll(".hover-actions").stream().allMatch(a -> a.getLayoutBounds().getHeight() < 60), "the actions don't stretch");

        Hyperlink veil = (Hyperlink) bubble.lookup(".html-spoiler");
        veil.fire();
        assertTrue(texts(bubble).contains("secreto"), "shown when asked for");
    }
}

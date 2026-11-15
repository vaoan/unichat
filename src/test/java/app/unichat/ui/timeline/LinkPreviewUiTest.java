package app.unichat.ui.timeline;

import app.unichat.model.LinkMedia;
import app.unichat.model.LinkPreview;
import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.Theme;
import app.unichat.ui.desktop.Notifier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A link's card under its message, as Telegram draws one: a link sent from Unichat to Telegram (Seth Miski's chat,
 * 2026-11-14) came back with no preview at all, since the bridge never hands one's own message the preview Telegram's
 * server made, and a GIF post on X never moved.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class LinkPreviewUiTest {
    private static final String CHAT = "!andres:demo";
    private static final String POST = "https://fixupx.com/montelobo_/status/2121725725623628253";

    @BeforeAll
    static void fx() throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
        } catch (IllegalStateException already) {
            up.countDown();
        }
        up.await();
    }

    private static String texts(Node root) {
        List<String> out = new ArrayList<>();
        walk(root, n -> {
            if (n instanceof Text t) out.add(t.getText());
            else if (n instanceof Labeled l) out.add(l.getText());
        });
        return String.join(" ", out);
    }

    private static void walk(Node n, java.util.function.Consumer<Node> each) {
        each.accept(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) walk(c, each);
    }

    private static boolean shown(Node n) {
        for (Node p = n; p != null; p = p.getParent()) if (!p.isVisible()) return false;
        return true;
    }

    private static Node cardIn(Scene scene, String text) {
        return scene.getRoot().lookupAll(".link-preview").stream().filter(n -> shown(n) && texts(n).contains(text)).findFirst().orElse(null);
    }

    private static Scene open(MockChatService svc) throws Exception {
        return FxWait.fx(() -> {
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
    }

    @Test
    void aLinkNoBridgeDescribedGetsItsCardAndItsGifPlays() throws Exception {
        MockChatService svc = new MockChatService();
        svc.addLinkPreview(POST, new LinkPreview(POST, "X", "Montelobo (@Montelobo_)", "Lo que he hecho en Cancún", null, 800, 866,
                new LinkMedia(LinkMedia.Kind.VIDEO, "https://video.twimg.com/tweet_video/a.mp4", "video/mp4", 0), true));
        svc.sendText(CHAT, "mira " + POST, null).get();
        Scene scene = open(svc);
        // Read in the same turn that finds it: the row can be drawn again at any moment.
        String[] seen = new String[1];
        boolean[] moving = new boolean[1];
        FxWait.until("the post's card", () -> {
            Node card = cardIn(scene, "Montelobo (@Montelobo_)");
            if (card == null) return false;
            seen[0] = texts(card);
            moving[0] = !card.lookupAll(".media-box").isEmpty();
            return true;
        });
        assertTrue(seen[0].startsWith("X "), "the site, on top as Telegram writes it: " + seen[0]);
        assertTrue(seen[0].contains("Lo que he hecho en Cancún"), seen[0]);
        assertTrue(moving[0], "the GIF, in the card, playing by itself");
    }

    @Test
    void aBridgesPreviewIsTheSameCardWithItsPicture() throws Exception {
        MockChatService svc = new MockChatService();
        svc.sendText(CHAT, "https://a.example/nota", null).get();
        FxWait.fx(() -> {
            var ms = svc.messages(CHAT);
            ms.get(ms.size() - 1).setLinkPreviews(List.of(new LinkPreview("https://a.example/nota", "Ejemplo", "Una nota", "Su texto",
                    "mxc://demo/foto", 1200, 630, null, false)));
            return null;
        });
        Scene scene = open(svc);
        String[] seen = new String[1];
        boolean[] picture = new boolean[1];
        FxWait.until("the bridge's card", () -> {
            Node card = cardIn(scene, "Una nota");
            if (card == null) return false;
            seen[0] = texts(card);
            picture[0] = !card.lookupAll(".media-box").isEmpty();
            return true;
        });
        assertTrue(seen[0].startsWith("Ejemplo "), seen[0]);
        assertTrue(picture[0], "its picture, as wide as the card");
    }
}

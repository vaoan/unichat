package app.unichat.ui.chats;

import app.unichat.model.Network;
import app.unichat.service.OlderChats.Answer;
import app.unichat.service.OlderChats.Loading;
import app.unichat.service.OlderChats.Stage;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bar on top while older chats load: the network, its stage and N of M, then each network's answer for a moment.
 * A screen reader hears it all, and nothing is cut at 160 % nor with a wide font.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class OlderChatsBarUiTest {
    private final AtomicReference<@Nullable Loading> state = new AtomicReference<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** The bar's "a few seconds" go by when the test says. */
    private final List<Runnable> later = new CopyOnWriteArrayList<>();
    private final List<javafx.stage.Stage> stages = new ArrayList<>();

    @BeforeAll
    static void toolkit() throws InterruptedException {
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
    void close() throws InterruptedException {
        FxWait.onFx(() -> stages.forEach(javafx.stage.Stage::close));
    }

    private OlderChatsBar show(double px, String font) throws InterruptedException {
        OlderChatsBar bar = FxWait.fx(() -> {
            OlderChatsBar b = new OlderChatsBar(state::get, listeners::add, listeners::remove, later::add);
            VBox root = new VBox(b);
            root.getStyleClass().add("root");
            root.setStyle("-fx-font-size: " + px + "px; -fx-font-family: '" + font + "';");
            Scene scene = new Scene(root, 900, 300);
            scene.getStylesheets().add(OlderChatsBarUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setOpacity(0);
            stage.setScene(scene);
            stage.show();
            stages.add(stage);
            return b;
        });
        FxWait.settled();
        return bar;
    }

    private OlderChatsBar show() throws InterruptedException {
        return show(13, "System");
    }

    private void set(Loading l) throws InterruptedException {
        state.set(l);
        listeners.forEach(Runnable::run);
        FxWait.settled();
    }

    private static Loading at(Stage stage, int newChats) {
        return new Loading(Network.TELEGRAM, stage, newChats, 1, 3, Map.of());
    }

    @Test
    void showsTheNetworkTheStageAndNOfM() throws Exception {
        OlderChatsBar bar = show();
        assertFalse(FxWait.fx(bar::isVisible), "nothing running: no bar");
        set(at(Stage.RESTARTING, 0));
        assertTrue(FxWait.fx(bar::isVisible));
        List<String> shown = FxWait.fx(() -> texts(bar));
        assertTrue(shown.contains(I18n.t("olderChats.bar.loading", "Telegram")), shown.toString());
        assertTrue(shown.contains(I18n.t("olderChats.bar.restarting")), shown.toString());
        assertTrue(shown.contains(I18n.t("olderChats.bar.count", 2, 3)), "the second of three networks: " + shown);
        set(at(Stage.ASKING, 0));
        assertTrue(FxWait.fx(() -> texts(bar)).contains(I18n.t("olderChats.bar.asking")));
        set(at(Stage.ARRIVING, 1));
        assertTrue(FxWait.fx(() -> texts(bar)).contains(I18n.n("olderChats.bar.arriving", 1)));
    }

    @Test
    void countsChatsArriving() throws Exception {
        OlderChatsBar bar = show();
        set(at(Stage.ARRIVING, 37));
        assertTrue(FxWait.fx(() -> texts(bar)).contains("Llegando chats: 37 nuevos"), FxWait.fx(() -> texts(bar)).toString());
    }

    @Test
    void saysTheResultThenHides() throws Exception {
        OlderChatsBar bar = show();
        set(at(Stage.ARRIVING, 30));
        Map<Network, Answer> answers = new LinkedHashMap<>();
        answers.put(Network.TELEGRAM, new Answer.Loaded(30));
        answers.put(Network.DISCORD, new Answer.Loaded(7));
        answers.put(Network.WHATSAPP, new Answer.NothingMore());
        set(new Loading(Network.WHATSAPP, Stage.DONE, 0, 3, 3, answers));
        String said = String.join(" ", FxWait.fx(() -> texts(bar)));
        assertEquals("37 chats nuevos: Telegram 30, Discord 7. WhatsApp: nada más.", said);
        assertTrue(FxWait.fx(bar::isVisible), "said for a moment");
        assertEquals(1, later.size());
        FxWait.onFx(() -> later.getFirst().run());
        assertFalse(FxWait.fx(bar::isVisible), "then it goes");
    }

    @Test
    void saysWhatWasNotPossible() throws Exception {
        OlderChatsBar bar = show();
        set(at(Stage.RESTARTING, 0));
        String reason = I18n.t("olderChats.didntComeBack");
        set(new Loading(Network.INSTAGRAM, Stage.DONE, 0, 1, 1, Map.of(Network.INSTAGRAM, new Answer.NotPossible(reason))));
        String said = String.join(" ", FxWait.fx(() -> texts(bar)));
        assertTrue(said.contains(I18n.t("olderChats.bar.notPossible", "Instagram", reason)), said);
        assertTrue(said.contains(I18n.t("olderChats.bar.noneNew")), said);
    }

    @Test
    void aRunThatOnlySaysDoneStillShowsItsResult() throws Exception {
        // The loader can go straight to DONE (a network whose account dropped before Load: "not possible"), or tell a
        // stage and DONE within one frame: the bar never sees it running, and the result is all there is to say.
        OlderChatsBar bar = show();
        String reason = I18n.t("olderChats.noAccount", "Telegram");
        set(new Loading(Network.TELEGRAM, Stage.DONE, 0, 1, 1, Map.of(Network.TELEGRAM, new Answer.NotPossible(reason))));
        assertTrue(FxWait.fx(bar::isVisible), "the result shows");
        assertTrue(String.join(" ", FxWait.fx(() -> texts(bar))).contains(reason));
        FxWait.onFx(() -> later.getFirst().run());
        assertFalse(FxWait.fx(bar::isVisible), "then it goes");
        listeners.forEach(Runnable::run);
        FxWait.settled();
        assertFalse(FxWait.fx(bar::isVisible), "and the same result isn't said twice");
    }

    @Test
    void aNewRunAfterAnOldOneShowsItsResult() throws Exception {
        state.set(new Loading(Network.TELEGRAM, Stage.DONE, 0, 1, 1, Map.of(Network.TELEGRAM, new Answer.Loaded(3))));
        OlderChatsBar bar = show(); // the last run's end, from before the window was built
        set(new Loading(Network.DISCORD, Stage.DONE, 0, 1, 1, Map.of(Network.DISCORD, new Answer.Loaded(7))));
        assertTrue(FxWait.fx(bar::isVisible), "a new run's end shows");
        assertTrue(String.join(" ", FxWait.fx(() -> texts(bar))).contains("Discord 7"));
    }

    @Test
    void doneBeforeTheBarWasMadeIsNotShown() throws Exception {
        state.set(new Loading(Network.TELEGRAM, Stage.DONE, 0, 1, 1, Map.of(Network.TELEGRAM, new Answer.Loaded(3))));
        OlderChatsBar bar = show(); // a run that ended before a language change rebuilt the window
        assertFalse(FxWait.fx(bar::isVisible));
    }

    @Test
    void focusableWithItsWholeText() throws Exception {
        OlderChatsBar bar = show();
        set(at(Stage.ARRIVING, 37));
        assertTrue(FxWait.fx(bar::isFocusTraversable), "reachable with Tab");
        String heard = FxWait.fx(bar::getAccessibleText);
        for (String line : FxWait.fx(() -> texts(bar))) assertTrue(heard.contains(line), "a screen reader hears " + line + ": " + heard);
        FxWait.onFx(bar::dispose);
        assertTrue(listeners.isEmpty(), "a bar gone stops listening");
    }

    @Test
    void nothingCutAt160PercentNorWithAWideFont() throws Exception {
        Map<Network, Answer> answers = new LinkedHashMap<>();
        answers.put(Network.TELEGRAM, new Answer.Loaded(300));
        answers.put(Network.GOOGLE_MESSAGES, new Answer.NothingMore());
        answers.put(Network.INSTAGRAM, new Answer.NotPossible(I18n.t("olderChats.didntComeBack")));
        for (String font : List.of("System", "Monospaced")) for (double px : List.of(13.0, 21.0)) {
            state.set(new Loading(Network.GOOGLE_MESSAGES, Stage.ARRIVING, 1234, 4, 5, Map.of()));
            OlderChatsBar bar = show(px, font);
            cutNothing(bar, px + "px in " + font + ", running");
            set(new Loading(Network.INSTAGRAM, Stage.DONE, 0, 5, 5, answers));
            cutNothing(bar, px + "px in " + font + ", done");
            FxWait.onFx(() -> {
                bar.dispose();
                bar.getScene().getWindow().hide();
            });
        }
    }

    private static void cutNothing(OlderChatsBar bar, String where) throws InterruptedException {
        for (Node n : FxWait.fx(() -> List.copyOf(bar.lookupAll(".label")))) {
            Labeled l = (Labeled) n;
            String full = FxWait.fx(l::getText);
            if (!FxWait.fx(l::isVisible) || full == null || full.isEmpty()) continue;
            Text drawn = (Text) FxWait.fx(() -> l.lookup(".text"));
            assertEquals(full.replace("\n", ""), FxWait.fx(drawn::getText).replace("\n", ""), "cut at " + where);
        }
    }

    /** Every text shown in {@code n}. */
    private static List<String> texts(Node n) {
        List<String> out = new ArrayList<>();
        if (n instanceof Labeled l && l.isVisible() && l.getText() != null && !l.getText().isEmpty()) out.add(l.getText());
        if (n instanceof javafx.scene.Parent p) for (Node c : p.getChildrenUnmodifiable()) if (c.isVisible()) out.addAll(texts(c));
        return out;
    }
}

package app.unichat.ui.chats;

import app.unichat.service.ArchiveWaking.Step;
import app.unichat.service.ArchiveWaking.Waking;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening the archive tells the service, and waking shows on top: what it's doing, N of M, then "Al día" for a moment.
 * The person can reach it with the keyboard and hear it, and nothing is cut at 160 %.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class RestBarUiTest {
    private final AtomicReference<Waking> state = new AtomicReference<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Boolean> told = new CopyOnWriteArrayList<>();
    /** The bar's "a few seconds" go by when the test says. */
    private final List<Runnable> later = new CopyOnWriteArrayList<>();
    private final SimpleBooleanProperty archiveOpen = new SimpleBooleanProperty(false);

    private static void toolkit() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
    }

    private RestBar show(double px) throws Exception {
        return FxWait.fx(() -> {
            RestBar b = new RestBar(state::get, listeners::add, listeners::remove, told::add, archiveOpen, later::add);
            VBox root = new VBox(b);
            root.getStyleClass().add("root");
            root.setStyle("-fx-font-size: " + px + "px;");
            Scene scene = new Scene(root, 900, 200);
            scene.getStylesheets().add(RestBarUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(scene);
            stage.show();
            return b;
        });
    }

    private void set(Waking w) {
        state.set(w);
        listeners.forEach(Runnable::run);
    }

    private static void close(RestBar bar) throws Exception {
        FxWait.fx(() -> {
            bar.dispose();
            bar.getScene().getWindow().hide();
            return null;
        });
    }

    @Test
    void openingAndLeavingTheArchiveIsTold() throws Exception {
        toolkit();
        RestBar bar = show(13);
        assertEquals(List.of(false), told, "where the window starts");
        FxWait.fx(() -> { archiveOpen.set(true); return null; });
        FxWait.fx(() -> { archiveOpen.set(false); return null; });
        assertEquals(List.of(false, true, false), told);
        close(bar);
        FxWait.fx(() -> { archiveOpen.set(true); return null; });
        assertEquals(3, told.size(), "a bar gone tells nothing");
        assertTrue(listeners.isEmpty(), "and stops listening");
    }

    @Test
    void wakingShowsWhatHowFarThenUpToDateForAMoment() throws Exception {
        toolkit();
        RestBar bar = show(13);
        assertFalse(FxWait.fx(bar::isVisible), "nothing woke: no bar");

        set(new Waking(Step.SERVERS, 3, 123));
        FxWait.until("the bar shows", bar::isVisible);
        String text = FxWait.fx(() -> texts(bar).toString());
        assertTrue(text.contains("Reactivando lo archivado de Discord: recibiendo todo de nuevo…"), text);
        assertTrue(text.contains("Servidores de Discord") && text.contains("3 de 123"), text);

        set(new Waking(Step.CHATS, 200, 3699));
        FxWait.until("chats", () -> texts(bar).contains("200 de 3699"));
        assertTrue(FxWait.fx(() -> texts(bar).contains("Chats poniéndose al día")));
        assertEquals(200.0 / 3699, FxWait.fx(() -> ((ProgressBar) bar.lookup(".progress-bar")).getProgress()), 1e-9);
        assertTrue(FxWait.fx(bar::isFocusTraversable), "reachable with Tab");
        String heard = FxWait.fx(bar::getAccessibleText);
        assertTrue(heard.contains("Reactivando") && heard.contains("200 de 3699"), "a screen reader says it all: " + heard);

        set(new Waking(Step.DONE, 3699, 3699));
        FxWait.until("up to date", () -> texts(bar).contains("Al día"));
        assertTrue(FxWait.fx(bar::isVisible), "said for a moment");
        assertTrue(FxWait.fx(() -> bar.getAccessibleText().contains("Al día")));
        assertEquals(1, later.size());
        FxWait.fx(() -> { later.getFirst().run(); return null; });
        assertFalse(FxWait.fx(bar::isVisible), "then it goes");
        close(bar);
    }

    @Test
    void doneBeforeTheBarWasMadeIsNotShown() throws Exception {
        toolkit();
        state.set(new Waking(Step.DONE, 4, 4)); // woken before a language change rebuilt the window
        RestBar bar = show(13);
        assertFalse(FxWait.fx(bar::isVisible));
        close(bar);
    }

    @Test
    void nothingIsCutAt160Percent() throws Exception {
        toolkit();
        state.set(new Waking(Step.CHATS, 3699, 3699));
        RestBar bar = show(21);
        FxWait.settled();
        for (Node n : FxWait.fx(() -> List.copyOf(bar.lookupAll(".label")))) {
            Labeled l = (Labeled) n;
            String full = FxWait.fx(l::getText);
            if (!FxWait.fx(l::isVisible) || full == null || full.isEmpty()) continue;
            Text drawn = (Text) FxWait.fx(() -> l.lookup(".text"));
            assertEquals(full.replace("\n", ""), FxWait.fx(drawn::getText).replace("\n", ""), "cut at 160 %");
        }
        close(bar);
    }

    /** Every text in {@code n}. */
    private static List<String> texts(Node n) {
        List<String> out = new ArrayList<>();
        if (n instanceof Labeled l && l.isVisible()) out.add(l.getText());
        if (n instanceof javafx.scene.Parent p) for (Node c : p.getChildrenUnmodifiable()) if (c.isVisible()) out.addAll(texts(c));
        return out;
    }
}

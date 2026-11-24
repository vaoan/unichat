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
    private final List<String> opened = new CopyOnWriteArrayList<>();
    private final List<String> shown = new CopyOnWriteArrayList<>();
    /** The bar's "a few seconds" go by when the test says. */
    private final List<Runnable> later = new CopyOnWriteArrayList<>();
    private final SimpleBooleanProperty archiveOpen = new SimpleBooleanProperty(false);

    private static void toolkit() throws InterruptedException {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
    }

    private RestBar show(double px) throws InterruptedException {
        return show(px, false);
    }

    private RestBar show(double px, boolean contrast) throws InterruptedException {
        return FxWait.fx(() -> {
            RestBar b = new RestBar(state::get, listeners::add, listeners::remove, told::add, opened::add, shown::add, archiveOpen, later::add);
            VBox root = new VBox(b);
            root.getStyleClass().add("root");
            if (contrast) root.getStyleClass().add("contrast");
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

    private static void close(RestBar bar) throws InterruptedException {
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
        FxWait.onFx(() -> archiveOpen.set(true));
        FxWait.onFx(() -> archiveOpen.set(false));
        assertEquals(List.of(false, true, false), told);
        close(bar);
        FxWait.onFx(() -> archiveOpen.set(true));
        assertEquals(3, told.size(), "a bar gone tells nothing");
        assertTrue(listeners.isEmpty(), "and stops listening");
    }

    @Test
    void wakingShowsWhatHowFarThenUpToDateForAMoment() throws Exception {
        toolkit();
        RestBar bar = show(13);
        assertFalse(FxWait.fx(bar::isVisible), "nothing woke: no bar");

        set(new Waking(Step.CHATS, 200, 3699));
        FxWait.until("the bar shows", bar::isVisible);
        FxWait.until("chats", () -> texts(bar).contains("200 de 3699"));
        assertTrue(FxWait.fx(() -> texts(bar).contains("Reactivando lo archivado de Discord: recibiendo todo de nuevo…")));
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
        FxWait.onFx(() -> later.getFirst().run());
        assertFalse(FxWait.fx(bar::isVisible), "then it goes");
        close(bar);
    }

    @Test
    void aServerOpenedInTheArchiveAndACatchUpThatFailedAreSaid() throws Exception {
        toolkit();
        RestBar bar = show(13);
        set(new Waking(Step.SERVER, 0, 0, "Furry Moonfest"));
        FxWait.until("the server", () -> texts(bar).contains("Reactivando Furry Moonfest: recibiendo todo de nuevo…"));
        assertEquals(List.of("Reactivando Furry Moonfest: recibiendo todo de nuevo…"), FxWait.fx(() -> texts(bar)), "no count for a server");
        assertTrue(FxWait.fx(() -> bar.getAccessibleText().contains("Furry Moonfest")));

        set(new Waking(Step.RETRYING, 100, 3699));
        FxWait.until("the failure", () -> texts(bar).contains("No se pudo terminar de ponerse al día: se intentará de nuevo en breve"));
        assertTrue(FxWait.fx(() -> texts(bar).contains("100 de 3699")));
        close(bar);
    }

    @Test
    void anArchivedChatOpenedInTheArchiveIsTold() throws Exception {
        toolkit();
        RestBar bar = show(13);
        app.unichat.model.Chat archived = new app.unichat.model.Chat("!c1:x");
        archived.setArchived(true);
        app.unichat.model.Chat inbox = new app.unichat.model.Chat("!n:x");
        FxWait.onFx(() -> bar.chatOpened(archived));
        assertTrue(opened.isEmpty(), "outside the archive: no server comes back");
        assertEquals(List.of("!c1:x"), shown, "but the chat shown never rests, however it was opened");
        FxWait.onFx(() -> {
            archiveOpen.set(true);
            bar.chatOpened(inbox);
            bar.chatOpened(archived);
            bar.serverOpened(archived);
        });
        assertEquals(List.of("!c1:x", "!c1:x"), opened, "only the archived one, in the archive, as a chat or a server");
        assertEquals(List.of("!c1:x", "!n:x", "!c1:x"), shown, "a server's channels showing isn't a chat shown");
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
    void nothingIsCutAt160PercentNorInHighContrast() throws Exception {
        toolkit();
        for (boolean contrast : List.of(false, true)) cutNothing(contrast);
    }

    private void cutNothing(boolean contrast) throws InterruptedException {
        state.set(new Waking(Step.CHATS, 3699, 3699));
        RestBar bar = show(21, contrast);
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

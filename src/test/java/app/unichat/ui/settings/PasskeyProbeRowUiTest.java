package app.unichat.ui.settings;

import app.unichat.cloud.PasskeyPage;
import app.unichat.cloud.PasskeyProbe;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Probar llave de acceso (prueba)": there only when asked for, it opens the probe and says what it found. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class PasskeyProbeRowUiTest {
    @BeforeAll
    static void toolkit() throws Exception {
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
    void flagOff() {
        System.clearProperty(PasskeyProbe.FLAG);
    }

    private final AtomicReference<Consumer<PasskeyPage.Finding>> told = new AtomicReference<>();
    private final CompletableFuture<Void> session = new CompletableFuture<>();

    private static final URI ADDRESS = URI.create("http://localhost:43117/passkey");

    private CompletableFuture<Void> start(Consumer<URI> opened, Consumer<PasskeyPage.Finding> f) {
        told.set(f);
        opened.accept(ADDRESS);
        return session;
    }

    private Stage show(Node row) throws InterruptedException {
        return FxWait.fx(() -> {
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(new StackPane(row), 400, 300));
            s.show();
            return s;
        });
    }

    private static Button button(Node row) {
        return (Button) row.lookupAll(".button").iterator().next();
    }

    private static boolean says(Node row, String text) {
        return row.lookupAll(".label").stream().anyMatch(n -> text.equals(((Label) n).getText()));
    }

    @Test
    void itIsThereOnlyWhenAskedFor() throws Exception {
        // Built on the UI thread: a control made on another one deadlocked with it in JavaFX's class setup.
        assertTrue(FxWait.fx(() -> PasskeyProbeRow.ifAsked(this::start)).isEmpty(), "hidden unless the probe was asked for");
        System.setProperty(PasskeyProbe.FLAG, "true");
        assertTrue(FxWait.fx(() -> PasskeyProbeRow.ifAsked(this::start)).isPresent());
    }

    @Test
    void itOpensTheProbeAndSaysWhatItFound() throws Exception {
        Node row = FxWait.fx(() -> PasskeyProbeRow.build(this::start));
        Stage stage = show(row);
        Button run = button(row);
        assertEquals(I18n.t("settings.vault.probe.title"), run.getAccessibleText(), "a screen reader names it");
        assertTrue(run.isFocusTraversable(), "reachable by keyboard");
        FxWait.onFx(run::fire);
        assertNotNull(told.get(), "the probe started");
        FxWait.until("says it's open, at which address", () -> says(row, I18n.t("settings.vault.probe.opened", ADDRESS)));
        assertTrue(I18n.t("settings.vault.probe.opened", ADDRESS).contains(ADDRESS.toString()), "on screen, for no browser");
        told.get().accept(new PasskeyPage.Finding(PasskeyPage.Step.GET, null, 32, "0123456789abcdef", "id", "platform", "UA", ""));
        FxWait.until("says what it found", () -> says(row, I18n.t("cloud.passkey.gotPrf")));
        FxWait.onFx(stage::close);
    }

    @Test
    void aProbeThatCantStartSaysWhy() throws Exception {
        Node row = FxWait.fx(() -> PasskeyProbeRow.build(this::start));
        Stage stage = show(row);
        FxWait.onFx(() -> button(row).fire());
        session.completeExceptionally(new IOException("puerto ocupado"));
        FxWait.until("says why", () -> says(row, "puerto ocupado"));
        FxWait.onFx(stage::close);
    }

    @Test
    void aProbeLeftToItsDeadlineSaysSoInItsOwnWords() throws Exception {
        Node row = FxWait.fx(() -> PasskeyProbeRow.build(this::start));
        Stage stage = show(row);
        FxWait.onFx(() -> button(row).fire());
        session.completeExceptionally(new IOException(I18n.t("cloud.passkey.expired")));
        FxWait.until("says the test stopped", () -> says(row, I18n.t("cloud.passkey.expired")));
        assertNotEquals(I18n.t("cloud.signIn.expired"), I18n.t("cloud.passkey.expired"), "a probe, not a sign-in");
        FxWait.onFx(stage::close);
    }
}

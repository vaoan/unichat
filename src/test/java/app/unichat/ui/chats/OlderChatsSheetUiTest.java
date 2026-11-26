package app.unichat.ui.chats;

import app.unichat.model.Network;
import app.unichat.service.OlderChats;
import app.unichat.service.OlderChats.Plan;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warning before every "Load older chats": what each network will do and its risks, Cancel focused and doing
 * nothing, and Instagram left out unless its box is ticked on that click.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class OlderChatsSheetUiTest {
    private static final Plan TELEGRAM = new Plan(Network.TELEGRAM, 100, true, false, false, null);
    private static final Plan WHATSAPP = new Plan(Network.WHATSAPP, -1, false, false, false, null);
    private static final Plan LINKEDIN = new Plan(Network.LINKEDIN, -1, true, false, false, null);
    private static final Plan DISCORD = new Plan(Network.DISCORD, 50, true, true, false, null);
    private static final Plan INSTAGRAM = new Plan(Network.INSTAGRAM, -1, true, false, true, null);

    /** What the sheet's Load hands on: each call's list, as {@link OlderChats#load} gets it. */
    private final List<List<Network>> loads = new CopyOnWriteArrayList<>();
    /** The service's own load, recording what reaches it: the real path from the sheet's button. */
    private final OlderChats svc = new OlderChats() {
        @Override public CompletableFuture<Map<Network, Answer>> load(List<Network> networks) {
            loads.add(List.copyOf(networks));
            return CompletableFuture.completedFuture(Map.of());
        }
    };
    private final List<Stage> stages = new ArrayList<>();
    @SuppressWarnings("NullAway.Init") // each test opens it first
    private StackPane overlay;

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
        FxWait.onFx(() -> {
            stages.forEach(Stage::close);
            stages.clear();
        });
    }

    private Node open(List<Plan> plans) throws InterruptedException {
        return open(plans, 13, false);
    }

    /** The sheet in the test's window (one per test, as the app opens it again in its one window), at {@code px}. */
    private Node open(List<Plan> plans, double px, boolean contrast) throws InterruptedException {
        boolean first = stages.isEmpty();
        FxWait.onFx(() -> {
            if (first) {
                overlay = new StackPane();
                Scene scene = new Scene(overlay, 600, 900);
                scene.getStylesheets().add(OlderChatsSheetUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
                Stage stage = new Stage();
                stage.setOpacity(0);
                stage.setScene(scene);
                stage.show();
                stages.add(stage);
            }
            overlay.getStyleClass().setAll("root");
            if (contrast) overlay.getStyleClass().add("contrast");
            overlay.setStyle("-fx-font-size: " + px + "px;");
        });
        if (first) FxWait.until("the window has its focus", () -> overlay.getScene().getWindow().isFocused());
        // As in the app, the sheet opens in a window that's done with what came before: its first focus, or the focus
        // finding a new owner after the last sheet's Cancel went (which, pending, took it from this sheet's Cancel).
        FxWait.settled();
        FxWait.onFx(() -> {
            new OlderChatsSheet(n -> overlay.getChildren().setAll(n), () -> overlay.getChildren().clear()).open(plans, run -> { var _ = svc.load(run); });
        });
        FxWait.until("the sheet shows", () -> sheet() != null);
        FxWait.settled();
        return app.unichat.Present.of(FxWait.fx(this::sheet));
    }

    private @Nullable Node sheet() {
        return overlay.lookup(".older-chats-sheet");
    }

    private boolean gone() throws InterruptedException {
        return FxWait.fx(() -> sheet() == null);
    }

    private Button cancel() throws InterruptedException {
        return FxWait.fx(() -> (Button) app.unichat.Present.of(sheet()).lookup(".secondary-button"));
    }

    private Button load() throws InterruptedException {
        return FxWait.fx(() -> (Button) app.unichat.Present.of(sheet()).lookup(".primary-button"));
    }

    private @Nullable Node focused() throws InterruptedException {
        return FxWait.fx(() -> overlay.getScene().getFocusOwner());
    }

    private void key(KeyCode code) throws InterruptedException {
        FxWait.onFx(() -> {
            Node target = overlay.getScene().getFocusOwner();
            String ch = code == KeyCode.TAB ? "\t" : code == KeyCode.ENTER ? "\r" : "";
            for (var type : List.of(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
                Event.fireEvent(target, new KeyEvent(type, "", ch, code, false, false, false, false));
            }
        });
        FxWait.settled();
    }

    /** One row per plan, in order: each says it all. */
    private List<Node> rows() throws InterruptedException {
        return FxWait.fx(() -> List.copyOf(app.unichat.Present.of(sheet()).lookupAll(".older-chats-row")));
    }

    private static String said(Node row) throws InterruptedException {
        return FxWait.fx(() -> String.valueOf(row.getAccessibleText()));
    }

    @Test
    void cancelIsFocusedAndRunsNothing() throws Exception {
        open(List.of(TELEGRAM, WHATSAPP));
        FxWait.until("Cancel has the focus", () -> focused() == cancel());
        FxWait.onFx(() -> cancel().fire());
        assertTrue(gone(), "a click on Cancel closes it");
        assertTrue(loads.isEmpty(), "and nothing runs");

        open(List.of(TELEGRAM, WHATSAPP));
        FxWait.until("Cancel has the focus", () -> focused() == cancel());
        key(KeyCode.ENTER);
        assertTrue(gone(), "Enter on Cancel closes it");
        assertTrue(loads.isEmpty(), "and nothing runs");
    }

    @Test
    void escapeCancels() throws Exception {
        open(List.of(TELEGRAM));
        FxWait.until("Cancel has the focus", () -> focused() == cancel());
        key(KeyCode.ESCAPE);
        assertTrue(gone());
        assertTrue(loads.isEmpty());
    }

    @Test
    void eachNetworkSaysHowManyAndWhetherItRestarts() throws Exception {
        open(List.of(TELEGRAM, WHATSAPP, LINKEDIN));
        List<Node> rows = rows();
        assertEquals(3, rows.size());
        String telegram = said(rows.get(0));
        String whatsapp = said(rows.get(1));
        String linkedin = said(rows.get(2));
        assertTrue(telegram.contains("Telegram") && telegram.contains(I18n.t("olderChats.sheet.upTo", 100)), telegram);
        assertTrue(telegram.contains(I18n.t("olderChats.sheet.restarts", "Telegram")), telegram);
        assertTrue(whatsapp.contains(I18n.t("olderChats.sheet.everyGroup")), whatsapp);
        assertFalse(whatsapp.contains(I18n.t("olderChats.sheet.restarts", "WhatsApp")), "WhatsApp doesn't restart: " + whatsapp);
        assertTrue(linkedin.contains(I18n.t("olderChats.sheet.all")), linkedin);
        assertTrue(linkedin.contains(I18n.t("olderChats.sheet.restarts", "LinkedIn")), linkedin);
        for (Node row : rows) {
            List<String> shown = FxWait.fx(() -> texts(row));
            for (String line : shown) assertTrue(said(row).contains(line), "what the row shows, it says: " + line);
        }
        FxWait.onFx(() -> load().fire());
        assertEquals(List.of(List.of(Network.TELEGRAM, Network.WHATSAPP, Network.LINKEDIN)), loads, "in the order shown");
        assertTrue(gone());
    }

    @Test
    void discordSaysItReconnectsEveryServer() throws Exception {
        open(List.of(TELEGRAM, DISCORD));
        assertTrue(said(rows().get(1)).contains(I18n.t("olderChats.sheet.reconnectsServers")), said(rows().get(1)));
        assertTrue(said(rows().get(1)).contains(I18n.t("olderChats.sheet.privateChats", 50)), said(rows().get(1)));
        FxWait.onFx(() -> cancel().fire());

        Plan paused = new Plan(Network.DISCORD, 50, true, true, false, Duration.ofMinutes(7));
        open(List.of(TELEGRAM, paused));
        String discord = said(rows().get(1));
        assertTrue(discord.contains(I18n.n("olderChats.wait", 7)), discord);
        FxWait.onFx(() -> load().fire());
        assertEquals(List.of(List.of(Network.TELEGRAM)), loads, "Discord in its pause doesn't run");
    }

    @Test
    void instagramIsOffByDefaultAndRunsOnlyWhenTicked() throws Exception {
        Node sheet = open(List.of(TELEGRAM, INSTAGRAM));
        CheckBox box = FxWait.fx(() -> (CheckBox) sheet.lookup(".check-box"));
        assertNotNull(box);
        assertFalse(FxWait.fx(box::isSelected), "off by default");
        Labeled risk = FxWait.fx(() -> (Labeled) sheet.lookup(".older-chats-risk"));
        assertNotNull(risk, "a warning of its own");
        assertEquals(I18n.t("olderChats.sheet.instagramRisk"), FxWait.fx(risk::getText));
        assertTrue(FxWait.fx(() -> risk.isVisible() && risk.getParent() != null && risk.getScene() != null), "visible text, not a tooltip");
        assertNull(FxWait.fx(risk::getTooltip));
        FxWait.onFx(() -> load().fire());
        assertEquals(List.of(List.of(Network.TELEGRAM)), loads, "left out unless ticked");

        Node again = open(List.of(TELEGRAM, INSTAGRAM));
        CheckBox tick = FxWait.fx(() -> (CheckBox) again.lookup(".check-box"));
        assertFalse(FxWait.fx(tick::isSelected), "off again on the next click");
        FxWait.onFx(() -> tick.setSelected(true));
        FxWait.onFx(() -> load().fire());
        assertEquals(List.of(Network.TELEGRAM, Network.INSTAGRAM), loads.get(1), "ticked: it runs");
    }

    @Test
    void loadIsDisabledWhenNothingWouldRun() throws Exception {
        Node sheet = open(List.of(INSTAGRAM));
        assertTrue(FxWait.fx(() -> load().isDisabled()), "only an unticked Instagram: nothing would run");
        FxWait.onFx(() -> ((CheckBox) sheet.lookup(".check-box")).setSelected(true));
        assertFalse(FxWait.fx(() -> load().isDisabled()));
        FxWait.onFx(() -> ((CheckBox) sheet.lookup(".check-box")).setSelected(false));
        assertTrue(FxWait.fx(() -> load().isDisabled()));
    }

    @Test
    void theTrafficAndWhatsappLinesShow() throws Exception {
        Node sheet = open(List.of(TELEGRAM));
        assertTrue(FxWait.fx(() -> texts(sheet)).contains(I18n.t("olderChats.sheet.traffic")), "the traffic line always");
        assertFalse(FxWait.fx(() -> texts(sheet)).contains(I18n.t("olderChats.sheet.whatsappPairing")), "WhatsApp's only with it");
        FxWait.onFx(() -> cancel().fire());
        Node withWhatsapp = open(List.of(TELEGRAM, WHATSAPP));
        assertTrue(FxWait.fx(() -> texts(withWhatsapp)).contains(I18n.t("olderChats.sheet.traffic")));
        assertTrue(FxWait.fx(() -> texts(withWhatsapp)).contains(I18n.t("olderChats.sheet.whatsappPairing")));
    }

    @Test
    void keyboardReachesEveryControlWithItsText() throws Exception {
        Node sheet = open(List.of(TELEGRAM, WHATSAPP, INSTAGRAM));
        FxWait.until("Cancel has the focus", () -> focused() == cancel());
        CheckBox box = FxWait.fx(() -> (CheckBox) sheet.lookup(".check-box"));
        List<Node> reached = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            key(KeyCode.TAB);
            Node now = focused();
            if (now == cancel()) break;
            if (now != null) reached.add(now);
        }
        assertSame(cancel(), focused(), "Tab comes back to Cancel");
        // From Cancel: Load, then the sheet from the top in reading order (each row, Instagram's box under its row).
        List<Node> rows = rows();
        assertEquals(List.of(load(), rows.get(0), rows.get(1), rows.get(2), box), reached, "Tab goes through every control");
        for (Node n : List.of(cancel(), box, load())) {
            String text = FxWait.fx(() -> n.getAccessibleText() != null ? n.getAccessibleText() : ((Labeled) n).getText());
            assertFalse(text == null || text.isBlank(), "every control says what it is: " + n);
        }
        for (Node row : rows()) {
            assertTrue(FxWait.fx(row::isFocusTraversable));
            assertFalse(said(row).isBlank());
        }
        String help = FxWait.fx(() -> cancel().getAccessibleHelp());
        assertTrue(help.contains(I18n.t("olderChats.sheet.title")), "the focus starts on Cancel: it says what's asked: " + help);
        FxWait.onFx(box::fire);
        assertTrue(FxWait.fx(box::isSelected), "the box ticks from the keyboard (Space fires it)");
    }

    @Test
    void fitsAt160PercentAndInHighContrast() throws Exception {
        for (boolean contrast : List.of(false, true)) {
            Node sheet = open(List.of(TELEGRAM, WHATSAPP, DISCORD, LINKEDIN, INSTAGRAM), 21, contrast);
            Node card = FxWait.fx(() -> sheet.lookup(".card"));
            double cardWidth = FxWait.fx(() -> card.getLayoutBounds().getWidth());
            for (Node n : FxWait.fx(() -> List.copyOf(sheet.lookupAll(".label")))) cutNothing(n, cardWidth);
            for (Node n : FxWait.fx(() -> List.copyOf(sheet.lookupAll(".button")))) cutNothing(n, cardWidth);
            for (Node n : FxWait.fx(() -> List.copyOf(sheet.lookupAll(".check-box")))) cutNothing(n, cardWidth);
            FxWait.onFx(() -> cancel().fire());
        }
    }

    private static void cutNothing(Node n, double room) throws InterruptedException {
        Labeled l = (Labeled) n;
        String full = FxWait.fx(l::getText);
        if (!FxWait.fx(l::isVisible) || full == null || full.isEmpty()) return;
        Text drawn = (Text) FxWait.fx(() -> l.lookup(".text"));
        assertEquals(full.replace("\n", ""), FxWait.fx(drawn::getText).replace("\n", ""), "cut: " + full);
        assertTrue(FxWait.fx(l::getWidth) <= room + 0.5, "wider than the card: " + full);
    }

    /** Every text shown in {@code n}. */
    private static List<String> texts(Node n) {
        List<String> out = new ArrayList<>();
        if (n instanceof Labeled l && l.isVisible() && l.getText() != null && !l.getText().isEmpty()) out.add(l.getText());
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) if (c.isVisible()) out.addAll(texts(c));
        return out;
    }
}

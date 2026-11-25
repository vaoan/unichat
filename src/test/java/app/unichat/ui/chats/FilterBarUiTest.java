package app.unichat.ui.chats;

import app.unichat.service.MockChatService;
import app.unichat.support.I18n;
import app.unichat.ui.FxWait;
import app.unichat.ui.Icons;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chat list's quick filters: one segmented bar on one line, "All" as text and three named icons. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class FilterBarUiTest {
    /** The chat column's least width ({@code MainView}) and its usual one. */
    private static final int[] WIDTHS = {260, 340};
    private static final String[] KEYS = {"chatList.chip.all", "chatList.chip.unread", "chatList.chip.favorites",
            "chatList.chip.lowPriority"};

    private final MockChatService svc = new MockChatService();
    private final List<Stage> stages = new ArrayList<>();

    @AfterEach
    void close() throws InterruptedException {
        FxWait.onFx(() -> {
            stages.forEach(Stage::close);
            Icons.SCALE.set(1);
        });
        svc.stop();
    }

    @Test
    void allFourPartsSitOnOneLineInsideTheColumnAtAnyTextSize() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (double scale : new double[] {1.0, 1.6}) {
            for (int width : WIDTHS) {
                for (String look : new String[] {"", "dark", "contrast"}) {
                    ChatListPane pane = show(width, scale, look);
                    String where = "text " + scale + ", " + width + " px" + (look.isEmpty() ? "" : ", " + look) + ": ";
                    wrong.addAll(FxWait.fx(() -> oneLine(pane, width, where)));
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    /** What's wrong with the bar's layout in {@code pane}, each line starting with {@code where}. */
    private static List<String> oneLine(ChatListPane pane, int width, String where) {
        List<String> w = new ArrayList<>();
        List<Long> tops = new ArrayList<>();
        for (ToggleButton t : parts(pane)) {
            Bounds b = t.localToScene(t.getBoundsInLocal());
            tops.add(Math.round(b.getMinY()));
            if (b.getMinX() < -0.5 || b.getMaxX() > width + 0.5) w.add(where + name(t) + " leaves the column " + b);
            if (t.getWidth() + 0.5 < t.prefWidth(-1)) w.add(where + name(t) + " is squeezed");
            if (t.lookup(".text") instanceof Text shown && !shown.getText().equals(t.getText())) {
                w.add(where + name(t) + " cut to " + shown.getText());
            }
        }
        if (tops.stream().distinct().count() != 1) w.add(where + "parts on more than one line " + tops);
        return w;
    }

    @Test
    void eachPartSetsItsFilter() throws Exception {
        ChatListPane pane = show(340, 1, "");
        List<ToggleButton> parts = FxWait.fx(() -> parts(pane));
        fire(parts.get(2));
        ChatFilter f = FxWait.fx(() -> pane.filterProperty().get());
        assertEquals(ChatFilter.View.FAVORITES, f.view());
        assertFalse(f.unreadOnly());
        fire(parts.get(1));
        f = FxWait.fx(() -> pane.filterProperty().get());
        assertEquals(ChatFilter.View.INBOX, f.view());
        assertTrue(f.unreadOnly());
        fire(parts.get(3));
        f = FxWait.fx(() -> pane.filterProperty().get());
        assertEquals(ChatFilter.View.LOW_PRIORITY, f.view());
        assertFalse(f.unreadOnly(), "Low priority isn't only the unread");
        fire(parts.get(0));
        f = FxWait.fx(() -> pane.filterProperty().get());
        assertEquals(ChatFilter.View.INBOX, f.view());
        assertFalse(f.unreadOnly());
    }

    @Test
    void onePartIsAlwaysChosenEvenWhenTheChosenOneIsClickedAgain() throws Exception {
        ChatListPane pane = show(340, 1, "");
        List<ToggleButton> parts = FxWait.fx(() -> parts(pane));
        assertEquals(List.of(true, false, false, false), FxWait.fx(() -> selected(parts)), "All at first");
        for (int i : new int[] {0, 2, 2, 3, 1, 1}) {
            fire(parts.get(i));
            List<Boolean> on = FxWait.fx(() -> selected(parts));
            assertEquals(1, on.stream().filter(b -> b).count(), "exactly one chosen after part " + i + ": " + on);
            assertTrue(on.get(i), "part " + i + " stays chosen");
        }
    }

    @Test
    void theBarFollowsAFilterChangedElsewhere() throws Exception {
        ChatListPane pane = show(340, 1, "");
        List<ToggleButton> parts = FxWait.fx(() -> parts(pane));
        FxWait.onFx(() -> pane.goTo(ChatFilter.DEFAULT.withView(ChatFilter.View.FAVORITES)));
        assertEquals(List.of(false, false, true, false), FxWait.fx(() -> selected(parts)), "a shortcut to Favorites");
        FxWait.onFx(() -> pane.filterProperty().set(ChatFilter.DEFAULT.withUnreadOnly(true)));
        assertEquals(List.of(false, true, false, false), FxWait.fx(() -> selected(parts)), "unread only");
        FxWait.onFx(() -> pane.goTo(ChatFilter.DEFAULT.withView(ChatFilter.View.ARCHIVE)));
        assertEquals(List.of(false, true, false, false), FxWait.fx(() -> selected(parts)),
                "the archive has no part: the last one stays");
        FxWait.onFx(() -> pane.goTo(ChatFilter.DEFAULT.withView(ChatFilter.View.LOW_PRIORITY)));
        assertEquals(List.of(false, false, false, true), FxWait.fx(() -> selected(parts)), "leaving the archive");
        FxWait.onFx(() -> pane.goTo(ChatFilter.DEFAULT));
        assertEquals(List.of(true, false, false, false), FxWait.fx(() -> selected(parts)), "back to the inbox");
    }

    @Test
    void tabReachesTheBarArrowsGoAlongItAndSpaceChoosesAPart() throws Exception {
        ChatListPane pane = show(340, 1, "");
        List<ToggleButton> parts = FxWait.fx(() -> parts(pane));
        // A toggle group is one stop for Tab, on its chosen part (as the chips were); the arrows go along it.
        FxWait.onFx(() -> pane.lookup(".search-field").requestFocus());
        key(pane, KeyCode.TAB);
        assertSame(parts.get(0), focused(pane), "Tab from the search reaches the bar");
        for (int i = 1; i < parts.size(); i++) {
            ToggleButton part = parts.get(i);
            key(pane, KeyCode.RIGHT);
            assertSame(part, focused(pane), "Right arrow reaches " + name(part));
            key(pane, KeyCode.SPACE);
            assertTrue(FxWait.fx(part::isSelected), "Space chooses " + name(part));
        }
        ChatFilter f = FxWait.fx(() -> pane.filterProperty().get());
        assertEquals(ChatFilter.View.LOW_PRIORITY, f.view(), "Space applied the last part's filter");
        key(pane, KeyCode.SPACE);
        assertTrue(FxWait.fx(parts.get(3)::isSelected), "Space on the chosen part keeps it");
        key(pane, KeyCode.LEFT);
        assertSame(parts.get(2), focused(pane), "Left arrow goes back");
        key(pane, KeyCode.SPACE);
        assertEquals(ChatFilter.View.FAVORITES, FxWait.fx(() -> pane.filterProperty().get()).view());
        FxWait.onFx(() -> pane.lookup(".search-field").requestFocus());
        key(pane, KeyCode.TAB);
        assertSame(parts.get(2), focused(pane), "Tab comes back to the chosen part");
        assertTrue(FxWait.fx(() -> parts.stream().allMatch(Node::isFocusTraversable)));
    }

    private static @Nullable Node focused(ChatListPane pane) throws InterruptedException {
        return FxWait.<@Nullable Node, RuntimeException>fx(() -> pane.getScene().getFocusOwner());
    }

    @Test
    void eachIconPartSaysItsNameToScreenReadersAndInItsTooltip() throws Exception {
        ChatListPane pane = show(340, 1, "");
        List<ToggleButton> parts = FxWait.fx(() -> parts(pane));
        assertEquals(I18n.t(KEYS[0]), FxWait.fx(() -> parts.get(0).getText()), "All is text");
        for (int i = 1; i < parts.size(); i++) {
            ToggleButton part = parts.get(i);
            String name = I18n.t(KEYS[i]);
            assertEquals(name, FxWait.fx(part::getAccessibleText));
            assertEquals(name, FxWait.fx(() -> tooltip(part)));
            assertTrue(FxWait.fx(() -> part.getText() == null || part.getText().isEmpty()), name + " shows only its icon");
            assertTrue(FxWait.fx(() -> part.lookup(".icon") != null), name + " has an icon");
        }
    }

    @Test
    void highContrastShowsTheChosenPartByItsShapeAndItsIconStaysVisible() throws Exception {
        ChatListPane pane = show(340, 1, "contrast");
        List<ToggleButton> parts = FxWait.fx(() -> parts(pane));
        for (int chosen = 0; chosen < parts.size(); chosen++) {
            fire(parts.get(chosen));
            for (int i = 0; i < parts.size(); i++) {
                ToggleButton part = parts.get(i);
                List<Paint> fills = FxWait.fx(() -> fills(part.getBackground()));
                if (i == chosen) {
                    assertEquals(3, fills.size(), name(part) + " chosen: a ring inside its fill " + fills);
                    assertNotEquals(fills.get(0), fills.get(1), "the ring shows against the fill");
                    if (i > 0) {
                        Paint icon = FxWait.fx(() -> fills(((Region) part.lookup(".icon")).getBackground()).get(0));
                        assertNotEquals(fills.get(2), icon, name(part) + "'s icon shows on the fill");
                    }
                } else {
                    assertTrue(fills.stream().allMatch(p -> p.equals(Color.TRANSPARENT)),
                            name(part) + " not chosen: no fill " + fills);
                }
            }
        }
    }

    private ChatListPane show(int width, double scale, String look) throws InterruptedException {
        toolkit();
        ChatListPane pane = FxWait.fx(() -> {
            Icons.SCALE.set(scale);
            ChatListPane p = new ChatListPane(svc, c -> { }, new FolderDialogs(svc, n -> { }, () -> { }));
            Scene scene = new Scene(p, width, 700);
            scene.getStylesheets().add(FilterBarUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
            if (!look.isEmpty()) p.getStyleClass().add(look);
            p.setStyle("-fx-font-size: " + 13 * scale + "px;");
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(scene);
            stage.show();
            stages.add(stage);
            return p;
        });
        FxWait.until("the filters show", () -> parts(pane).size() == KEYS.length);
        FxWait.settled();
        return pane;
    }

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

    /** The filter toggles in the order of {@link #KEYS}, found by the name they show or say. */
    private static List<ToggleButton> parts(ChatListPane pane) {
        List<ToggleButton> out = new ArrayList<>();
        for (String key : KEYS) {
            pane.lookupAll(".toggle-button").stream()
                    .filter(n -> n instanceof ToggleButton t && I18n.t(key).equals(name(t)) && t.isVisible())
                    .map(ToggleButton.class::cast).findFirst().ifPresent(out::add);
        }
        return out;
    }

    private static String name(ToggleButton t) {
        String said = t.getAccessibleText();
        return said != null ? said : t.getText();
    }

    private static List<Boolean> selected(List<ToggleButton> parts) {
        return parts.stream().map(ToggleButton::isSelected).toList();
    }

    private static List<Paint> fills(@Nullable Background b) {
        return b == null ? List.of() : b.getFills().stream().map(BackgroundFill::getFill).toList();
    }

    private static void fire(ToggleButton b) throws InterruptedException {
        FxWait.onFx(b::fire);
        FxWait.settled();
    }

    private static void key(ChatListPane pane, KeyCode code) throws InterruptedException {
        FxWait.onFx(() -> {
            Node target = pane.getScene().getFocusOwner();
            String ch = code == KeyCode.SPACE ? " " : code == KeyCode.TAB ? "\t" : "";
            for (var type : List.of(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
                Event.fireEvent(target, new KeyEvent(type, "", ch, code, false, false, false, false));
            }
        });
        FxWait.settled();
    }

    /** What a part's tooltip says, or null without one. */
    private static @Nullable String tooltip(ToggleButton b) {
        javafx.scene.control.Tooltip t = b.getTooltip();
        return t == null ? null : t.getText();
    }
}

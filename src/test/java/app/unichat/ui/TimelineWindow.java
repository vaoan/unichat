package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.service.ScriptedChatService;
import app.unichat.ui.desktop.Notifier;
import app.unichat.ui.timeline.Composer;
import app.unichat.ui.timeline.ThreadRows;
import java.util.concurrent.ExecutionException;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The whole window with one chat open, for the tests of how the message list scrolls: hidden (opacity 0), the demo with
 * nobody acting on their own (the test plays the others, {@link ScriptedChatService}), and a way to watch every frame.
 */
final class TimelineWindow {
    static final String CHAT = "!andres:demo";

    final ScriptedChatService svc;
    final Stage stage;
    final MainView view;
    final Scene scene;

    private TimelineWindow(ScriptedChatService svc, Stage stage, MainView view, Scene scene) {
        this.svc = svc;
        this.stage = stage;
        this.view = view;
        this.scene = scene;
    }

    static void toolkit() throws InterruptedException {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        // Each test closes its window: the last one closing would end JavaFX for every test after it.
        Platform.setImplicitExit(false);
    }

    /** {@link #CHAT} with {@code messages} of four lines each (more than the window holds), open and at its newest. */
    static TimelineWindow open(int messages) throws InterruptedException, ExecutionException {
        return open(messages, "Mensaje largo %d\nsegunda línea\ntercera línea\ncuarta línea");
    }

    /** {@code format} gets the message's number. Another chat is opened first, as a person comes from one. */
    static TimelineWindow open(int messages, String format) throws InterruptedException, ExecutionException {
        return open(messages, format, svc -> { });
    }

    /** {@code before} changes the chat before the window opens (a message's HTML, say). */
    static TimelineWindow open(int messages, String format, java.util.function.Consumer<ScriptedChatService> before) throws InterruptedException, ExecutionException {
        return open(messages, format, before, HEIGHT);
    }

    /** The window's usual height; {@link #SHORT} leaves a list as short as Linux's taller fonts do (416 px there). */
    static final int HEIGHT = 560, SHORT = 480;

    /** {@code height}: the window's. */
    static TimelineWindow open(int messages, String format, java.util.function.Consumer<ScriptedChatService> before,
                               int height) throws InterruptedException, ExecutionException {
        toolkit();
        ScriptedChatService svc = new ScriptedChatService();
        for (int i = 0; i < messages; i++) svc.sendText(CHAT, format.formatted(i), null).get();
        before.accept(svc);
        TimelineWindow w = FxWait.fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            MainView view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1000, height);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return new TimelineWindow(svc, stage, view, sc);
        });
        FxWait.until("the chat list shows", () -> !w.scene.getRoot().lookupAll(".chat-list .list-cell").isEmpty());
        FxWait.fx(() -> { w.view.openChatId("!family:demo"); return null; });
        FxWait.settled();
        w.openChat();
        return w;
    }

    /** Opens {@link #CHAT} and waits until it's at its newest message. */
    void openChat() throws InterruptedException {
        FxWait.fx(() -> { view.openChatId(CHAT); return null; });
        FxWait.until("the chat's newest message is listed", this::showsNewest);
        untilAtBottom();
    }

    /** The row drawing {@code m}, or null when it isn't drawn. */
    javafx.scene.control.ListCell<?> cellOf(Message m) {
        for (Node n : list().lookupAll(".row-cell")) {
            if (n instanceof javafx.scene.control.ListCell<?> c && c.getItem() instanceof ThreadRows.MessageRow mr && mr.message().id().equals(m.id())) return c;
        }
        return null;
    }

    /** The item {@code text} of {@code m}'s menu, opened from the keys as a person would (Enter on it). */
    javafx.scene.control.MenuItem menuItem(Message m, String text) throws InterruptedException {
        return FxWait.fx(() -> {
            javafx.scene.control.ListCell<?> cell = cellOf(m);
            list().getSelectionModel().select(cell.getIndex());
            list().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            javafx.scene.control.ContextMenu menu = javafx.stage.Window.getWindows().stream().filter(x -> x instanceof javafx.scene.control.ContextMenu cm && cm.isShowing()
                    && cm.getOwnerNode() != null && cm.getOwnerNode().getScene() == scene).map(x -> (javafx.scene.control.ContextMenu) x).findFirst().orElseThrow();
            javafx.scene.control.MenuItem item = menu.getItems().stream().filter(i -> text.equals(i.getText())).findFirst().orElseThrow();
            menu.hide();
            return item;
        });
    }

    /** Runs a menu item as a click on it would. */
    static void fire(javafx.scene.control.MenuItem item) throws InterruptedException {
        FxWait.fx(() -> {
            item.fire();
            return null;
        });
    }

    /** A click on {@code cell}, with Shift when {@code shift}. */
    static void press(Node cell, boolean shift) {
        cell.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1, shift, false, false, false,
                true, false, false, true, false, true, null));
    }

    @SuppressWarnings("unchecked") // the timeline's ListView holds rows of several types, all read as Object
    ListView<Object> list() { return (ListView<Object>) scene.getRoot().lookup(".timeline"); }

    VirtualFlow<?> flow() { return (VirtualFlow<?>) list().lookup(".virtual-flow"); }

    Composer composer() { return (Composer) scene.getRoot().lookup(".composer"); }

    TextArea input() { return (TextArea) scene.getRoot().lookup(".composer .text-area"); }

    boolean jumpDownShown() { return scene.getRoot().lookup(".jump-down").isVisible(); }

    javafx.scene.control.Button jumpDown() { return (javafx.scene.control.Button) scene.getRoot().lookup(".jump-down"); }

    /** The "Al día" dot in the header: the list follows the newest message. */
    boolean followingShown() { return scene.getRoot().lookup(".following-badge").isVisible(); }

    /** The newest message in the service is a row of the list. */
    boolean showsNewest() {
        String last = svc.messages(CHAT).getLast().id();
        return list().getItems().stream().anyMatch(r -> r instanceof ThreadRows.MessageRow mr && mr.message().id().equals(last));
    }

    /** How far the newest row's bottom is below the viewport's (px; negative is space under it), or null when it isn't drawn. */
    Double newestGap() {
        VirtualFlow<?> flow = flow();
        IndexedCell<?> last = flow.getLastVisibleCell();
        if (last == null || last.getIndex() != list().getItems().size() - 1) return null;
        return belowViewport(last, flow);
    }

    /**
     * How far {@code row}'s bottom is below the viewport's, measured as TimelineScroll does: from the viewport's own top and
     * height, not the flow's bounds, which reach down to a row cut below it.
     */
    private static double belowViewport(IndexedCell<?> row, VirtualFlow<?> flow) {
        return row.localToScene(0, 0).getY() + row.getHeight() - (flow.localToScene(0, 0).getY() + flow.getHeight());
    }

    /** What the list draws last, for a failure to say why the newest message isn't there. */
    private String lastDrawn() {
        VirtualFlow<?> flow = flow();
        IndexedCell<?> last = flow.getLastVisibleCell();
        int n = list().getItems().size();
        if (last == null) return "no cells, " + n + " rows";
        double bottom = belowViewport(last, flow);
        Object item = n == 0 ? null : list().getItems().get(n - 1);
        String newest = item instanceof ThreadRows.MessageRow mr ? mr.message().type() + " '" + mr.message().body() + "'"
                : String.valueOf(item);
        return "last drawn row " + last.getIndex() + " of " + n + ", " + Math.round(last.getHeight()) + " px tall, bottom "
                + Math.round(bottom) + " px off; viewport " + Math.round(flow.getHeight()) + " px; newest " + newest;
    }

    /** Where the row of message {@code id} is drawn (its top, in the scene), or null when it isn't on screen. */
    Double yOf(String id) {
        VirtualFlow<?> flow = flow();
        for (int i = flow.getFirstVisibleCell().getIndex(); i <= flow.getLastVisibleCell().getIndex(); i++) {
            IndexedCell<?> c = flow.getVisibleCell(i);
            if (c != null && c.getItem() instanceof ThreadRows.MessageRow mr && mr.message().id().equals(id)) {
                return c.localToScene(0, 0).getY();
            }
        }
        return null;
    }

    /**
     * The message read: the one whose top is nearest above the middle of the view, as the list holds it (a picture
     * growing at the top, or the box at the bottom, moves neither it nor what's around it).
     */
    String readMessage() {
        VirtualFlow<?> flow = flow();
        // The viewport's top, as TimelineScroll measures it: the flow's bounds reach up to a row cut above it (-9 px), and
        // a message read from there wasn't the one the list holds (CI's Linux, now and then, when the text grew).
        double top = flow.localToScene(0, 0).getY(), middle = top + flow.getHeight() / 2;
        String read = null;
        for (int i = flow.getFirstVisibleCell().getIndex(); i <= flow.getLastVisibleCell().getIndex(); i++) {
            IndexedCell<?> c = flow.getVisibleCell(i);
            if (c == null || !(c.getItem() instanceof ThreadRows.MessageRow mr)) continue;
            double y = c.localToScene(0, 0).getY();
            if (y > middle && read != null) break;
            if (y >= top) read = mr.message().id();
        }
        if (read == null) throw new AssertionError("no message's top in view");
        return read;
    }

    /** Until the newest message is at the bottom, edge to edge, and stays so once all is done. */
    double untilAtBottom() throws InterruptedException {
        FxWait.until("the newest message is at the bottom", () -> {
            Double gap = newestGap();
            return gap != null && gap <= 1 && gap > -6;
        });
        FxWait.settled();
        FxWait.until("the newest message is still at the bottom", () -> {
            Double gap = newestGap();
            return gap != null && gap <= 1 && gap > -6;
        });
        return FxWait.fx(this::newestGap);
    }

    /** One turn of the wheel or trackpad: {@code dy} up (positive) or down (negative), as a person would. */
    void wheel(double dy) throws InterruptedException {
        FxWait.fx(() -> {
            Event.fireEvent(flow(), new ScrollEvent(ScrollEvent.SCROLL, 200, 200, 200, 200, false, false, false, false,
                    false, false, 0, dy, 0, dy, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            return null;
        });
        FxWait.pulses(1); // the list lays out each turn
    }

    /** Up into older messages, far enough that the newest is out of view. */
    void scrollUp() throws InterruptedException {
        for (int i = 0; i < 8; i++) wheel(120);
        FxWait.until("the list stops following the newest", this::jumpDownShown);
        FxWait.settled();
        assertNull(FxWait.fx(this::newestGap), "precondition: the newest is out of view");
    }

    /** A key pressed in the message box. */
    void key(KeyCode code, boolean shift) throws InterruptedException {
        FxWait.fx(() -> {
            input().requestFocus();
            input().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
            return null;
        });
    }

    /** {@code text} in the message box, one line at a time, a frame between each, as typing it would. */
    void type(String text) throws InterruptedException {
        String[] lines = text.split("\n", -1);
        StringBuilder sofar = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sofar.append(i == 0 ? "" : "\n").append(lines[i]);
            String now = sofar.toString();
            FxWait.fx(() -> { input().setText(now); input().end(); return null; });
            FxWait.pulses(1);
        }
    }

    Message newest() { return svc.messages(CHAT).getLast(); }

    /** ⌘+ / ⌘- / ⌘0 on the window: the text bigger, smaller or back to 100 % (Ctrl on Linux: both are pressed). */
    void zoom(KeyCode code) throws InterruptedException {
        FxWait.fx(() -> {
            scene.getRoot().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, true));
            return null;
        });
        FxWait.settled();
    }

    /** A key pressed on the message list itself (it has the focus, as after F6 or a click on a message). */
    void listKey(KeyCode code) throws InterruptedException {
        FxWait.fx(() -> {
            list().requestFocus();
            list().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            return null;
        });
        FxWait.settled();
    }

    /** The scrollbar's thumb pressed, moved to {@code fraction} of the way down (0 top, 1 bottom), and let go. */
    void dragScrollbar(double fraction) throws InterruptedException {
        FxWait.fx(() -> {
            javafx.scene.control.ScrollBar bar = list().lookupAll(".scroll-bar").stream()
                    .filter(n -> n instanceof javafx.scene.control.ScrollBar sb
                            && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL)
                    .map(n -> (javafx.scene.control.ScrollBar) n).findFirst().orElseThrow();
            bar.fireEvent(mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED));
            bar.setValue(bar.getMin() + fraction * (bar.getMax() - bar.getMin()));
            bar.fireEvent(mouse(javafx.scene.input.MouseEvent.MOUSE_RELEASED));
            return null;
        });
        FxWait.settled();
    }

    private static javafx.scene.input.MouseEvent mouse(javafx.event.EventType<javafx.scene.input.MouseEvent> type) {
        return new javafx.scene.input.MouseEvent(type, 5, 5, 5, 5, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, true, null);
    }

    /**
     * Closes the window and lets its view go: disposed, and out of its scene. A closed window keeps its scene, and
     * the message box's caret, focused when it closed, kept blinking (JavaFX's own timeline) with the whole view held:
     * eighty of them ran the test run out of heap. In the app a window that closes loses the focus; here it doesn't.
     */
    void close() throws InterruptedException {
        svc.stop();
        FxWait.fx(() -> {
            view.dispose();
            stage.close();
            scene.setRoot(new javafx.scene.layout.Pane());
            return null;
        });
    }

    /** Every frame drawn from now on, until {@link Frames#stop}. */
    Frames watch(Frames.Probe probe) throws InterruptedException {
        Frames f = new Frames(probe);
        FxWait.fx(() -> { scene.addPostLayoutPulseListener(f.listener); return null; });
        return f;
    }

    /**
     * What each frame showed. Listeners after a layout run in the order they were added, so this one, added after the
     * list's own, sees the frame as it will be drawn.
     */
    final class Frames {
        @FunctionalInterface
        interface Probe {
            /** Null when the frame is right, else what's wrong with it. */
            String check() throws Exception; // NOPMD: SignatureDeclareThrowsException, a callback: each test's probe looks as it needs
        }

        private final Probe probe;
        private final List<String> wrong = new ArrayList<>();
        private int count;
        final Runnable listener = this::frame;

        private Frames(Probe probe) {
            this.probe = probe;
        }

        private void frame() {
            count++;
            try {
                String w = probe.check();
                if (w != null && wrong.size() < 20) wrong.add("frame " + count + ": " + w);
            } catch (Exception e) {
                wrong.add("frame " + count + ": " + e);
            }
        }

        /** Stops watching once the window has done all it was asked, and fails with the frames that were wrong. */
        void stop(String what) throws InterruptedException {
            FxWait.settled();
            FxWait.fx(() -> { scene.removePostLayoutPulseListener(listener); return null; });
            if (!wrong.isEmpty()) fail(what + ": " + wrong.size() + " of " + count + " frames wrong: " + wrong);
            assertTrue(count > 0, "no frame was drawn");
        }
    }

    /** A frame is right when the newest message is drawn with its bottom where it was ({@code gap}, ±1 px). */
    Frames.Probe atBottom(double gap) {
        return () -> {
            Double now = newestGap();
            if (now == null) return "the newest message isn't drawn (" + lastDrawn() + ")";
            return Math.abs(now - gap) <= 1 ? null : "the newest message's bottom is " + Math.round(now - gap) + " px off";
        };
    }

    /** A frame is right when the newest message is drawn wholly in view, at the bottom or above it (a short chat). */
    Frames.Probe inView() {
        return () -> {
            Double now = newestGap();
            if (now == null) return "the newest message isn't drawn (" + lastDrawn() + ")";
            return now <= 1 ? null : "the newest message is cut off by " + Math.round(now) + " px";
        };
    }

    /** Up in older messages, and every frame from there watched: what's being read stays where it is. */
    Frames readingOlder() throws InterruptedException {
        scrollUp();
        String read = FxWait.fx(this::readMessage);
        return watch(stays(read, FxWait.fx(() -> yOf(read))));
    }

    /** Where the list is scrolled to, 0 (its start) to 1 (its end). */
    double flowPosition() { return flow().getPosition(); }

    /** A frame is right when message {@code id} is drawn at {@code y} (±1 px). */
    Frames.Probe stays(String id, double y) {
        double listY = flow().localToScene(0, 0).getY();
        return () -> {
            Double now = yOf(id);
            if (now == null) return "the message read is gone from view";
            if (Math.abs(now - y) <= 1) return null;
            // Which moved: the list in the window (a header growing), or the rows in the list (the flow's own layout).
            double listMoved = flow().localToScene(0, 0).getY() - listY;
            return "the message read moved " + Math.round(now - y) + " px (the list " + Math.round(listMoved) + " px, position "
                    + String.format(java.util.Locale.ROOT, "%.4f", flow().getPosition()) + ")";
        };
    }

    /** Fires a click on {@code n}. */
    static void click(Node n) {
        n.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false, true, null));
    }
}

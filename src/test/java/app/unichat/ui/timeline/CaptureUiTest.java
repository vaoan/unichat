package app.unichat.ui.timeline;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.desktop.Notifier;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Capturar conversación…": a stretch picked between two markers (all between them in, none left out; each end moved,
 * taken away and put back), then a picture of it as the chat draws it, sent to a chat, saved or copied.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class CaptureUiTest {
    private static final String CHAT = "!family:demo";
    private MockChatService svc;
    private Stage stage;
    private MainView view;
    private ThreadPane thread;
    @TempDir
    Path dir;

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

    @BeforeEach
    void open() throws Exception {
        svc = new MockChatService();
        svc.noAutoReplies();
        thread = FxWait.fx(() -> {
            stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            stage.setScene(new Scene(view, 900, 700));
            stage.show();
            view.openChatId(CHAT);
            return find(view);
        });
    }

    @AfterEach
    void close() throws Exception {
        svc.stop();
        FxWait.fx(() -> {
            view.dispose();
            stage.close();
            stage.getScene().setRoot(new javafx.scene.layout.Pane());
            return null;
        });
    }

    private static ThreadPane find(Node n) {
        if (n instanceof ThreadPane t) return t;
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) {
            ThreadPane t = find(c);
            if (t != null) return t;
        }
        return null;
    }

    private List<Message> chat() {
        return svc.messages(CHAT);
    }

    @SuppressWarnings("unchecked")
    private ListView<ThreadRows.Row> list() throws Exception {
        return FxWait.fx(() -> (ListView<ThreadRows.Row>) thread.lookup(".timeline"));
    }

    /** Space on {@code m}, as a click on it while picking. */
    private void press(Message m) throws Exception {
        ListView<ThreadRows.Row> l = list();
        FxWait.fx(() -> {
            for (int i = 0; i < l.getItems().size(); i++) {
                if (l.getItems().get(i) instanceof ThreadRows.MessageRow mr && mr.message().id().equals(m.id())) l.getSelectionModel().select(i);
            }
            l.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false));
            return null;
        });
    }

    private String count() throws Exception {
        return FxWait.fx(() -> ((Label) thread.lookup(".selection-count")).getText());
    }

    private void start(Message m) throws Exception {
        MenuItem capture = FxWait.fx(() -> thread.actions().messageMenu(m).getItems().stream()
                .filter(i -> "Capturar conversación…".equals(i.getText())).findFirst().orElseThrow());
        FxWait.fx(() -> { capture.fire(); return null; });
    }

    private List<String> ends() throws Exception {
        FxWait.settled();
        return FxWait.fx(() -> thread.lookupAll(".range-end-label").stream().map(n -> ((Label) n).getText()).toList());
    }

    @Test
    void aStretchIsEverythingBetweenItsTwoMarkers() throws Exception {
        List<Message> all = chat();
        Message a = all.get(all.size() - 6), b = all.get(all.size() - 4), c = all.get(all.size() - 2);
        start(b);
        assertEquals("Capturando 1 mensaje", count(), "the first is both ends");
        FxWait.settled();
        assertTrue(FxWait.fx(() -> thread.lookup(".range-marker-start") != null && thread.lookup(".range-marker-end") != null),
                "its markers: «Desde aquí» and «Hasta aquí»");
        press(c);
        assertEquals("Capturando 3 mensajes", count(), "everything between: nothing left out");
        FxWait.settled();
        assertEquals(3, (long) FxWait.fx(() -> thread.lookupAll(".row-cell").stream()
                .filter(n -> n.getPseudoClassStates().contains(MessageSelection.PICKED)).count()), "and drawn as one band");
        press(a);
        assertEquals("Capturando 5 mensajes", count());
        press(b); // inside: the nearer end comes to it
        assertEquals("Capturando 3 mensajes", count(), "from b to c now: the middle can't be left out, only the ends move");
        assertEquals(2, ends().size(), "both ends said under the header, each with its ✕");
    }

    @Test
    void anEndTakenAwayIsPutBackByTheNextClick() throws Exception {
        List<Message> all = chat();
        Message a = all.get(all.size() - 5), b = all.get(all.size() - 2);
        start(a);
        press(b);
        Button removeEnd = FxWait.fx(() -> thread.lookupAll(".range-ends .button").stream().map(n -> (Button) n)
                .filter(x -> x.getAccessibleText().startsWith("Quitar «Hasta:")).findFirst().orElseThrow());
        FxWait.fx(() -> { removeEnd.fire(); return null; });
        assertEquals("Capturando 1 mensaje", count(), "its start alone");
        assertEquals(1, ends().size());
        press(all.get(all.size() - 3));
        assertEquals("Capturando 3 mensajes", count(), "the end is back where clicked");
    }

    private Node sheet() throws Exception {
        return FxWait.fx(() -> stage.getScene().getRoot().lookup(".capture-sheet"));
    }

    private void captureLast(int n) throws Exception {
        List<Message> all = chat();
        // The window shows new messages a moment later (MainView paces its redraws), and one still going out can't be an
        // end (its id is a temporary one): pick once the newest is listed and the server has it.
        FxWait.until("the newest is listed and sent", () -> all.getLast().status() != app.unichat.model.MessageStatus.SENDING
                && FxWait.fx(() -> list().getItems().stream()
                .anyMatch(r -> r instanceof ThreadRows.MessageRow mr && mr.message().id().equals(all.getLast().id()))));
        start(all.get(all.size() - n));
        press(all.getLast());
        Button go = FxWait.fx(() -> thread.lookupAll(".selection-bar .button").stream().map(x -> (Button) x)
                .filter(x -> "Capturar…".equals(x.getText())).findFirst().orElseThrow());
        FxWait.fx(() -> { go.fire(); return null; });
        FxWait.until("the capture is ready", () -> FxWait.fx(() -> sheet() != null && !sheet().lookupAll(".capture-picture").isEmpty()));
    }

    private List<ImageView> pictures() throws Exception {
        return FxWait.fx(() -> sheet().lookupAll(".capture-picture").stream().map(n -> (ImageView) n).toList());
    }

    @Test
    void oneLongPictureOrPiecesAPhonesScreenTall() throws Exception {
        for (int i = 0; i < 10; i++) svc.sendText(CHAT, "mensaje número " + i + " con algo de texto para que ocupe su lugar", null).get();
        captureLast(14);
        assertEquals(1, pictures().size(), "one long picture to start");
        double whole = FxWait.fx(() -> pictures().getFirst().getImage().getHeight());
        ToggleButton phone = FxWait.fx(() -> sheet().lookupAll(".toggle-button").stream().map(n -> (ToggleButton) n)
                .filter(t -> "Varias para teléfono".equals(t.getText())).findFirst().orElseThrow());
        FxWait.fx(() -> { phone.fire(); return null; });
        List<ImageView> pieces = pictures(); // shown small: 380 wide, each a phone's screen tall
        assertTrue(pieces.size() > 1, "a dozen messages are more than a screen: " + pieces.size() + " pieces of " + whole);
        double width = pieces.getFirst().getImage().getWidth();
        for (ImageView p : pieces) assertEquals(width * 16 / 9, p.getImage().getHeight(), 2, "all one size");
        assertTrue(pieces.size() * width * 16 / 9 >= whole, "nothing lost");
    }

    @Test
    void itGoesToTheChatsChosenAsPhotosInOrder() throws Exception {
        captureLast(3);
        Button send = FxWait.fx(() -> sheet().lookupAll(".button").stream().map(n -> (Button) n)
                .filter(b -> "Enviar a un chat…".equals(b.getText())).findFirst().orElseThrow());
        FxWait.fx(() -> { send.fire(); return null; });
        int before = svc.messages("!mom:demo").size();
        FxWait.fx(() -> {
            ListView<?> chats = (ListView<?>) sheet().lookup(".forward-chats");
            for (int i = 0; i < chats.getItems().size(); i++) {
                if (((app.unichat.model.Chat) chats.getItems().get(i)).id().equals("!mom:demo")) chats.getSelectionModel().select(i);
            }
            chats.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false));
            ((Button) sheet().lookupAll(".button").stream().filter(b -> "Enviar".equals(((Button) b).getText())).findFirst().orElseThrow()).fire();
            return null;
        });
        FxWait.until("the picture went", () -> svc.messages("!mom:demo").size() == before + 1);
        Message sent = svc.messages("!mom:demo").getLast();
        assertEquals(MessageType.FILE, sent.type(), "one long picture goes whole, as a document: as a photo it was shrunk to a strip");
        assertTrue(sent.outgoing());
    }

    @Test
    void itCanBeSavedToAFolder() throws Exception {
        captureLast(3);
        FxWait.fx(() -> { thread.captureSheet().useFolderChooser(w -> dir.toFile()); return null; });
        Button save = FxWait.fx(() -> sheet().lookupAll(".button").stream().map(n -> (Button) n)
                .filter(b -> "Guardar…".equals(b.getText())).findFirst().orElseThrow());
        FxWait.fx(() -> { save.fire(); return null; });
        FxWait.until("saved", () -> {
            try (var s = Files.list(dir)) {
                return s.anyMatch(p -> p.getFileName().toString().startsWith("Conversación ") && p.toString().endsWith(".png"));
            }
        });
    }

    @Test
    void itsMenuStartsItFromAnyMessage() throws Exception {
        Message m = chat().getFirst();
        assertTrue(FxWait.fx(() -> thread.actions().messageMenu(m).getItems().stream()
                .anyMatch(i -> "Capturar conversación…".equals(i.getText()))));
        assertFalse(FxWait.fx(() -> thread.lookup(".range-ends").isVisible()), "nothing picked: no ends to say");
    }

    @Test
    void itCanBeCopied() throws Exception {
        captureLast(3);
        Button copy = FxWait.fx(() -> sheet().lookupAll(".button").stream().map(n -> (Button) n)
                .filter(b -> "Copiar".equals(b.getText())).findFirst().orElseThrow());
        FxWait.fx(() -> { copy.fire(); return null; });
        FxWait.until("on the clipboard: the picture and its file", () -> FxWait.fx(() -> {
            javafx.scene.input.Clipboard c = javafx.scene.input.Clipboard.getSystemClipboard();
            return c.hasImage() && c.hasFiles() && c.getFiles().getFirst().getName().endsWith(".png");
        }));
    }

    @Test
    void theFooterCanBeLeftOut() throws Exception {
        captureLast(3);
        double with = FxWait.fx(() -> pictures().getFirst().getImage().getHeight());
        javafx.scene.control.CheckBox footer = FxWait.fx(() -> (javafx.scene.control.CheckBox) sheet().lookup(".check-box"));
        FxWait.fx(() -> { footer.fire(); return null; });
        FxWait.until("made again without it", () -> FxWait.fx(() -> !pictures().isEmpty() && pictures().getFirst().getImage().getHeight() < with));
    }

    @Test
    void theMarkersOwnCrossTakesItsEndAwayWithoutTouchingTheMessage() throws Exception {
        List<Message> all = chat();
        start(all.get(all.size() - 3));
        press(all.getLast());
        FxWait.settled();
        Button x = FxWait.fx(() -> (Button) thread.lookup(".range-marker-end .button"));
        FxWait.fx(() -> { // a real click, through the list's own handling while picking
            javafx.geometry.Bounds b = x.localToScene(x.getBoundsInLocal());
            for (var type : List.of(javafx.scene.input.MouseEvent.MOUSE_PRESSED, javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED)) {
                javafx.event.Event.fireEvent(x, new javafx.scene.input.MouseEvent(type, 2, 2, b.getCenterX(), b.getCenterY(),
                        javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false, false, null));
            }
            return null;
        });
        assertEquals("Capturando 1 mensaje", count(), "only its start is left");
    }

    private String status() throws Exception {
        return FxWait.fx(() -> sheet().lookupAll(".label").stream().map(n -> ((Label) n).getText())
                .filter(t -> t != null && (t.startsWith("Lista") || t.startsWith("No se pudo") || t.startsWith("Preparando"))).findFirst().orElse(""));
    }

    @Test
    void aStretchOfTooManyMessagesIsRefusedBeforeAnythingIsDrawn() throws Exception {
        List<Message> many = new java.util.ArrayList<>();
        for (int i = 0; i <= ConversationShot.MAX_MESSAGES; i++) many.add(chat().get(i % chat().size()));
        java.util.concurrent.atomic.AtomicInteger drawn = new java.util.concurrent.atomic.AtomicInteger();
        CompletableFuture<ConversationShot.Shot> f = FxWait.fx(() -> ConversationShot.take(new Label("encabezado"), many, mr -> {
            drawn.incrementAndGet();
            return new Label(mr.message().body());
        }, stage.getScene(), ConversationShot.WIDTH, false, java.time.Duration.ZERO));
        java.util.concurrent.ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class, f::get);
        assertTrue(e.getCause() instanceof ConversationShot.TooLong);
        assertEquals(0, drawn.get(), "not one row made");
    }

    @Test
    void whatsTallerThanAPictureCanBeIsRefusedBeforeItsDrawn() throws Exception {
        List<Message> some = chat().subList(chat().size() - 2, chat().size());
        CompletableFuture<ConversationShot.Shot> f = FxWait.fx(() -> ConversationShot.take(new Label("encabezado"), some, mr -> {
            javafx.scene.layout.Region tall = new javafx.scene.layout.Region();
            tall.setPrefHeight(ConversationShot.MAX_HEIGHT_PX / 3.0); // two of them: past the pixels allowed, measured laid out
            return tall;
        }, stage.getScene(), ConversationShot.WIDTH, false, java.time.Duration.ZERO));
        java.util.concurrent.ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class, f::get);
        assertTrue(e.getCause() instanceof ConversationShot.TooLong, String.valueOf(e.getCause()));
    }

    @Test
    void thePreviewIsASmallCopyNeverTheWholePicture() throws Exception {
        captureLast(3);
        assertEquals(380.0, FxWait.fx(() -> pictures().getFirst().getImage().getWidth()),
                "made small: a long one whole is past what a graphics card shows");
    }

    @Test
    void savingLeavesNoCopyAnywhereElse() throws Exception {
        captureLast(3);
        Path sending = app.unichat.settings.Settings.dataDir().resolve("cache").resolve("sending");
        java.util.Set<String> before = names(sending);
        FxWait.fx(() -> { thread.captureSheet().useFolderChooser(w -> dir.toFile()); return null; });
        Button save = FxWait.fx(() -> sheet().lookupAll(".button").stream().map(n -> (Button) n)
                .filter(b -> "Guardar…".equals(b.getText())).findFirst().orElseThrow());
        FxWait.fx(() -> { save.fire(); return null; });
        FxWait.until("saved", () -> names(dir).size() == 1);
        assertEquals(before, names(sending), "straight into the folder chosen");
    }

    private static java.util.Set<String> names(Path folder) throws java.io.IOException {
        if (!Files.isDirectory(folder)) return java.util.Set.of();
        try (var s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    void aChatsVeryLongNameStillMakesAFile() throws Exception {
        FxWait.fx(() -> { svc.chat(CHAT).setTitle("😀".repeat(120)); return null; });
        itCanBeSavedToAFolder();
    }

    @Test
    void anEndStillGoingOutCantBePicked() throws Exception {
        svc.sendText(CHAT, "va saliendo", null).get();
        Message going = chat().getLast();
        going.setStatus(app.unichat.model.MessageStatus.SENDING);
        start(chat().get(chat().size() - 3));
        press(going);
        assertEquals("Capturando 1 mensaje", count(), "its id is a temporary one: the range would empty when it arrives");
    }

    @Test
    void theStripsCrossLeavesTheKeyboardOnTheMessages() throws Exception {
        List<Message> all = chat();
        start(all.get(all.size() - 3));
        press(all.getLast());
        Button x = FxWait.fx(() -> (Button) thread.lookup(".range-ends .button"));
        FxWait.fx(() -> { x.requestFocus(); x.fire(); return null; });
        assertTrue(FxWait.fx(() -> stage.getScene().getFocusOwner() == list()), "not lost with the button rebuilt");
    }

    @Test
    void anEndsRowShowsNoMessageButtonsWhilePicking() throws Exception {
        List<Message> all = chat();
        start(all.get(all.size() - 3));
        press(all.getLast());
        FxWait.settled();
        Node line = FxWait.fx(() -> thread.lookup(".range-marker-end").getParent().getChildrenUnmodifiable().stream()
                .filter(n -> n instanceof MessageLine).findFirst().orElseThrow());
        FxWait.fx(() -> {
            Event.fireEvent(line, new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_ENTERED, 1, 1, 1, 1,
                    javafx.scene.input.MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null));
            return null;
        });
        assertTrue(FxWait.fx(() -> line.lookupAll(".hover-actions").stream().noneMatch(Node::isVisible)), "picking: a click only picks");
    }

    @Test
    void itsHeaderNamesTheNetworkAndHowManyNeverTheAccount() throws Exception {
        app.unichat.model.Chat c = FxWait.fx(() -> svc.chat(CHAT));
        String onScreen = FxWait.fx(() -> ((Label) thread.lookup(".thread-header .thread-subtitle")).getText());
        assertTrue(onScreen.contains("+57"), "the window's header shows the account it's on: " + onScreen);
        String captured = FxWait.fx(() -> ((Label) thread.captureHeader(c).lookup(".thread-subtitle")).getText());
        assertEquals("WhatsApp · " + c.members().size() + " participantes", captured, "no phone number of one's own, no «silenciado»");
    }

    @Test
    void itsPiecesEndAfterAMessageNeverAfterADaysLine() throws Exception {
        List<Message> some = chat().subList(chat().size() - 4, chat().size());
        ConversationShot.Shot shot = FxWait.fx(() -> ConversationShot.take(new Label("encabezado"), some,
                mr -> new Label(mr.message().body()), stage.getScene(), ConversationShot.WIDTH, false, java.time.Duration.ZERO)).get();
        assertEquals(1 + some.size(), shot.ends().size(), "the header's end and each message's: day lines aren't places to cut");
    }
}

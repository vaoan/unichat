package app.unichat.ui;

import app.unichat.Present;
import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.RoomFeatures;
import app.unichat.model.StickerPack;
import app.unichat.service.MockChatService;
import app.unichat.settings.Settings;
import app.unichat.ui.desktop.Notifier;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typing one emoji alone in the message box shows the stickers of that emoji above it (the demo's set gives 😂 its
 * "jaja"); one sent takes the emoji's place. Nothing for anything else typed, while editing, or with it turned off.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class StickerSuggestionsUiTest {
    private MockChatService svc;
    @SuppressWarnings("NullAway.Init") // the @BeforeEach sets it
    private Stage stage;
    @SuppressWarnings("NullAway.Init") // the @BeforeEach sets it
    private MainView view;
    private String chat;

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
        openWith(new MockChatService());
    }

    private void openWith(MockChatService service) throws InterruptedException {
        svc = service;
        svc.noAutoReplies();
        chat = svc.chats().stream().filter(c -> c.features().accepts(RoomFeatures.STICKER)).findFirst().orElseThrow().id();
        FxWait.onFx(() -> {
            stage = new Stage();
            stage.setOpacity(0);
            view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            stage.setScene(new Scene(view, 900, 700));
            stage.show();
            view.openChatId(chat);
        });
        FxWait.until("the message box", () -> FxWait.fx(() -> view.lookup(".composer-input") != null));
    }

    @AfterEach
    void close() throws Exception {
        Settings.get().setSuggestStickers(true);
        svc.stop();
        FxWait.onFx(() -> {
            view.dispose();
            stage.close();
            stage.getScene().setRoot(new javafx.scene.layout.Pane());
        });
    }

    private TextArea box() throws InterruptedException {
        return FxWait.fx(() -> (TextArea) view.lookup(".composer-input"));
    }

    private void type(String text) throws InterruptedException {
        FxWait.onFx(() -> box().setText(text));
        FxWait.settled();
    }

    private ScrollPane suggestions() throws InterruptedException {
        return FxWait.fx(() -> (ScrollPane) view.lookup(".sticker-suggestions"));
    }

    /** The stickers offered, or none when the line is hidden. */
    private List<Node> offered() throws InterruptedException {
        return FxWait.fx(() -> suggestions().isVisible() ? List.copyOf(((HBox) suggestions().getContent()).getChildren()) : List.of());
    }

    private List<Message> stickersSent() {
        return svc.messages(chat).stream().filter(m -> m.type() == MessageType.STICKER).toList();
    }

    @Test
    void oneEmojiOffersItsStickersAndOneSentTakesItsPlace() throws Exception {
        assertTrue(offered().isEmpty(), "nothing typed, nothing offered");
        type("😂");
        FxWait.until("the set's 😂 offered", () -> offered().size() == 1);
        assertEquals("Stickers de 😂", FxWait.fx(() -> suggestions().getAccessibleText()));
        Node jaja = offered().getFirst();
        FxWait.onFx(() -> {
            Event.fireEvent(jaja, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
        FxWait.until("the sticker sent", () -> stickersSent().size() == 1);
        assertTrue(Present.of(stickersSent().getFirst().mediaUrl()).endsWith("sticker-jaja"), stickersSent().getFirst().mediaUrl());
        FxWait.until("the emoji gone from the box", () -> FxWait.fx(() -> box().getText().isEmpty()));
        FxWait.settled();
        assertTrue(offered().isEmpty(), "and the line with it");
        assertTrue(svc.messages(chat).stream().noneMatch(m -> "😂".equals(m.body())), "the emoji itself wasn't sent");
    }

    @Test
    void anythingButOneEmojiAloneOffersNothing() throws Exception {
        type("😂");
        FxWait.until("offered", () -> offered().size() == 1);
        for (String t : List.of("😂😂", "jaja 😂", "jaja", "👋")) { // 👋 is the demo's too: the one typed, not any
            type(t);
            if (t.equals("👋")) assertEquals(2, offered().size(), "👋 has two in the demo's set");
            else assertTrue(offered().isEmpty(), "\"" + t + "\" offers nothing");
        }
        type("  😂\uFE0F ");
        assertEquals(1, offered().size(), "spaces and its variation mark aside, it's one emoji");
    }

    @Test
    void setsListedAfterTheEmojiWasTypedBringTheirStickers() throws Exception {
        close();
        CompletableFuture<List<StickerPack.Ref>> listed = new CompletableFuture<>();
        openWith(new MockChatService() {
            @Override
            public CompletableFuture<List<StickerPack.Ref>> stickerPacks() {
                return listed; // Telegram's bridge not ready yet
            }
        });
        type("🙏"); // one no test sends: what's sent stays among the recents, and those are offered too
        assertTrue(offered().isEmpty(), "no set yet");
        StickerPack.Ref demo = new MockChatService().stickerPacks().get().getFirst();
        listed.complete(List.of(demo));
        FxWait.until("the set's 🙏, once the sets are listed and it's brought", () -> offered().size() == 1);
    }

    @Test
    void turnedOffTheRowShowingGoesAndNoneComes() throws Exception {
        type("😂");
        FxWait.until("offered while on", () -> offered().size() == 1);
        Settings.get().setSuggestStickers(false);
        FxWait.onFx(view::settingsSynced);  // as Ajustes and the cloud apply a change
        FxWait.settled();
        assertTrue(offered().isEmpty(), "the row showing went at once, not at the next key");
        type("🙏");
        assertTrue(offered().isEmpty(), "and none comes");
    }

    @Test
    void editingASentMessageOffersNothingThoughTheTextIsTheSame() throws Exception {
        type("😂");
        FxWait.until("offered", () -> offered().size() == 1);
        svc.sendText(chat, "😂", null).get();
        Message mine = svc.messages(chat).getLast();
        FxWait.onFx(() -> Present.of(find(view)).composer().edit(mine));  // the box already holds "😂": no text changes
        FxWait.settled();
        assertTrue(offered().isEmpty(), "an edit stays a text: no sticker can take its place");
    }

    @Test
    void textTypedOrAnotherChatOpenedWhileItGoesStays() throws Exception {
        close();
        CompletableFuture<Void> going = new CompletableFuture<>();
        openWith(new MockChatService() {
            @Override
            public CompletableFuture<Void> sendSticker(String chatId, app.unichat.model.MediaLibrary.Item item, @Nullable String replyToId) {
                return going.thenCompose(v -> super.sendSticker(chatId, item, replyToId)); // a slow bridge
            }
        });
        type("😂");
        FxWait.until("offered", () -> offered().size() == 1);
        press(offered().getFirst());
        type("😂 jaja"); // goes on writing while it's sent
        going.complete(null);
        FxWait.until("sent", () -> stickersSent().size() == 1);
        FxWait.settled();
        assertEquals("😂 jaja", FxWait.fx(() -> box().getText()), "what was written after the click stays");

        String other = svc.chats().stream().map(Chat::id).filter(id -> !id.equals(chat)).findFirst().orElseThrow();
        type("😂");
        FxWait.until("offered", () -> offered().size() == 1);
        going.obtrudeValue(null);
        press(offered().getFirst());
        FxWait.onFx(() -> view.openChatId(other));
        type("nos vemos a las 5");
        FxWait.until("the second sent", () -> stickersSent().size() == 2);
        FxWait.settled();
        assertEquals("nos vemos a las 5", FxWait.fx(() -> box().getText()), "another chat's text isn't emptied");
    }

    @Test
    void aChatThatRefusesStickersHasItsTilesSayItThoughTheTextIsTheSame() throws Exception {
        String refusing = svc.chats().stream().map(Chat::id).filter(id -> !id.equals(chat)).findFirst().orElseThrow();
        FxWait.onFx(() -> {
            Present.of(svc.chat(refusing)).setFeatures(RoomFeatures.parse(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree("{\"id\":\"gm\",\"file\":{\"m.image\":{\"mime_types\":{\"image/*\":2}}}}")));
            view.openChatId(refusing);
        });
        type("😂");
        FxWait.onFx(() -> view.openChatId(chat));
        type("😂");
        FxWait.until("offered here", () -> offered().size() == 1 && FxWait.fx(() -> offered().getFirst().getAccessibleText()).endsWith(", enviar"));
        FxWait.onFx(() -> view.openChatId(refusing));  // its draft is the same "😂": the text doesn't change
        FxWait.settled();
        assertTrue(FxWait.fx(() -> offered().getFirst().getAccessibleText()).contains("no acepta stickers"),
                "the tiles are this chat's: " + FxWait.fx(() -> offered().getFirst().getAccessibleText()));
    }

    @Test
    void theSuggestionsAreReachedByKeyboardFromTheBox() throws Exception {
        type("😂");
        FxWait.until("offered", () -> offered().size() == 1);
        Node tile = offered().getFirst();
        assertTrue(FxWait.fx(tile::isFocusTraversable), "Tab reaches it");
        assertTrue(FxWait.fx(tile::getAccessibleText).contains("😂"), "and says which: " + FxWait.fx(tile::getAccessibleText));
        FxWait.onFx(() -> box().requestFocus());
        List<Node> passed = new java.util.ArrayList<>();
        for (int i = 0; i < 4 && FxWait.fx(() -> view.getScene().getFocusOwner()) != tile; i++) { // the box's buttons, then it
            FxWait.onFx(() -> {
                Node at = view.getScene().getFocusOwner();
                passed.add(at);
                Event.fireEvent(at, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, true, false, false, false));
            });
            FxWait.settled();
        }
        assertSame(tile, FxWait.fx(() -> view.getScene().getFocusOwner()), "⇧Tab from the box reaches it, past " + passed);
    }

    private static void press(Node tile) throws InterruptedException {
        FxWait.onFx(() -> {
            Event.fireEvent(tile, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
    }

    private static app.unichat.ui.timeline.@Nullable ThreadPane find(Node n) {
        if (n instanceof app.unichat.ui.timeline.ThreadPane t) return t;
        if (n instanceof javafx.scene.Parent p) for (Node c : p.getChildrenUnmodifiable()) {
            app.unichat.ui.timeline.ThreadPane t = find(c);
            if (t != null) return t;
        }
        return null;
    }
}

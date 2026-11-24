package app.unichat.ui.chats;

import app.unichat.ui.FxWait;
import app.unichat.autosave.SaveFolders;
import app.unichat.model.SaveKind;
import app.unichat.service.MockChatService;
import java.util.concurrent.ExecutionException;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chat's options: saving what arrives turns on with every kind, each kind turns off apart, and it says where. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ChatOptionsUiTest {
    /** The chat's options, opened in a window of their own; saving starts off, what's sent and what I send. */
    private static StackPane open(MockChatService svc, String id) throws InterruptedException, ExecutionException {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        // The test data folder is the whole run's: start from a known state, and leave it so.
        svc.setMediaHidden(id, false).get();
        svc.setAutoSave(id, false, java.util.Set.of()).get();
        svc.setAutoSave(id, true, java.util.Set.of()).get();
        StackPane overlay = FxWait.fx(() -> {
            StackPane o = new StackPane();
            Stage stage = new Stage();
            stage.setOpacity(0);
            stage.setScene(new Scene(o, 600, 800));
            stage.show();
            new ChatOptions(svc, n -> o.getChildren().setAll(n), () -> o.getChildren().clear(), t -> { }).open(svc.chat(id), () -> { });
            return o;
        });
        FxWait.until("the sheet shows", () -> overlay.lookup(".chat-options") != null);
        return overlay;
    }

    private static CheckBox box(StackPane overlay, String text) throws InterruptedException {
        return FxWait.fx(() -> overlay.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
                .filter(b -> b.getText().equals(text)).findFirst().orElseThrow());
    }

    /**
     * Saving what's sent on and what I send off left the person's own photos out without a word: they thought saving
     * was broken. A note says so, only then, and goes with what I send turned on.
     */
    @Test
    void whileOnlyWhatOthersSendIsSavedANoteSaysMineIsnt() throws Exception {
        MockChatService svc = new MockChatService();
        String id = "!family:demo";
        StackPane overlay = open(svc, id);
        Node note = FxWait.fx(() -> overlay.lookup(".chat-options-mine-note"));
        assertFalse(FxWait.fx(note::isVisible), "nothing saved: nothing to say");
        CheckBox theirs = box(overlay, "Guardar lo que envíen en este chat");
        CheckBox mine = box(overlay, "Guardar lo que yo envíe en este chat");
        FxWait.onFx(theirs::fire);
        assertTrue(FxWait.fx(note::isVisible), "theirs saved, mine not: said");
        assertTrue(FxWait.fx(((javafx.scene.control.Label) note)::getText).contains("tú"));
        FxWait.onFx(mine::fire);
        assertFalse(FxWait.fx(note::isVisible), "mine saved too");
        FxWait.onFx(() -> {
            mine.fire();
            theirs.fire();
        });
        assertFalse(FxWait.fx(note::isVisible), "nothing saved again");
        FxWait.onFx(() -> overlay.getScene().getWindow().hide());
    }

    /** "Textos" is a kind like the others, for what others send and for what I send, each apart. */
    @Test
    void theTextsAreAKindOfTheirOwnOnEachSide() throws Exception {
        MockChatService svc = new MockChatService();
        String id = "!family:demo";
        StackPane overlay = open(svc, id);
        CheckBox theirs = box(overlay, "Guardar lo que envíen en este chat");
        CheckBox texts = FxWait.fx(() -> overlay.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
                .filter(b -> "Guardar lo que envíen en este chat: Textos (para poder ver lo que se borre)".equals(b.getAccessibleText()))
                .findFirst().orElseThrow());
        FxWait.onFx(theirs::fire);
        assertTrue(svc.autoSaves(id).contains(SaveKind.TEXTS), "turned on: the texts too");
        FxWait.onFx(texts::fire);
        assertEquals(EnumSet.complementOf(EnumSet.of(SaveKind.TEXTS)), svc.autoSaves(id), "the texts left out alone");
        assertTrue(svc.autoSaves(id, true).isEmpty(), "what I send untouched");
        FxWait.onFx(() -> overlay.getScene().getWindow().hide());
    }

    @Test
    void savingTurnsOnWithEveryKindAndEachTurnsOffApart() throws Exception {
        MockChatService svc = new MockChatService();
        String id = "!family:demo";
        StackPane overlay = open(svc, id);
        List<CheckBox> boxes = FxWait.fx(() -> overlay.lookupAll(".chat-options-kinds .check-box").stream().map(n -> (CheckBox) n).toList());
        CheckBox on = FxWait.fx(() -> overlay.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
                .filter(b -> b.getText().equals("Guardar lo que envíen en este chat")).findFirst().orElseThrow());
        assertTrue(FxWait.fx(() -> boxes.stream().allMatch(Node::isDisabled)), "kinds wait for the switch");
        FxWait.onFx(on::fire);
        assertEquals(EnumSet.allOf(SaveKind.class), svc.autoSaves(id), "on: everything");
        CheckBox gifs = FxWait.fx(() -> boxes.stream().filter(b -> b.getText().equals("GIFs")).findFirst().orElseThrow());
        FxWait.onFx(gifs::fire);
        assertEquals(EnumSet.complementOf(EnumSet.of(SaveKind.GIFS)), svc.autoSaves(id), "GIFs left out");
        String where = FxWait.fx(() -> overlay.lookupAll(".label").stream().map(n -> ((javafx.scene.control.Label) n).getText())
                .filter(t -> t != null && t.startsWith("Carpeta:")).findFirst().orElseThrow());
        assertTrue(where.contains(SaveFolders.shown(SaveFolders.of(svc.chat(id)))) && where.contains("test-autosave"), where);
        FxWait.onFx(on::fire);
        assertTrue(svc.autoSaves(id).isEmpty(), "off");
        CheckBox showMedia = FxWait.fx(() -> overlay.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
                .filter(b -> b.getText().equals("Mostrar la multimedia en el perfil")).findFirst().orElseThrow());
        assertTrue(FxWait.fx(showMedia::isSelected), "shown unless hidden");
        FxWait.onFx(showMedia::fire);
        assertTrue(svc.mediaHidden(id), "hidden for this chat");
        svc.setMediaHidden(id, false).get();
        // What I send has its own switch and kinds, apart from what others send.
        CheckBox mineOn = FxWait.fx(() -> overlay.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
                .filter(b -> b.getText().equals("Guardar lo que yo envíe en este chat")).findFirst().orElseThrow());
        FxWait.onFx(mineOn::fire);
        assertEquals(EnumSet.allOf(SaveKind.class), svc.autoSaves(id, true), "on: every kind");
        CheckBox minePhotos = FxWait.fx(() -> overlay.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
                .filter(b -> "Guardar lo que yo envíe en este chat: Fotos".equals(b.getAccessibleText())).findFirst().orElseThrow());
        FxWait.onFx(minePhotos::fire);
        assertFalse(svc.autoSaves(id, true).contains(SaveKind.PHOTOS), "its own kinds");
        FxWait.onFx(mineOn::fire);
        assertTrue(svc.autoSaves(id, true).isEmpty(), "and off");

        // Leaving is on its own tab, "Peligroso": nothing of it shows among the everyday options.
        assertTrue(FxWait.fx(() -> overlay.lookupAll(".danger-button").stream().noneMatch(ChatOptionsUiTest::shown)),
                "not on the General tab");
        javafx.scene.control.ToggleButton dangerTab = FxWait.fx(() -> overlay.lookupAll(".media-tab").stream()
                .map(n -> (javafx.scene.control.ToggleButton) n).filter(t -> t.getText().equals("Peligroso")).findFirst().orElseThrow());
        FxWait.onFx(dangerTab::fire);
        assertTrue(FxWait.fx(() -> !shown(showMedia)), "and the General options are hidden there");
        // Leaving asks first; "Cancelar" goes back, only the second yes leaves.
        Button ask = FxWait.fx(() -> overlay.lookupAll(".danger-button").stream().map(n -> (Button) n)
                .filter(ChatOptionsUiTest::shown).findFirst().orElseThrow());
        FxWait.onFx(ask::fire);
        Button cancel = FxWait.fx(() -> overlay.lookupAll(".secondary-button").stream().map(n -> (Button) n)
                .filter(b -> b.isVisible() && b.getText().equals("Cancelar")).findFirst().orElseThrow());
        FxWait.onFx(cancel::fire);
        assertNotNull(svc.chat(id), "not left: asked, then cancelled");
        FxWait.onFx(ask::fire);
        Button yes = FxWait.fx(() -> overlay.lookupAll(".danger-button").stream().map(n -> (Button) n)
                .filter(b -> b.isVisible() && b.getText().startsWith("Sí,")).findFirst().orElseThrow());
        FxWait.onFx(yes::fire);
        FxWait.until("the chat is left", () -> svc.chat(id) == null);
        assertTrue(overlay.getChildren().isEmpty(), "and the sheet closed");
        FxWait.onFx(() -> overlay.getScene().getWindow().hide());
        svc.stop();
    }

    /** On screen: it and everything it's in visible. */
    private static boolean shown(Node n) {
        for (Node at = n; at != null; at = at.getParent()) if (!at.isVisible()) return false;
        return true;
    }
}

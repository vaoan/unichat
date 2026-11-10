package app.unichat.ui;

import app.unichat.autosave.SaveFolders;
import app.unichat.model.SaveKind;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chat's options: saving what arrives turns on with every kind, each kind turns off apart, and it says where. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class ChatOptionsUiTest {
    @Test
    void savingTurnsOnWithEveryKindAndEachTurnsOffApart() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        String id = "!familia:demo";
        // The test data folder is the whole run's: start from a known state, and leave it so.
        svc.setMediaHidden(id, false).get();
        svc.setAutoSave(id, java.util.Set.of()).get();
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
        List<CheckBox> boxes = FxWait.fx(() -> overlay.lookupAll(".chat-options-kinds .check-box").stream().map(n -> (CheckBox) n).toList());
        CheckBox on = FxWait.fx(() -> overlay.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
                .filter(b -> b.getText().equals("Guardar lo que envíen en este chat")).findFirst().orElseThrow());
        assertTrue(FxWait.fx(() -> boxes.stream().allMatch(Node::isDisabled)), "kinds wait for the switch");
        FxWait.fx(() -> { on.fire(); return null; });
        assertEquals(EnumSet.allOf(SaveKind.class), svc.autoSaves(id), "on: everything");
        CheckBox gifs = FxWait.fx(() -> boxes.stream().filter(b -> b.getText().equals("GIFs")).findFirst().orElseThrow());
        FxWait.fx(() -> { gifs.fire(); return null; });
        assertEquals(EnumSet.complementOf(EnumSet.of(SaveKind.GIFS)), svc.autoSaves(id), "GIFs left out");
        String where = FxWait.fx(() -> overlay.lookupAll(".label").stream().map(n -> ((javafx.scene.control.Label) n).getText())
                .filter(t -> t != null && t.startsWith("Carpeta:")).findFirst().orElseThrow());
        assertTrue(where.contains(SaveFolders.shown(SaveFolders.of(svc.chat(id)))) && where.contains("test-autosave"), where);
        FxWait.fx(() -> { on.fire(); return null; });
        assertTrue(svc.autoSaves(id).isEmpty(), "off");
        CheckBox showMedia = FxWait.fx(() -> overlay.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
                .filter(b -> b.getText().equals("Mostrar la multimedia en el perfil")).findFirst().orElseThrow());
        assertTrue(FxWait.fx(showMedia::isSelected), "shown unless hidden");
        FxWait.fx(() -> { showMedia.fire(); return null; });
        assertTrue(svc.mediaHidden(id), "hidden for this chat");
        svc.setMediaHidden(id, false).get();
        FxWait.fx(() -> { overlay.getScene().getWindow().hide(); return null; });
        svc.stop();
    }
}

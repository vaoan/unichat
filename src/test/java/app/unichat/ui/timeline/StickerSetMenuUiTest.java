package app.unichat.ui.timeline;

import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.service.MockChatService;
import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.chats.SidePanel;
import app.unichat.ui.desktop.Notifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Telegram sticker in the chat opens its set in the side panel's Stickers tab from its menu. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class StickerSetMenuUiTest {
    @Test
    void aStickersMenuOpensItsSet() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        var info = new ObjectMapper().readTree("""
                {"mimetype":"image/webp","fi.mau.bridged_sticker":{"network":"telegram","id":"7","pack_url":"https://t.me/addstickers/gatitos"}}""");
        svc.sendSticker("!familia:demo", new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://demo/s1", "image/webp", 64, 64, info), null).get();
        Stage[] window = new Stage[1];
        MainView view = FxWait.fx(() -> {
            window[0] = new Stage();
            window[0].setOpacity(0);
            MainView v = new MainView(svc, window[0], new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            window[0].setScene(new Scene(v, 1000, 700));
            window[0].show();
            v.openChatId("!familia:demo");
            return v;
        });
        try {
            List<Message> msgs = svc.messages("!familia:demo");
            Message sticker = msgs.get(msgs.size() - 1);
            ThreadPane thread = FxWait.fx(() -> find(view, ThreadPane.class));
            MenuItem show = FxWait.fx(() -> thread.actions().messageMenu(sticker).getItems().stream()
                    .filter(i -> "Ver el set de este sticker".equals(i.getText())).findFirst().orElseThrow());
            FxWait.fx(() -> { show.fire(); return null; });
            SidePanel panel = FxWait.fx(() -> find(view, SidePanel.class));
            FxWait.until("the Stickers tab", () -> FxWait.fx(panel::current) == SidePanel.Tab.STICKERS);
            FxWait.until("that set, open", () -> FxWait.fx(() -> find(panel, Button.class, "gatitos")) != null);
            // The demo has no Telegram: the set says it couldn't be fetched rather than loading for ever.
            FxWait.until("why it's not there", () -> FxWait.fx(() -> text(panel)).contains("No se pudo traer este set"));
            assertTrue(FxWait.fx(() -> text(panel)).contains("Sets de Telegram"));
            assertEquals(SidePanel.Tab.STICKERS, FxWait.fx(panel::current));
        } finally {
            svc.stop();
            FxWait.fx(() -> { window[0].close(); return null; });
        }
    }

    private static <T> T find(Node n, Class<T> type) {
        if (type.isInstance(n)) return type.cast(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) {
            T t = find(c, type);
            if (t != null) return t;
        }
        return null;
    }

    private static Button find(Node n, Class<Button> type, String text) {
        if (n instanceof Button b && text.equals(b.getText())) return b;
        if (n instanceof javafx.scene.control.ScrollPane s && s.getContent() != null) return find(s.getContent(), type, text);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) {
            Button t = find(c, type, text);
            if (t != null) return t;
        }
        return null;
    }

    private static String text(Node n) {
        StringBuilder s = new StringBuilder();
        if (n instanceof javafx.scene.control.Labeled l) s.append(l.getText()).append('\n');
        if (n instanceof javafx.scene.text.Text t) s.append(t.getText()).append('\n');
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) s.append(text(sp.getContent()));
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) s.append(text(c));
        return s.toString();
    }
}

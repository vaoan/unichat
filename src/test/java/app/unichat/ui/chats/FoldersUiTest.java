package app.unichat.ui.chats;

import app.unichat.ui.FxWait;
import app.unichat.ui.MainView;
import app.unichat.ui.Theme;
import app.unichat.ui.desktop.Notifier;
import app.unichat.ui.timeline.ThreadPane;
import app.unichat.service.Folder;
import app.unichat.model.Chat;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import app.unichat.Present;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folders end to end on the real window: rail, list of names, inside a folder, menus and dialogs.
 * {@code ./mvnw test -Pheadless-ui -Dtest=FoldersUiTest}: no display, no windows.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class FoldersUiTest {
    private static final String FAMILIA = "!family:demo";
    private static final String SERVER = "!server:demo";

    private MockChatService svc;
    private Scene scene;
    private ChatListPane pane;

    @BeforeAll
    static void toolkit() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false); // each test closes its window: the toolkit must outlive it
    }

    @BeforeEach
    void openWindow() throws Exception {
        svc = new MockChatService();
        scene = fx(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            MainView view = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, url -> { });
            Scene sc = new Scene(view, 1100, 760);
            Theme.register(sc);
            stage.setScene(sc);
            stage.show();
            return sc;
        });
        FxWait.until("the chat list shows", () -> !scene.getRoot().lookupAll(".chat-list .list-cell").isEmpty());
        FxWait.settled();
        pane = fx(() -> (ChatListPane) scene.getRoot().lookupAll("*").stream()
                .filter(ChatListPane.class::isInstance).findFirst().orElseThrow());
    }

    @AfterEach
    void closeWindow() throws Exception {
        svc.stop();
        FxWait.onFx(() -> {
            ((Stage) scene.getWindow()).close();
        });
    }

    @Test
    void theFoldersButtonSitsRightBelowTheInbox() throws Exception {
        assertEquals(1, railIndex("Carpetas"), "the inbox is the first child");
    }

    @Test
    void clickingShowsNamesAndClickingANameGoesIn() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.createFolder("Casa");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);

        fire(railButton("Carpetas"));
        assertTrue(fx(pane::foldersOpen));
        assertEquals("Carpetas", fx(() -> title().getText()));
        assertFalse(fx(() -> showing(inboxList())), "the names cover the inbox");
        assertFalse(fx(() -> showing(chipRow())), "no filter chips in folders");
        List<String> names = texts(".folder-list");
        assertTrue(names.stream().anyMatch(t -> t.startsWith("Trabajo")), names.toString());
        assertTrue(names.stream().anyMatch(t -> t.startsWith("Casa")), names.toString());
        assertEquals(1, fx(() -> buttons("Nueva carpeta").size()));

        clickCell(".folder-list", "Trabajo");
        assertEquals("Trabajo", fx(() -> folderTitle().getText()));
        assertFalse(fx(() -> showing(folderList())), "not an accordion: the names are gone");
        assertTrue(texts(".folder-chats").stream().anyMatch(t -> t.startsWith(title(FAMILIA))));
    }

    @Test
    void noFoldersExplainsHowToStart() throws Exception {
        fire(railButton("Carpetas"));
        assertTrue(fx(() -> scene.getRoot().lookupAll(".empty-label").stream()
                .anyMatch(n -> n instanceof Label l && l.isVisible() && l.getText()
                        .equals("Aún no tienes carpetas. Crea una y añade chats con clic derecho."))));
    }

    @Test
    void goingBackReturnsToTheNames() throws Exception {
        svc.createFolder("Trabajo");
        fire(railButton("Carpetas"));

        clickCell(".folder-list", "Trabajo");
        fire(fx(() -> buttons("Volver a carpetas").get(0)));
        assertTrue(fx(() -> showing(folderList())), "the back button");
        assertTrue(fx(pane::foldersOpen));

        clickCell(".folder-list", "Trabajo");
        key(fx(this::folderChats), KeyCode.ESCAPE);
        assertTrue(fx(() -> showing(folderList())), "Esc");

        clickCell(".folder-list", "Trabajo");
        key(fx(this::folderChats), KeyCode.LEFT);
        assertTrue(fx(() -> showing(folderList())), "left arrow");
    }

    @Test
    void openingAChatStaysInTheFolder() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");

        clickCell(".folder-chats", title(FAMILIA));
        assertEquals(FAMILIA, fx(() -> Present.of(thread().chat()).id()));
        assertTrue(fx(() -> showing(folderChats())), "still inside the folder");
        assertEquals("Trabajo", fx(() -> folderTitle().getText()));
    }

    @Test
    void aChatCanBeInTwoFolders() throws Exception {
        String work = svc.createFolder("Trabajo");
        String home = svc.createFolder("Casa");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        svc.setInFolder(home, Present.of(svc.chat(FAMILIA)), true);
        fire(railButton("Carpetas"));

        clickCell(".folder-list", "Trabajo");
        assertTrue(texts(".folder-chats").stream().anyMatch(t -> t.startsWith(title(FAMILIA))));
        fire(fx(() -> buttons("Volver a carpetas").get(0)));
        clickCell(".folder-list", "Casa");
        assertTrue(texts(".folder-chats").stream().anyMatch(t -> t.startsWith(title(FAMILIA))));
    }

    @Test
    void deletingTheOpenFolderGoesBackToTheNames() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.createFolder("Casa");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");

        svc.deleteFolder(work);
        eventually("back to the names", () -> showing(folderList()));
        assertTrue(fx(pane::foldersOpen));
        assertNotNull(svc.chat(FAMILIA), "the chat itself stays");
        assertTrue(texts(".folder-list").stream().noneMatch(t -> t.startsWith("Trabajo")));
    }

    @Test
    void leavingAChatInsideAFolderJustHidesIt() throws Exception {
        String work = svc.createFolder("Trabajo");
        String key = Present.of(svc.chat(FAMILIA)).prefKey();
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");

        var _ = svc.leaveChat(FAMILIA);
        eventually("it stops showing", () -> folderChats().getItems().isEmpty());
        assertTrue(fx(() -> showing(folderChats())), "still inside the folder");
        assertEquals(List.of(key), svc.folders().get(0).chatKeys(), "the key stays for another installation");
    }

    @Test
    void searchingLeavesTheFolder() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");

        FxWait.onFx(() -> {
            ((TextField) scene.getRoot().lookup(".search-field")).setText(title(FAMILIA));
        });
        FxWait.settled();
        assertFalse(fx(pane::foldersOpen));
        assertTrue(fx(() -> showing(inboxList())));
        assertTrue(fx(() -> inboxList().getItems().stream().anyMatch(c -> c.id().equals(FAMILIA))));
    }

    @Test
    void theInboxButtonLeavesFolders() throws Exception {
        svc.createFolder("Trabajo");
        assertFalse(fx(() -> rail("Carpetas").getStyleClass().contains("active")));
        assertTrue(fx(() -> rail("Bandeja").getStyleClass().contains("active")));

        fire(railButton("Carpetas"));
        assertTrue(fx(() -> rail("Carpetas").getStyleClass().contains("active")));
        assertFalse(fx(() -> rail("Bandeja").getStyleClass().contains("active")), "only one is active");

        fire(railButton("Bandeja"));
        assertFalse(fx(pane::foldersOpen));
        assertTrue(fx(() -> showing(inboxList())));
        assertFalse(fx(() -> rail("Carpetas").getStyleClass().contains("active")));
        assertTrue(fx(() -> rail("Bandeja").getStyleClass().contains("active")));
    }

    @Test
    void aNetworkFilterDoesNotHideChatsFromFolders() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(SERVER)), true);
        FxWait.onFx(() -> {
            pane.goTo(pane.filterProperty().get().switchTo(app.unichat.model.Network.TELEGRAM, null));
        });
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");
        assertTrue(texts(".folder-chats").stream().anyMatch(t -> t.contains("2 sin leer")),
                "the server's unread needs every network: " + texts(".folder-chats"));
        assertFalse(fx(() -> rail("Telegram").getStyleClass().contains("active")));
    }

    @Test
    void menuTogglesMembership() throws Exception {
        String work = svc.createFolder("Trabajo");
        String home = svc.createFolder("Casa");
        svc.setInFolder(home, Present.of(svc.chat(FAMILIA)), true);

        Menu menu = Present.of(fx(() -> foldersMenu(chatMenu(Present.of(svc.chat(FAMILIA))))));
        assertFalse(fx(() -> check(menu, "Trabajo").isSelected()));
        assertTrue(fx(() -> check(menu, "Casa").isSelected()));
        assertTrue(fx(() -> menu.getItems().stream().anyMatch(i -> "Nueva carpeta…".equals(i.getText()))));

        FxWait.onFx(() -> {
            CheckMenuItem item = check(menu, "Trabajo");
            item.setSelected(true);
            item.fire();
        });
        String key = Present.of(svc.chat(FAMILIA)).prefKey();
        assertTrue(folder(work).chatKeys().contains(key));

        FxWait.onFx(() -> {
            CheckMenuItem item = check(menu, "Casa");
            item.setSelected(false);
            item.fire();
        });
        assertFalse(folder(home).chatKeys().contains(key));

        assertNotNull(fx(() -> foldersMenu(pane.menuFor(Present.of(svc.chat(SERVER))))), "a server can be in a folder");

        Chat bot = new Chat("!bot:demo");
        bot.setBridgeBot(true);
        bot.setPrefKey("bot");
        assertNull(fx(() -> foldersMenu(chatMenu(bot))), "not for bridge bots");
        Chat personal = new Chat("!me:demo");
        personal.setSpace(true);
        personal.setPersonalSpace(true);
        personal.setPrefKey("me");
        assertNull(fx(() -> foldersMenu(pane.menuFor(personal))), "not for the personal space");
        Chat invited = new Chat("!invited:demo");
        invited.setInvite(true);
        invited.setPrefKey("matrix:!invited:demo");
        assertNull(fx(() -> foldersMenu(chatMenu(invited))), "not for invitations");
    }

    @Test
    void aServerOverTheFolderLeavesItAloneUntilComingBack() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(SERVER)), true);
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");
        clickCell(".folder-chats", title(SERVER));

        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        FxWait.onFx(pane::refresh);
        assertEquals(1, fx(() -> folderChats().getItems().size()), "hidden: nothing to draw");

        key(fx(() -> scene.getRoot().lookup(".space-list")), KeyCode.ESCAPE);
        assertEquals(2, fx(() -> folderChats().getItems().size()), "back: up to date");
    }

    @Test
    void insideAFolderTheMenuCanTakeTheChatOut() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        assertNull(fx(() -> itemNamed(pane.menuFor(Present.of(svc.chat(FAMILIA))), "Quitar de esta carpeta")), "outside a folder");

        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");
        FxWait.onFx(() -> {
            Present.of(itemNamed(pane.menuFor(Present.of(svc.chat(FAMILIA))), "Quitar de esta carpeta")).fire();
        });
        assertTrue(folder(work).chatKeys().isEmpty());
        eventually("it leaves the list", () -> folderChats().getItems().isEmpty());
        assertNotNull(svc.chat(FAMILIA));
    }

    @Test
    void theNewFolderButtonAsksTheName() throws Exception {
        fire(railButton("Carpetas"));
        fire(fx(() -> buttons("Nueva carpeta").get(0)));
        Button save = fx(() -> buttons("Crear").get(0));
        assertTrue(fx(save::isDisabled), "a folder needs a name");
        FxWait.onFx(() -> {
            nameField().setText("  Amigos  ");
        });
        assertFalse(fx(save::isDisabled));
        fire(save);
        assertEquals("Amigos", svc.folders().get(0).name());
        assertFalse(fx(() -> scene.getRoot().lookup(".overlay").isVisible()), "the dialog closes");
        eventually("the name shows", () -> texts0(".folder-list").stream().anyMatch(t -> t.startsWith("Amigos")));
    }

    @Test
    void theChatMenuCreatesAFolderWithTheChatInside() throws Exception {
        FxWait.onFx(() -> {
            MenuItem create = Present.of(foldersMenu(chatMenu(Present.of(svc.chat(FAMILIA))))).getItems().stream()
                    .filter(i -> "Nueva carpeta…".equals(i.getText())).findFirst().orElseThrow();
            create.fire();
            nameField().setText("Amigos");
        });
        fire(fx(() -> buttons("Crear").get(0)));
        assertEquals(List.of(Present.of(svc.chat(FAMILIA)).prefKey()), svc.folders().get(0).chatKeys());
    }

    @Test
    void renameAndDeleteFromTheNames() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        fire(railButton("Carpetas"));
        FxWait.onFx(() -> {
            folderList().getSelectionModel().select(0);
        });

        key(fx(this::folderList), KeyCode.F2);
        FxWait.onFx(() -> {
            nameField().setText("Oficina");
        });
        fire(fx(() -> buttons("Guardar").get(0)));
        assertEquals("Oficina", svc.folders().get(0).name());

        FxWait.onFx(() -> {
            folderList().getSelectionModel().select(0);
        });
        key(fx(this::folderList), KeyCode.DELETE);
        assertTrue(fx(() -> scene.getRoot().lookupAll("Label").stream().anyMatch(n -> n instanceof Label l
                && l.getText().equals("¿Eliminar la carpeta «Oficina»? Los chats no se borran."))));
        fire(fx(() -> buttons("Cancelar").get(0)));
        assertEquals(1, svc.folders().size(), "cancel keeps it");

        FxWait.onFx(() -> {
            folderList().getSelectionModel().select(0);
        });
        key(fx(this::folderList), KeyCode.DELETE);
        fire(fx(() -> buttons("Eliminar").get(0)));
        assertTrue(svc.folders().isEmpty());
        assertNotNull(svc.chat(FAMILIA), "the chats aren't deleted");
    }

    @Test
    void aRefreshKeepsTheSelection() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");
        clickCell(".folder-chats", title(FAMILIA));
        FxWait.onFx(pane::refresh);
        assertEquals(FAMILIA, fx(() -> folderChats().getSelectionModel().getSelectedItem().id()));

        fire(fx(() -> buttons("Volver a carpetas").get(0)));
        FxWait.onFx(() -> {
            folderList().getSelectionModel().select(0);
            pane.refresh();
        });
        assertEquals("Trabajo", fx(() -> folderList().getSelectionModel().getSelectedItem().name()));
    }

    @Test
    void aServerInAFolderOpensItsChannelsAndComesBackToTheFolder() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(SERVER)), true);
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");

        clickCell(".folder-chats", title(SERVER));
        assertTrue(fx(pane::spaceOpen));
        assertFalse(fx(() -> showing(folderChats())), "the channels cover the folder");
        key(fx(() -> scene.getRoot().lookup(".space-list")), KeyCode.ESCAPE);
        assertFalse(fx(pane::spaceOpen));
        assertTrue(fx(() -> showing(folderChats())), "back in the folder, not the inbox");
        assertTrue(fx(pane::foldersOpen));
    }

    @Test
    void altArrowsStayInsideTheFolder() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        fire(railButton("Carpetas"));
        FxWait.onFx(() -> {
            pane.move(1, false);
        });
        assertNull(fx(() -> thread().chat()), "on the names there's nothing to move through");

        clickCell(".folder-list", "Trabajo");
        FxWait.onFx(() -> {
            pane.move(1, false);
        });
        assertEquals(FAMILIA, fx(() -> Present.of(thread().chat()).id()));
    }

    @Test
    void theMenuKeyOpensTheSelectedRowsMenuOnEveryList() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(SERVER)), true);
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);

        assertTrue(menuKeyOn(inboxListNow(), SERVER).contains("Carpetas"), "the inbox, on a server: never opens in the thread");

        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");
        List<String> inside = menuKeyOn(fx(this::folderChats), SERVER);
        assertTrue(inside.contains("Carpetas"), inside.toString());
        assertTrue(inside.contains("Quitar de esta carpeta"), inside.toString());

        clickCell(".folder-chats", title(SERVER));
        @SuppressWarnings("unchecked") // lookup() gives a Node: the list found by its style class is that ListView
        ListView<Chat> channels = fx(() -> (ListView<Chat>) scene.getRoot().lookup(".space-list"));
        String channel = fx(() -> channels.getItems().stream().filter(c -> c.prefKey() != null).findFirst().orElseThrow().id());
        assertTrue(menuKeyOn(channels, channel).contains("Carpetas"), "a server's channels");

        FxWait.onFx(() -> {
            folderChats().getSelectionModel().clearSelection();
        });
        assertTrue(menuKey(fx(this::folderChats)).isEmpty(), "nothing selected: no menu");
    }

    @Test
    void anArchivedChatInAFolderDoesNotSendTheHiddenFilterToTheArchive() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        svc.setArchived(FAMILIA, true).get();
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");

        clickCell(".folder-chats", title(FAMILIA));
        assertEquals(FAMILIA, fx(() -> Present.of(thread().chat()).id()));
        assertEquals(ChatFilter.View.INBOX, fx(() -> pane.filterProperty().get().view()));

        TextField search = fx(() -> (TextField) scene.getRoot().lookup(".search-field"));
        FxWait.onFx(() -> {
            search.setText("x");
            search.clear();
        });
        assertEquals(ChatFilter.View.INBOX, fx(() -> pane.filterProperty().get().view()), "search then clear lands in the inbox");
        assertEquals("Bandeja", fx(() -> title().getText()));
    }

    @Test
    void escOnTheNamesGoesToTheInbox() throws Exception {
        svc.createFolder("Trabajo");
        fire(railButton("Carpetas"));
        key(fx(this::folderList), KeyCode.LEFT);
        assertTrue(fx(pane::foldersOpen), "left does nothing here");

        key(fx(this::folderList), KeyCode.ESCAPE);
        assertFalse(fx(pane::foldersOpen));
        assertTrue(fx(() -> showing(inboxList())));
        assertTrue(fx(() -> rail("Bandeja").getStyleClass().contains("active")));
    }

    @Test
    void escWithNoFoldersAlsoGoesToTheInbox() throws Exception {
        fire(railButton("Carpetas"));
        key(fx(() -> buttons("Nueva carpeta").get(0)), KeyCode.ESCAPE);
        assertFalse(fx(pane::foldersOpen));
    }

    @Test
    void withNoFoldersTheFocusGoesToNuevaCarpetaAndItExplains() throws Exception {
        fire(railButton("Carpetas"));
        Button create = fx(() -> buttons("Nueva carpeta").get(0));
        assertEquals(create, fx(scene::getFocusOwner));
        assertEquals("Aún no tienes carpetas. Crea una y añade chats con clic derecho.", fx(create::getAccessibleHelp));

        svc.createFolder("Trabajo");
        eventually("the name shows", () -> !folderList().getItems().isEmpty());
        assertNull(fx(create::getAccessibleHelp), "it isn't true any more");
        FxWait.onFx(() -> {
            pane.focusList();
        });
        assertEquals(fx(this::folderList), fx(scene::getFocusOwner));
    }

    @Test
    void backFromAServerReselectsItEvenAfterOpeningAChannel() throws Exception {
        String work = svc.createFolder("Trabajo");
        svc.setInFolder(work, Present.of(svc.chat(FAMILIA)), true);
        svc.setInFolder(work, Present.of(svc.chat(SERVER)), true);
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");
        clickCell(".folder-chats", title(SERVER));
        clickCell(".space-list", "#general");
        assertEquals("!srv-general:demo", fx(() -> Present.of(thread().chat()).id()));

        key(fx(() -> scene.getRoot().lookup(".space-list")), KeyCode.ESCAPE);
        assertEquals(SERVER, fx(() -> folderChats().getSelectionModel().getSelectedItem().id()));
        assertEquals("!srv-general:demo", fx(() -> Present.of(thread().chat()).id()), "going back closes nothing");
    }

    @Test
    void reenteringALongFolderShowsTheOpenChat() throws Exception {
        String work = svc.createFolder("Trabajo");
        for (Chat c : svc.chats()) if (!c.space() && !c.bridgeBot()) svc.setInFolder(work, c, true);
        fire(railButton("Carpetas"));
        clickCell(".folder-list", "Trabajo");
        Chat last = fx(() -> folderChats().getItems().getLast());
        assertTrue(texts(".folder-chats").stream().noneMatch(t -> t.startsWith(last.title())), "the folder must be longer than the window");
        FxWait.onFx(() -> {
            pane.setSelected(last.id());
        });
        fire(fx(() -> buttons("Volver a carpetas").get(0)));

        clickCell(".folder-list", "Trabajo");
        assertEquals(last, fx(() -> folderChats().getSelectionModel().getSelectedItem()));
        eventually("the open chat comes into view", () -> texts0(".folder-chats").stream().anyMatch(t -> t.startsWith(last.title())));
    }

    @Test
    void theNewChatButtonHidesInFolders() throws Exception {
        assertEquals(1, fx(() -> buttons("Nuevo chat").size()));
        fire(railButton("Carpetas"));
        assertTrue(fx(() -> buttons("Nuevo chat").isEmpty()), "beside the title Carpetas it would read as a new folder");
        fire(railButton("Bandeja"));
        assertEquals(1, fx(() -> buttons("Nuevo chat").size()));
    }

    // ------------------------------------------------------------------ helpers

    private ListView<Chat> inboxListNow() throws InterruptedException {
        return fx(this::inboxList);
    }

    /** Selects {@code chatId} and presses the menu key: what the menu that opens offers. */
    private List<String> menuKeyOn(ListView<Chat> list, String chatId) throws InterruptedException {
        FxWait.onFx(() -> {
            int row = -1;
            for (int i = 0; i < list.getItems().size(); i++) if (list.getItems().get(i).id().equals(chatId)) row = i;
            assertTrue(row >= 0, "no row " + chatId);
            list.getSelectionModel().select(row);
        });
        return menuKey(list);
    }

    /** The keyboard's context menu event lands on the focused list, not on a row. */
    private List<String> menuKey(Node target) throws InterruptedException {
        return fx(() -> {
            Event.fireEvent(target, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, true, null));
            List<ContextMenu> open = Window.getWindows().stream().filter(w -> w instanceof ContextMenu && w.isShowing())
                    .map(ContextMenu.class::cast).toList();
            List<String> texts = open.stream().flatMap(m -> m.getItems().stream()).map(MenuItem::getText).toList();
            open.forEach(ContextMenu::hide);
            return texts;
        });
    }

    private ContextMenu chatMenu(Chat c) {
        return pane.menuFor(c);
    }

    private static @Nullable Menu foldersMenu(ContextMenu menu) {
        return (Menu) menu.getItems().stream().filter(i -> i instanceof Menu && "Carpetas".equals(i.getText()))
                .findFirst().orElse(null);
    }

    private static @Nullable MenuItem itemNamed(ContextMenu menu, String text) {
        return menu.getItems().stream().filter(i -> text.equals(i.getText())).findFirst().orElse(null);
    }

    private static CheckMenuItem check(Menu menu, String text) {
        return (CheckMenuItem) menu.getItems().stream().filter(i -> i instanceof CheckMenuItem && text.equals(i.getText()))
                .findFirst().orElseThrow();
    }

    private Folder folder(String id) {
        return svc.folders().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    private String title(String chatId) {
        return Present.of(svc.chat(chatId)).title();
    }

    private int railIndex(String text) throws InterruptedException {
        return fx(() -> {
            VBox rail = (VBox) scene.getRoot().lookup(".rail");
            for (int i = 0; i < rail.getChildren().size(); i++) {
                String a = rail.getChildren().get(i).getAccessibleText();
                if (a != null && a.startsWith(text)) return i;
            }
            throw new AssertionError("no rail child " + text);
        });
    }

    private Button railButton(String text) throws InterruptedException {
        return fx(() -> rail(text));
    }

    private Button rail(String text) {
        return (Button) scene.getRoot().lookup(".rail").lookupAll(".rail-button").stream()
                .filter(n -> n.getAccessibleText() != null && n.getAccessibleText().startsWith(text))
                .findFirst().orElseThrow(() -> new AssertionError("no rail button " + text));
    }

    /** Visible buttons named {@code text}, by what they show or say to a screen reader. */
    private List<Button> buttons(String text) {
        return scene.getRoot().lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(this::showing)
                .filter(b -> text.equals(b.getText()) || text.equals(b.getAccessibleText())).toList();
    }

    private boolean showing(Node n) {
        for (Node x = n; x != null; x = x.getParent()) if (!x.isVisible()) return false;
        return true;
    }

    private TextField nameField() {
        return (TextField) scene.getRoot().lookup(".folder-dialog .text-field");
    }

    private Label title() {
        return (Label) scene.getRoot().lookup(".pane-title");
    }

    private Label folderTitle() {
        return (Label) scene.getRoot().lookup(".folder-title");
    }

    private Node chipRow() {
        return scene.getRoot().lookup(".chip-row");
    }

    private ThreadPane thread() {
        return (ThreadPane) scene.getRoot().lookup(".thread-pane");
    }

    @SuppressWarnings("unchecked") // lookup() gives a Node: the list found by its style class is that ListView
    private ListView<Chat> inboxList() {
        return (ListView<Chat>) scene.getRoot().lookup(".chat-list");
    }

    @SuppressWarnings("unchecked") // lookup() gives a Node: the list found by its style class is that ListView
    private ListView<Folder> folderList() {
        return (ListView<Folder>) scene.getRoot().lookup(".folder-list");
    }

    @SuppressWarnings("unchecked") // lookup() gives a Node: the list found by its style class is that ListView
    private ListView<Chat> folderChats() {
        return (ListView<Chat>) scene.getRoot().lookup(".folder-chats");
    }

    private List<String> texts(String list) throws InterruptedException {
        return fx(() -> texts0(list));
    }

    private List<String> texts0(String list) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return scene.getRoot().lookupAll(list + " .list-cell").stream()
                .filter(this::onScreenInList)
                .map(n -> (String) n.queryAccessibleAttribute(AccessibleAttribute.TEXT))
                .filter(t -> t != null && !t.isEmpty()).toList();
    }

    /** Recycled and spare cells also exist: only the ones really drawn inside the list count. */
    private boolean onScreenInList(Node cell) {
        if (!showing(cell)) return false;
        var bounds = cell.localToScene(cell.getBoundsInLocal());
        return bounds.getMaxY() > 0 && bounds.getMinY() < scene.getHeight();
    }

    private void clickCell(String list, String startsWith) throws InterruptedException {
        FxWait.onFx(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            Node cell = scene.getRoot().lookupAll(list + " .list-cell").stream()
                    .filter(n -> {
                        String t = (String) n.queryAccessibleAttribute(AccessibleAttribute.TEXT);
                        return t != null && t.startsWith(startsWith);
                    }).findFirst().orElseThrow(() -> new AssertionError("no row " + startsWith + " in " + texts0(list)));
            Event.fireEvent(cell, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, false, null));
        });
        FxWait.settled();
    }

    private static void fire(Button b) throws InterruptedException {
        FxWait.onFx(() -> {
            b.fire();
        });
        FxWait.settled();
    }

    private static void key(Node target, KeyCode code) throws InterruptedException {
        FxWait.onFx(() -> {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        });
        FxWait.settled();
    }

    /** chatsChanged reaches the list through a Throttle: wait for it instead of guessing how long. */
    private static void eventually(String what, Callable<Boolean> condition) {
        FxWait.until(what, condition);
    }

    private static <T extends @Nullable Object, E extends Exception> T fx(FxWait.FxCall<T, E> c) throws E, InterruptedException {
        return FxWait.fx(c);
    }
}

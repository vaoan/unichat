package app.unichat.ui;

import app.unichat.service.Folder;
import app.unichat.model.Chat;
import app.unichat.service.ChatPreferences;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.function.Function;

/** The per-chat actions menu (right-click in the list, "⋯" in the thread header). */
public final class ChatMenus {
    private static final int MENU_NAME_MAX = 40;

    private ChatMenus() {}

    public static ContextMenu forChat(Chat c, ChatPreferences svc, FolderDialogs folders, Runnable afterLeave) {
        String id = c.id();
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                item(c.isUnread() ? "Marcar como leído" : "Marcar como no leído",
                        () -> { if (c.isUnread()) svc.markRead(id); else svc.setMarkedUnread(id, true); }),
                favoriteItem(c, svc), pinItem(c, svc), muteItem(c, svc),
                reminderMenu(c, svc),
                new SeparatorMenuItem(),
                item(c.lowPriority() ? "Mover a la bandeja" : "Mover a prioridad baja", () -> svc.setLowPriority(id, !c.lowPriority())),
                item(c.archived() ? "Desarchivar" : "Archivar", () -> svc.setArchived(id, !c.archived())));
        addFoldersMenu(menu, c, folders);
        menu.getItems().addAll(
                new SeparatorMenuItem(),
                danger(item(c.group() ? "Salir del grupo" : "Eliminar chat", () -> {
                    svc.leaveChat(id);
                    if (afterLeave != null) afterLeave.run();
                })));
        return menu;
    }

    /** A server/group in the Bandeja: what makes sense for a container of chats. */
    public static ContextMenu forSpace(Chat c, ChatPreferences svc, FolderDialogs folders) {
        String id = c.id();
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                favoriteItem(c, svc), pinItem(c, svc), muteItem(c, svc),
                new SeparatorMenuItem(),
                item(c.archived() ? "Desarchivar" : "Archivar", () -> svc.setArchived(id, !c.archived())));
        addFoldersMenu(menu, c, folders);
        return menu;
    }

    /** One tick per folder, so a chat can be in several; only for what a folder can hold. */
    private static void addFoldersMenu(ContextMenu menu, Chat c, FolderDialogs dialogs) {
        if (!FolderRows.canHold(c)) return;
        Menu m = new Menu("Carpetas");
        for (Folder f : dialogs.folders().folders()) {
            CheckMenuItem i = new CheckMenuItem(shortName(f.name()));
            i.setMnemonicParsing(false); // a name is text: "a_b" keeps its underscore
            i.setSelected(f.chatKeys().contains(c.prefKey()));
            i.setOnAction(e -> dialogs.folders().setInFolder(f.id(), c, i.isSelected()));
            m.getItems().add(i);
        }
        if (!m.getItems().isEmpty()) m.getItems().add(new SeparatorMenuItem());
        m.getItems().add(item("Nueva carpeta…", () -> dialogs.createWith(c)));
        menu.getItems().add(m);
    }

    /** Menu items don't ellipsize: a long name would widen the whole menu. */
    private static String shortName(String name) {
        return name.length() <= MENU_NAME_MAX ? name : name.substring(0, MENU_NAME_MAX) + "…";
    }

    /** The menu key lands on the list, not on a row: it opens the selected row's menu ({@code menu} may give none). */
    static <T> void onMenuKey(ListView<T> list, Function<T, ContextMenu> menu) {
        list.setOnContextMenuRequested(e -> {
            T row = list.getSelectionModel().getSelectedItem();
            ContextMenu m = row != null && e.isKeyboardTrigger() ? menu.apply(row) : null;
            if (m != null) m.show(list, e.getScreenX(), e.getScreenY());
        });
    }

    private static MenuItem favoriteItem(Chat c, ChatPreferences svc) {
        return item(c.favorite() ? "Quitar de favoritos" : "Añadir a favoritos", () -> svc.setFavorite(c.id(), !c.favorite()));
    }

    private static MenuItem pinItem(Chat c, ChatPreferences svc) {
        return item(c.pinned() ? "Desfijar" : "Fijar arriba", () -> svc.setPinned(c.id(), !c.pinned()));
    }

    private static MenuItem muteItem(Chat c, ChatPreferences svc) {
        return item(c.muted() ? "Activar notificaciones" : "Silenciar", () -> svc.setMuted(c.id(), !c.muted()));
    }

    static Menu reminderMenu(Chat c, ChatPreferences svc) {
        Menu m = new Menu("Recordarme");
        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();
        m.getItems().addAll(
                item("En 1 hora", () -> svc.setReminder(c.id(), now.plus(1, ChronoUnit.HOURS))),
                item("En 3 horas", () -> svc.setReminder(c.id(), now.plus(3, ChronoUnit.HOURS))),
                item("Mañana a las 9:00", () -> svc.setReminder(c.id(),
                        LocalDate.now(zone).plusDays(1).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant())),
                item("El próximo lunes", () -> svc.setReminder(c.id(),
                        LocalDate.now(zone).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(9, 0).atZone(zone).toInstant())));
        if (c.reminderAt() != null) {
            m.getItems().add(new SeparatorMenuItem());
            m.getItems().add(item("Quitar recordatorio (" + Formats.listStamp(c.reminderAt(), now, zone) + ")",
                    () -> svc.setReminder(c.id(), null)));
        }
        return m;
    }

    static MenuItem item(String text, Runnable action) {
        MenuItem i = new MenuItem(text);
        i.setOnAction(e -> action.run());
        return i;
    }

    static MenuItem danger(MenuItem i) {
        i.getStyleClass().add("danger");
        return i;
    }
}

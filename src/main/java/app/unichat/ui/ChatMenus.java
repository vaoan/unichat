package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.service.ChatPreferences;
import javafx.scene.control.ContextMenu;
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

/** The per-chat actions menu (right-click in the list, "⋯" in the thread header). */
public final class ChatMenus {
    private ChatMenus() {}

    public static ContextMenu forChat(Chat c, ChatPreferences svc, Runnable afterLeave) {
        String id = c.id();
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                item(c.isUnread() ? "Marcar como leído" : "Marcar como no leído",
                        () -> { if (c.isUnread()) svc.markRead(id); else svc.setMarkedUnread(id, true); }),
                favoriteItem(c, svc), pinItem(c, svc), muteItem(c, svc),
                reminderMenu(c, svc),
                new SeparatorMenuItem(),
                item(c.lowPriority() ? "Mover a la bandeja" : "Mover a prioridad baja", () -> svc.setLowPriority(id, !c.lowPriority())),
                item(c.archived() ? "Desarchivar" : "Archivar", () -> svc.setArchived(id, !c.archived())),
                new SeparatorMenuItem(),
                danger(item(c.group() ? "Salir del grupo" : "Eliminar chat", () -> {
                    svc.leaveChat(id);
                    if (afterLeave != null) afterLeave.run();
                })));
        return menu;
    }

    /** A server/group in the Bandeja: what makes sense for a container of chats. */
    public static ContextMenu forSpace(Chat c, ChatPreferences svc) {
        String id = c.id();
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                favoriteItem(c, svc), pinItem(c, svc), muteItem(c, svc),
                new SeparatorMenuItem(),
                item(c.archived() ? "Desarchivar" : "Archivar", () -> svc.setArchived(id, !c.archived())));
        return menu;
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

package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.settings.Settings;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;

/**
 * The panel on the right of every chat, like Telegram's: one place for the person's profile and the emoji, sticker
 * and GIF palettes. Switching tabs keeps it open; MainView opens and closes it.
 */
public final class SidePanel extends VBox {
    public enum Tab {
        PROFILE("Perfil"), FAVORITES("Favoritos"), EMOJIS("Emojis"), STICKERS("Stickers"), GIFS("GIFs");

        final String label;

        Tab(String label) { this.label = label; }
    }

    private final InfoPane info;
    private final StackPane body = new StackPane();
    private final Map<Tab, ToggleButton> buttons = new EnumMap<>(Tab.class);
    private final Map<Tab, Node> content = new EnumMap<>(Tab.class);
    private Tab current = Tab.PROFILE;
    private Chat chat;

    public SidePanel(InfoPane info) {
        this.info = info;
        getStyleClass().add("side-panel");
        ToggleGroup group = new ToggleGroup();
        // Wraps at large text sizes instead of cutting the last tabs off.
        javafx.scene.layout.FlowPane tabs = new javafx.scene.layout.FlowPane(4, 4);
        tabs.getStyleClass().add("side-panel-tabs");
        for (Tab t : Tab.values()) {
            ToggleButton b = Chips.sticky(t.label, group, false, () -> show(chat, t));
            b.setAccessibleText("Pestaña " + t.label);
            buttons.put(t, b);
            tabs.getChildren().add(b);
        }
        content.put(Tab.PROFILE, info);
        content.put(Tab.STICKERS, placeholder("Tus stickers favoritos y recientes aparecerán aquí."));
        content.put(Tab.GIFS, placeholder("Busca GIFs y guarda tus favoritos aquí."));
        content.put(Tab.FAVORITES, placeholder("Tus emojis, stickers y GIFs favoritos aparecerán aquí."));
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(tabs, body);
        try {
            current = Tab.valueOf(Settings.get().getString("panel.tab", Tab.PROFILE.name()));
        } catch (IllegalArgumentException ignored) {
            current = Tab.PROFILE;
        }
    }

    private final Map<Tab, Runnable> onShow = new EnumMap<>(Tab.class);

    /** Replaces the content of a tab; {@code refresh} runs every time the tab is shown (may be null). */
    public void setContent(Tab t, Node n, Runnable refresh) {
        content.put(t, n);
        if (refresh != null) onShow.put(t, refresh);
        if (t == current) body.getChildren().setAll(n);
    }

    public void show(Chat c, Tab t) {
        chat = c;
        current = t;
        buttons.get(t).setSelected(true);
        if (t == Tab.PROFILE && c != null) info.show(c);
        Runnable r = onShow.get(t);
        if (r != null) r.run();
        body.getChildren().setAll(content.get(t));
        Settings.get().set("panel.tab", t.name());
    }

    public Tab current() { return current; }

    /** Refreshes the open tab for the chat being shown (Perfil follows chat changes). */
    public void refresh() {
        if (current == Tab.PROFILE) info.refresh();
    }

    private static Node placeholder(String text) {
        Label l = Labels.help(text);
        StackPane p = new StackPane(l);
        p.getStyleClass().add("side-panel-placeholder");
        return p;
    }
}

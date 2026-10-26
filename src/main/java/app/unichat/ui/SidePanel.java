package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.settings.Settings;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The panel on the right of every chat, like Telegram's: one place for the person's profile and the emoji, sticker
 * and GIF palettes. Switching tabs keeps it open; MainView opens and closes it.
 */
public final class SidePanel extends VBox {
    public enum Tab {
        PROFILE("Perfil"), EMOJIS("Emojis"), STICKERS("Stickers"), GIFS("GIFs");

        final String label;

        Tab(String label) { this.label = label; }
    }

    private final InfoPane info;
    private final StackPane body = new StackPane();
    private final Map<Tab, ToggleButton> buttons = new EnumMap<>(Tab.class);
    private final Map<Tab, Node> content = new EnumMap<>(Tab.class);
    private Tab current = Tab.PROFILE;
    private Chat chat;

    public SidePanel(InfoPane info, Consumer<String> insertText) {
        this.info = info;
        getStyleClass().add("side-panel");
        ToggleGroup group = new ToggleGroup();
        HBox tabs = new HBox(4);
        tabs.getStyleClass().add("side-panel-tabs");
        for (Tab t : Tab.values()) {
            ToggleButton b = new ToggleButton(t.label);
            b.getStyleClass().add("chip");
            b.setToggleGroup(group);
            b.setAccessibleText("Pestaña " + t.label);
            b.setOnAction(e -> {
                if (!b.isSelected()) b.setSelected(true);
                show(chat, t);
            });
            buttons.put(t, b);
            tabs.getChildren().add(b);
        }
        content.put(Tab.PROFILE, info);
        ScrollPane emojis = new ScrollPane(EmojiPicker.grid(insertText, 280));
        emojis.setFitToWidth(true);
        emojis.getStyleClass().addAll("emoji-picker", "side-panel-scroll");
        content.put(Tab.EMOJIS, emojis);
        content.put(Tab.STICKERS, placeholder("Tus stickers favoritos y recientes aparecerán aquí."));
        content.put(Tab.GIFS, placeholder("Busca GIFs y guarda tus favoritos aquí."));
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
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("settings-help");
        StackPane p = new StackPane(l);
        p.getStyleClass().add("side-panel-placeholder");
        return p;
    }
}

package app.unichat.ui.timeline;

import app.unichat.support.I18n;
import app.unichat.ui.Nodes;
import app.unichat.ui.Showing;
import app.unichat.ui.Theme;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;

/**
 * "Cargar mensajes anteriores", a thin bar right above the message list while the chat has older history to load (as
 * reaching the list's top does): a wheel and "Cargando…" while it loads, gone at the chat's start. It stays a button
 * while loading, so the keyboard's focus never falls off it; a press then does nothing more.
 */
final class OlderBar {
    private final HBox box;
    private final Button load = new Button();
    private final ProgressIndicator wheel = new ProgressIndicator();

    OlderBar(Runnable onLoad) {
        load.getStyleClass().add("older-load");
        load.setMnemonicParsing(false);
        load.setOnAction(e -> onLoad.run());
        wheel.getStyleClass().add("older-wheel");
        Showing.spinWhileSeen(wheel);
        load.setGraphic(wheel);
        box = new HBox(load);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("older-bar");
        show(false, false);
    }

    Node node() { return box; }

    Button button() { return load; }

    /** {@code more}: older history to load; {@code loading}: it's being loaded now (the bar shows either way). */
    void show(boolean more, boolean loading) {
        String text = I18n.t(loading ? "thread.olderLoading" : "thread.older");
        load.setText(text);
        load.setAccessibleText(text);
        Nodes.style(load, "loading", loading);
        Nodes.show(wheel, loading && !Theme.reduceMotion());
        Nodes.show(box, more || loading);
    }

    boolean shown() { return box.isVisible(); }
}

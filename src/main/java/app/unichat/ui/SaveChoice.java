package app.unichat.ui;

import app.unichat.model.SaveKind;
import app.unichat.support.I18n;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A chat's "Guardar lo que envíen" or "Guardar lo que yo envíe": the switch and the kinds under it, each read out with
 * the switch's name (two "Fotos" would sound the same). Turning it on starts with every kind; none chosen is off.
 */
final class SaveChoice {
    final CheckBox on;
    final VBox node;

    /** @param turnedOn just turned on: what's there already stays out */
    SaveChoice(String label, Set<SaveKind> saved, Consumer<Set<SaveKind>> save, Runnable turnedOn) {
        on = new CheckBox(label);
        on.setSelected(!saved.isEmpty());
        on.setWrapText(true);
        Map<SaveKind, CheckBox> kinds = new EnumMap<>(SaveKind.class);
        VBox kindBoxes = new VBox(6);
        kindBoxes.getStyleClass().add("chat-options-kinds");
        for (SaveKind k : SaveKind.values()) {
            CheckBox b = new CheckBox(I18n.t("chatOptions.kind." + k.name()));
            b.setSelected(saved.isEmpty() || saved.contains(k));
            b.disableProperty().bind(on.selectedProperty().not());
            b.setWrapText(true);
            b.setAccessibleText(label + ": " + b.getText());
            kinds.put(k, b);
            kindBoxes.getChildren().add(b);
        }
        Runnable apply = () -> {
            Set<SaveKind> chosen = EnumSet.noneOf(SaveKind.class);
            if (on.isSelected()) kinds.forEach((k, b) -> { if (b.isSelected()) chosen.add(k); });
            save.accept(chosen);
        };
        on.setOnAction(e -> {
            if (on.isSelected()) {
                if (kinds.values().stream().noneMatch(CheckBox::isSelected)) kinds.values().forEach(b -> b.setSelected(true));
                turnedOn.run();
            }
            apply.run();
        });
        kinds.values().forEach(b -> b.setOnAction(e -> apply.run()));
        node = new VBox(8, on, kindBoxes);
    }
}

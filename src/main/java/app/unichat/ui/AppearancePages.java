package app.unichat.ui;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static app.unichat.ui.SettingsForm.chips;
import static app.unichat.ui.SettingsForm.group;
import static app.unichat.ui.SettingsForm.page;
import static app.unichat.ui.SettingsForm.row;
import static app.unichat.ui.SettingsForm.toggle;

/** Ajustes → Apariencia and Accesibilidad: how Unichat looks, what they share being the text size and the appearance callback. */
final class AppearancePages {
    private static final String[] ACCENTS = {"#5B5BD6", "#0090FF", "#12A594", "#46A758", "#F76B15", "#E5484D", "#D6409F", "#8E4EC6", "#1C2024"};

    private AppearancePages() {}

    static Node appearance(Runnable changed) {
        Settings s = Settings.get();
        Node themes = plainChips(I18n.t("settings.appearance.mode"), List.of(Settings.Theme.values()), t -> switch (t) {
            case SYSTEM -> I18n.t("settings.option.system");
            case LIGHT -> I18n.t("settings.theme.light");
            case DARK -> I18n.t("settings.theme.dark");
        }, s.theme(), t -> {
            s.setTheme(t);
            changed.run();
        });
        java.util.List<String> languages = new java.util.ArrayList<>(List.of(I18n.AUTO));
        languages.addAll(I18n.available());
        Node language = plainChips(I18n.t("settings.appearance.language"), languages,
                code -> I18n.AUTO.equals(code) ? I18n.t("language.auto") : I18n.nativeName(code), s.language(), code -> {
                    s.setLanguage(code);
                    I18n.use(code); // the window is rebuilt in the new language (UnichatApp)
                });
        FlowPane accents = new FlowPane(8, 8);
        for (String hex : ACCENTS) {
            Button sw = new Button();
            sw.getStyleClass().add("accent-swatch");
            if (hex.equalsIgnoreCase(s.accent())) sw.getStyleClass().add("selected");
            sw.setStyle("-fx-background-color: " + hex + ";");
            sw.setAccessibleText(I18n.t("settings.appearance.accentColor", hex));
            sw.setOnAction(e -> {
                s.setAccent(hex);
                accents.getChildren().forEach(n -> n.getStyleClass().remove("selected"));
                sw.getStyleClass().add("selected");
                changed.run();
            });
            accents.getChildren().add(sw);
        }
        Slider font = fontSlider(changed);
        return page(I18n.t("settings.nav.appearance"),
                group(I18n.t("settings.appearance.groupLanguage"),
                        row(I18n.t("settings.appearance.language"), I18n.t("settings.appearance.languageHelp"), language)),
                group(I18n.t("settings.appearance.groupTheme"), row(I18n.t("settings.appearance.mode"), I18n.t("settings.appearance.modeHelp"), themes),
                        row(I18n.t("settings.appearance.accent"), null, accents)),
                group(I18n.t("settings.appearance.groupList"),
                        row(I18n.t("settings.appearance.textSize"), I18n.t("settings.appearance.textSizeHelp", Shortcuts.zoomKeys()), font),
                        row(I18n.t("settings.appearance.compact"), I18n.t("settings.appearance.compactHelp"), toggle(s.compactList(), v -> { s.setCompactList(v); changed.run(); })),
                        row(I18n.t("settings.appearance.badges"), I18n.t("settings.appearance.badgesHelp"), toggle(s.showNetworkBadges(), v -> { s.setShowNetworkBadges(v); changed.run(); }))),
                group(I18n.t("settings.appearance.groupWriting"),
                        row(I18n.t("settings.appearance.enterSends"), I18n.t("settings.appearance.enterSendsHelp", Shortcuts.key("Enter")), toggle(s.sendOnEnter(), s::setSendOnEnter))));
    }

    private static Slider fontSlider(Runnable changed) {
        Settings s = Settings.get();
        Slider font = new Slider(Settings.MIN_FONT_SCALE, Settings.MAX_FONT_SCALE, s.fontScale());
        font.setMajorTickUnit(0.15);
        font.setSnapToTicks(false);
        font.setPrefWidth(200);
        font.setMinWidth(160);
        font.setMaxWidth(220);
        font.valueChangingProperty().addListener((o, a, changing) -> {
            if (!changing) {
                s.setFontScale(font.getValue());
                changed.run();
            }
        });
        font.setOnMouseReleased(e -> {
            s.setFontScale(font.getValue());
            changed.run();
        });
        // Keyboard users change the slider with arrows: apply on every step.
        font.setOnKeyReleased(e -> {
            s.setFontScale(font.getValue());
            changed.run();
        });
        font.setBlockIncrement(0.05);
        font.setAccessibleRoleDescription(I18n.t("settings.slider.role"));
        return font;
    }

    /** Three chips: System / On / Off. */
    private static Node modeChips(String accessible, Settings.Mode current, Consumer<Settings.Mode> save) {
        return plainChips(accessible, List.of(Settings.Mode.values()), m -> switch (m) {
            case SYSTEM -> I18n.t("settings.option.system");
            case ON -> I18n.t("settings.option.on");
            case OFF -> I18n.t("settings.option.off");
        }, current, save);
    }

    /** Chips read out as just their label. */
    private static <T> Node plainChips(String accessible, List<T> values, Function<T, String> label, T current, Consumer<T> save) {
        return chips(accessible, values, label, label, current, save);
    }

    static Node accessibility(Runnable changed) {
        Settings s = Settings.get();
        Button test = new Button(I18n.t("settings.a11y.testVoice"));
        test.getStyleClass().add("secondary-button");
        test.setOnAction(e -> Speaker.say(I18n.t("settings.a11y.testVoiceSample")));
        HBox speak = new HBox(10, test, toggle(s.speakMessages(), s::setSpeakMessages));
        speak.setAlignment(Pos.CENTER_RIGHT);
        speak.getChildren().get(1).setAccessibleText(I18n.t("settings.a11y.speakAccessible"));
        return page(I18n.t("settings.nav.accessibility"),
                group(I18n.t("settings.a11y.groupVision"),
                        row(I18n.t("settings.appearance.textSize"), I18n.t("settings.a11y.textSizeHelp", Shortcuts.zoomKeys()), fontSlider(changed)),
                        row(I18n.t("settings.a11y.contrast"), I18n.t("settings.a11y.contrastHelp",
                                I18n.t(Theme.systemHighContrast() ? "settings.option.systemOn" : "settings.option.systemOff")),
                                modeChips(I18n.t("settings.a11y.contrast"), s.highContrast(), m -> { s.setHighContrast(m); changed.run(); })),
                        row(I18n.t("settings.a11y.readableFont"), I18n.t("settings.a11y.readableFontHelp"),
                                toggle(s.readableFont(), v -> { s.setReadableFont(v); changed.run(); })),
                        row(I18n.t("settings.a11y.spacing"), I18n.t("settings.a11y.spacingHelp"),
                                toggle(s.wideSpacing(), v -> { s.setWideSpacing(v); changed.run(); }))),
                group(I18n.t("settings.a11y.groupMotion"),
                        row(I18n.t("settings.a11y.motion"), I18n.t("settings.a11y.motionHelp",
                                I18n.t(Theme.systemReducedMotion() ? "settings.option.systemOn" : "settings.option.systemOff")),
                                modeChips(I18n.t("settings.a11y.motion"), s.reduceMotion(), m -> { s.setReduceMotion(m); changed.run(); })),
                        row(I18n.t("settings.a11y.focus"), I18n.t("settings.a11y.focusHelp"),
                                toggle(s.strongFocus(), v -> { s.setStrongFocus(v); changed.run(); })),
                        row(I18n.t("settings.a11y.actions"), I18n.t("settings.a11y.actionsHelp"),
                                toggle(s.alwaysShowMessageActions(), v -> { s.setAlwaysShowMessageActions(v); changed.run(); }))),
                group(I18n.t("settings.a11y.groupAudio"),
                        row(I18n.t("settings.a11y.speak"), I18n.t("settings.a11y.speakHelp"), speak)),
                group(I18n.t("settings.a11y.groupReaders"),
                        row(I18n.t("settings.a11y.readers"), I18n.t("settings.a11y.readersHelp"),
                                new Label())));
    }
}

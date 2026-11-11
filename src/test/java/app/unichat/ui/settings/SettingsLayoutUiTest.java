package app.unichat.ui.settings;

import app.unichat.ui.FxWait;
import app.unichat.ui.Theme;
import app.unichat.service.MockChatService;
import app.unichat.settings.Settings;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every page of Ajustes, at the usual text size and at 160 %: no option's name or help is cut, nor squeezed into a
 * column (a wide control beside it made "Color de acento" a syllable a line, and help texts a few words a line).
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SettingsLayoutUiTest {
    private static final List<String> PAGES = List.of("profile", "appearance", "accessibility", "notifications", "privacy",
            "networks", "cloud", "speech", "ai", "gifs", "shortcuts", "about");
    /** Narrower than this, a text longer than it reads as a column. */
    private static final double MIN_EM = 12;

    @Test
    void noTextIsCutNorSqueezedOnAnyPage() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        String scale = Settings.get().getString("ui.fontScale", null);
        List<String> bad = new ArrayList<>();
        // Something learned, so Escribir con IA shows its last change filled in (dates, traits, pending corrections).
        app.unichat.assist.WritingStyle style = app.unichat.assist.WritingStyle.get();
        style.forget();
        style.save("- frases cortas\n- ríe con jajaja");
        style.addCorrection("jajaja", "hahaha");
        new app.unichat.assist.StyleLearner(style, p -> "- frases cortas\n- ríe con hahaha, nunca con jajaja ni con emojis de risa").learn("!c", List.of());
        style.addCorrection("nos vemos mañana", "nos vemos");
        try {
            for (double fontScale : new double[] {1.0, 1.6}) {
                Settings.get().setFontScale(fontScale);
                Stage owner = FxWait.fx(() -> {
                    Stage o = new Stage();
                    o.setOpacity(0);
                    o.show();
                    SettingsWindow.show(o, svc, () -> { }, () -> { }, id -> { }, t -> { });
                    Theme.applyAll();
                    return o;
                });
                for (String page : PAGES) {
                    FxWait.fx(() -> { SettingsWindow.select(page); return null; });
                    FxWait.settled();
                    bad.addAll(FxWait.fx(() -> squeezed(SettingsWindow.current().getScene().getRoot(), page + " @" + fontScale)));
                }
                FxWait.fx(() -> { SettingsWindow.current().close(); owner.close(); return null; });
            }
        } finally {
            Settings.get().set("ui.fontScale", scale);
            style.forget();
            svc.stop();
        }
        assertTrue(bad.isEmpty(), "cut or squeezed:\n" + String.join("\n", bad));
    }

    private static List<String> squeezed(Node root, String where) {
        List<String> out = new ArrayList<>();
        for (Node n : root.lookupAll(".settings-label")) check((Label) n, where, out);
        for (Node n : root.lookupAll(".settings-row .settings-help")) if (n instanceof Label l) check(l, where, out);
        return out;
    }

    private static void check(Label l, String where, List<String> out) {
        if (!l.isVisible() || l.getText() == null || l.getText().isBlank() || l.getScene() == null) return;
        javafx.scene.text.Text t = new javafx.scene.text.Text(l.getText());
        t.setFont(l.getFont());
        double natural = t.getLayoutBounds().getWidth(), em = l.getFont().getSize();
        if (l.isTextTruncated() || l.getWidth() + 1 < Math.min(natural, MIN_EM * em)) {
            out.add(where + ": \"" + l.getText().substring(0, Math.min(40, l.getText().length())) + "\" " + Math.round(l.getWidth()) + " px");
        }
    }
}

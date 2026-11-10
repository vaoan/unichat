package app.unichat.ui;

import app.unichat.assist.AiSettings;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every setting that can come from another computer (the cloud) takes effect here at once, without restarting: each
 * one has its proof below, what the app actually uses after the change. A new setting without one fails
 * {@link #everySettingHasItsProof}: it has to say how it applies live.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class SyncedSettingsUiTest {
    /** A setting's value from the cloud, and what in the running app shows it took effect. */
    private record Proof(String value, Supplier<Object> effect, Object expected) {}

    private static VBox root;

    /** Each key: the value another computer chose, and where it shows here. Prefixes end with ".". */
    private static Map<String, Proof> proofs() {
        Map<String, Proof> p = new LinkedHashMap<>();
        // Read once, then applied (SyncedSettings): the window shows them.
        p.put("ui.theme", new Proof("DARK", () -> root.getStyleClass().contains("dark"), true));
        p.put("a11y.highContrast", new Proof("ON", () -> root.getStyleClass().contains("contrast"), true));
        p.put("a11y.strongFocus", new Proof("true", () -> root.getStyleClass().contains("strong-focus"), true));
        p.put("a11y.wideSpacing", new Proof("true", () -> root.getStyleClass().contains("wide-spacing"), true));
        p.put("a11y.readableFont", new Proof("true", () -> root.getStyle().contains("Verdana"), true));
        p.put("ui.fontScale", new Proof("1.4", () -> root.getStyle().contains("-fx-font-size: 18.2px"), true));
        p.put("ui.accent", new Proof("#E5484D", () -> root.getStyle().contains("-accent: " + Theme.accessibleAccent("#E5484D")), true));
        p.put("ai.enabled", new Proof("true", AiReplyButton.SHOWN::get, true));
        p.put("ui.language", new Proof("en", () -> I18n.locale().getLanguage(), "en"));
        // Read when drawn: the window is drawn again (SyncedSettings → Ajustes' own redraw).
        p.put("ui.compact", new Proof("true", () -> Settings.get().compactList(), true));
        p.put("ui.networkBadges", new Proof("false", () -> Settings.get().showNetworkBadges(), false));
        p.put("a11y.messageActions", new Proof("true", () -> Settings.get().alwaysShowMessageActions(), true));
        p.put("a11y.reduceMotion", new Proof("ON", Theme::reduceMotion, true));
        p.put("account.alias.", new Proof("Trabajo", () -> Settings.get().getString("account.alias.x", null), "Trabajo"));
        // Read when used: what the code reads at that moment.
        p.put("app.globalShortcut", new Proof("false", () -> Settings.get().globalShortcut(), false));
        p.put("app.showInDock", new Proof("true", () -> Settings.get().showInDock(), true));
        p.put("notify.enabled", new Proof("false", () -> Settings.get().notificationsEnabled(), false));
        p.put("app.checkUpdates", new Proof("false", () -> Settings.get().checkUpdates(), false));
        p.put("discord.mediaDays", new Proof("90", () -> Settings.get().discordMediaDays(), 90));
        p.put("compose.sendOnEnter", new Proof("false", () -> Settings.get().sendOnEnter(), false));
        p.put("privacy.typing", new Proof("false", () -> Settings.get().sendTypingNotifications(), false));
        p.put("privacy.readReceiptsWhen", new Proof("NEVER", () -> Settings.get().getString("privacy.readReceiptsWhen", null), "NEVER"));
        p.put("privacy.readReceipts", new Proof("false", () -> Settings.get().getString("privacy.readReceipts", null), "false"));
        p.put("privacy.linkPreviews", new Proof("false", () -> Settings.get().linkPreviews(), false));
        p.put("cache.mediaMb", new Proof("5000", () -> Settings.get().mediaCacheMb(), 5000));
        p.put("logs.retentionDays", new Proof("30", () -> Settings.get().logRetentionDays(), 30));
        p.put("notify.preview", new Proof("false", () -> Settings.get().notificationPreview(), false));
        p.put("notify.sound", new Proof("false", () -> Settings.get().notificationSound(), false));
        p.put("notify.archived", new Proof("true", () -> Settings.get().notifyArchived(), true));
        p.put("a11y.speak", new Proof("true", () -> Settings.get().speakMessages(), true));
        p.put("app.minimizeToTray", new Proof("false", () -> Settings.get().minimizeToTray(), false));
        p.put("downloads.trayProgress", new Proof("false", () -> Settings.get().getBool(Notifier.TRAY_PROGRESS, true), false));
        p.put("downloads.notifyDone", new Proof("false", () -> Settings.get().getBool(Notifier.NOTIFY_DONE, true), false));
        p.put("ai.engine", new Proof("CODEX", () -> AiSettings.engine().name(), "CODEX"));
        p.put("ai.instructions", new Proof("Breve.", AiSettings::instructions, "Breve."));
        p.put("ai.voiceNotes", new Proof("false", AiSettings::hearVoiceNotes, false));
        p.put("ai.learnStyle", new Proof("false", AiSettings::learnStyle, false));
        p.put("ai.readySound", new Proof("BELL", () -> AiSettings.readyTone().name(), "BELL"));
        p.put("ai.readyVolume", new Proof("30", AiSettings::readyVolume, 30));
        p.put("stt.engine", new Proof("CUSTOM", () -> app.unichat.stt.SttSettings.engine().name(), "CUSTOM"));
        p.put("stt.model", new Proof("BASE", () -> app.unichat.stt.SttSettings.model().name(), "BASE"));
        p.put("stt.language", new Proof("en", () -> Settings.get().sttLanguage(), "en"));
        p.put("stt.command", new Proof("whisper {archivo}", () -> Settings.get().sttCommand(), "whisper {archivo}"));
        p.put("voice.enhance", new Proof("false", app.unichat.voice.VoiceEnhancer::enabled, false));
        p.put("video.ffmpegAuto", new Proof("false", app.unichat.video.Ffmpeg::autoInstall, false));
        p.put("cloak.level", new Proof("HIGH", () -> Settings.get().getString("cloak.level", null), "HIGH"));
        p.put("whatsapp.animatedStickers", new Proof("GIF",
                () -> app.unichat.service.StickerMode.of(app.unichat.model.Network.WHATSAPP).name(), "GIF"));
        p.put("gif.", new Proof("true", () -> Settings.get().getString("gif.giphy.enabled", null), "true"));
        return p;
    }

    /** The keys the app keeps settings under, read from its source: literals given to Settings, and *_KEY constants. */
    private static TreeSet<String> keysInTheApp() throws IOException {
        Pattern call = Pattern.compile("\\.(?:getString|getBool|getInt|getEnum|getDouble|getSet|set|setBool|addToSet|removeFromSet)\\(\"([a-z]\\w*\\.[\\w.]+)\"");
        Pattern constant = Pattern.compile("static final String [A-Z_]*(?:KEY|PROGRESS|DONE)[A-Z_]* = \"([a-z][\\w.]*)\"");
        Pattern concat = Pattern.compile("\"([a-z][\\w]*\\.(?:[\\w]+\\.)*)\" \\+");
        TreeSet<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                for (Pattern pt : List.of(call, constant)) {
                    Matcher m = pt.matcher(src);
                    while (m.find()) keys.add(m.group(1));
                }
                if (src.contains("Settings.get()")) {
                    Matcher m = concat.matcher(src);
                    while (m.find()) if (m.group(1).equals("account.alias.") || m.group(1).equals("gif.")) keys.add(m.group(1));
                }
            }
        }
        keys.removeIf(k -> isMachineOnly(k) || k.startsWith("panel.") || k.startsWith("unichat."));
        keys.add("whatsapp.animatedStickers"); // StickerMode.key: the network's name + ".animatedStickers"
        return keys;
    }

    private static boolean isMachineOnly(String key) {
        try {
            var m = Settings.class.getDeclaredMethod("staysOnThisMachine", String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, key);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everySettingHasItsProof() throws IOException {
        TreeSet<String> missing = keysInTheApp();
        missing.removeAll(proofs().keySet());
        assertTrue(missing.isEmpty(), "settings that travel through the cloud with no proof they apply live: " + missing);
    }

    @Test
    void everySettingFromTheCloudAppliesAtOnce() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        Map<String, String> before = Settings.get().portable();
        String language = I18n.locale().getLanguage();
        String forced = System.getProperty("unichat.language");
        System.clearProperty("unichat.language"); // tests force Spanish; here the setting itself must decide
        Stage stage = FxWait.fx(() -> {
            root = new VBox();
            Stage s = new Stage();
            s.setOpacity(0);
            s.setScene(new Scene(root, 300, 200));
            Theme.register(s.getScene());
            s.show();
            return s;
        });
        try {
            Map<String, String> remote = new LinkedHashMap<>(before);
            proofs().forEach((k, proof) -> remote.put(k.endsWith(".") ? k + (k.equals("gif.") ? "giphy.enabled" : "x") : k, proof.value()));
            Settings.get().replacePortable(remote, k -> k.startsWith("panel."));
            FxWait.fx(() -> {
                SyncedSettings.apply(Theme::applyAll);
                return null;
            });
            List<String> wrong = new ArrayList<>();
            for (var e : proofs().entrySet()) {
                Object got = FxWait.fx(() -> e.getValue().effect().get());
                if (!e.getValue().expected().equals(got)) wrong.add(e.getKey() + ": " + got + " instead of " + e.getValue().expected());
            }
            assertEquals(List.of(), wrong, "settings that didn't take effect");
        } finally {
            Settings.get().replacePortable(before, k -> false);
            if (forced != null) System.setProperty("unichat.language", forced);
            FxWait.fx(() -> {
                I18n.use(language);
                AiReplyButton.SHOWN.set(AiSettings.enabled());
                Theme.applyAll();
                stage.close();
                return null;
            });
        }
    }
}

package app.unichat.ui;

import app.unichat.settings.Settings;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Applies the stylesheet, light/dark palette, accent color and font scale to every app scene. */
public final class Theme {
    private Theme() {}

    private static final List<Scene> SCENES = new ArrayList<>();
    private static final String CSS = Objects.requireNonNull(Theme.class.getResource("/app/unichat/css/app.css")).toExternalForm();
    private static Boolean systemDark;

    public static void register(Scene scene) {
        SCENES.add(scene);
        scene.getStylesheets().add(CSS);
        scene.windowProperty().addListener((o, a, w) -> {
            if (w == null) SCENES.remove(scene);
        });
        apply(scene);
    }

    public static void applyAll() {
        systemDark = null;
        SCENES.forEach(Theme::apply);
    }

    public static boolean isDark() {
        return switch (Settings.get().theme()) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> systemDark();
        };
    }

    private static void apply(Scene scene) {
        Parent root = scene.getRoot();
        if (root == null) return;
        root.getStyleClass().remove("dark");
        if (isDark()) root.getStyleClass().add("dark");
        Settings s = Settings.get();
        root.setStyle("-accent: " + s.accent() + "; -fx-font-size: " + Math.round(13 * s.fontScale() * 10) / 10.0 + "px;");
    }

    /** Reads the OS appearance once per {@link #applyAll()}: Windows registry / macOS defaults / GTK theme. */
    static boolean systemDark() {
        if (systemDark != null) return systemDark;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                String out = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                systemDark = out.contains("0x0");
            } else if (os.contains("mac")) {
                systemDark = run("defaults", "read", "-g", "AppleInterfaceStyle").trim().equalsIgnoreCase("dark");
            } else {
                systemDark = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").contains("dark");
            }
        } catch (Exception e) {
            systemDark = false;
        }
        return systemDark;
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        p.waitFor(3, TimeUnit.SECONDS);
        return sb.toString();
    }
}

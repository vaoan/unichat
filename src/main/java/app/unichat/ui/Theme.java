package app.unichat.ui;

import app.unichat.settings.Settings;
import app.unichat.support.Os;
import app.unichat.support.Processes;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies the stylesheet, light/dark palette, accent color and font scale to every app scene. */
public final class Theme {
    private Theme() {}

    private static final List<Scene> SCENES = new ArrayList<>();
    private static final String CSS = Objects.requireNonNull(Theme.class.getResource("/app/unichat/css/app.css")).toExternalForm();
    private static Boolean systemDark;
    private static Boolean systemContrast;
    private static Boolean systemReducedMotion;

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
        systemContrast = null;
        systemReducedMotion = null;
        SCENES.forEach(Theme::apply);
    }

    public static boolean isDark() {
        return switch (Settings.get().theme()) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> systemDark();
        };
    }

    public static boolean highContrast() {
        return switch (Settings.get().highContrast()) {
            case ON -> true;
            case OFF -> false;
            case SYSTEM -> systemHighContrast();
        };
    }

    public static boolean reduceMotion() {
        return switch (Settings.get().reduceMotion()) {
            case ON -> true;
            case OFF -> false;
            case SYSTEM -> systemReducedMotion();
        };
    }

    /** Extra line spacing for message text, in px, when "más interlineado" is on. */
    public static double lineSpacing() {
        return Settings.get().wideSpacing() ? Math.round(5 * Settings.get().fontScale()) : 0;
    }

    private static void apply(Scene scene) {
        Parent root = scene.getRoot();
        if (root == null) return;
        Settings s = Settings.get();
        Nodes.style(root, "dark", isDark());
        Nodes.style(root, "contrast", highContrast());
        Nodes.style(root, "strong-focus", s.strongFocus());
        Nodes.style(root, "wide-spacing", s.wideSpacing());
        String accent = highContrast() ? accessibleAccent(s.accent()) : s.accent();
        String style = "-accent: " + accent + "; -fx-font-size: " + Math.round(13 * s.fontScale() * 10) / 10.0 + "px;";
        if (s.readableFont()) style += " -fx-font-family: \"Verdana\", \"Tahoma\", sans-serif;";
        root.setStyle(style);
        Icons.SCALE.set(s.fontScale());
    }

    /** WCAG contrast ratio between two #RRGGBB colors. */
    static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double[] c = {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
        for (int i = 0; i < 3; i++) {
            double v = c[i] / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** Darkens {@code hex} until white text on it reaches 4.5:1 (WCAG AA). */
    static String accessibleAccent(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        String cur = hex;
        for (int i = 0; i < 40 && contrast(cur, "#FFFFFF") < 4.5; i++) {
            r = (int) (r * 0.93);
            g = (int) (g * 0.93);
            b = (int) (b * 0.93);
            cur = String.format("#%02X%02X%02X", r, g, b);
        }
        return cur;
    }

    /** Windows high-contrast theme / macOS "Aumentar contraste". */
    static boolean systemHighContrast() {
        if (systemContrast != null) return systemContrast;
        try {
            if (Os.windows()) {
                String out = run("reg", "query", "HKCU\\Control Panel\\Accessibility\\HighContrast", "/v", "Flags");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("REG_SZ\\s+(\\d+)").matcher(out);
                systemContrast = m.find() && (Integer.parseInt(m.group(1)) & 1) == 1;
            } else if (Os.mac()) {
                systemContrast = run("defaults", "read", "com.apple.universalaccess", "increaseContrast").trim().equals("1");
            } else {
                systemContrast = run("gsettings", "get", "org.gnome.desktop.a11y.interface", "high-contrast").contains("true");
            }
        } catch (Exception e) {
            systemContrast = false;
        }
        return systemContrast;
    }

    /** Windows "Mostrar animaciones" off / macOS "Reducir movimiento". */
    static boolean systemReducedMotion() {
        if (systemReducedMotion != null) return systemReducedMotion;
        try {
            if (Os.windows()) {
                String out = run("reg", "query", "HKCU\\Control Panel\\Desktop\\WindowMetrics", "/v", "MinAnimate");
                systemReducedMotion = out.matches("(?s).*REG_SZ\\s+0\\b.*");
            } else if (Os.mac()) {
                systemReducedMotion = run("defaults", "read", "com.apple.universalaccess", "reduceMotion").trim().equals("1");
            } else {
                systemReducedMotion = run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations").contains("false");
            }
        } catch (Exception e) {
            systemReducedMotion = false;
        }
        return systemReducedMotion;
    }

    /** Reads the OS appearance once per {@link #applyAll()}: Windows registry / macOS defaults / GTK theme. */
    static boolean systemDark() {
        if (systemDark != null) return systemDark;
        try {
            if (Os.windows()) {
                String out = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                systemDark = out.contains("0x0");
            } else if (Os.mac()) {
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
        return Processes.capture(List.of(cmd), 3).out();
    }
}

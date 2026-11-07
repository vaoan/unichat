package app.unichat.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the person reads, in their language. Every text lives in {@code app/unichat/i18n/messages_<lang>.properties}
 * (UTF-8): English in {@code messages.properties}, the fallback for a language Unichat doesn't have, and one file per
 * other language. Adding a language is adding its file: {@link #available} finds it and Ajustes offers it.
 *
 * <p>{@code {0}}, {@code {1}}… in a text are replaced by the arguments as they are (no MessageFormat: an apostrophe in
 * "don't" would vanish). Counts go through {@link #n}: {@code key.one} and {@code key.other}.
 */
public final class I18n {
    static final String DIR = "/app/unichat/i18n/";
    /** The choice that follows the system's language. */
    public static final String AUTO = "auto";

    private static final Map<String, Properties> FILES = new ConcurrentHashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Locale locale = resolve(System.getProperty("unichat.language", AUTO), Locale.getDefault());

    private I18n() {}

    /** The text of {@code key} in the current language, with {@code {0}}… filled in. A missing key shows as itself. */
    public static String t(String key, Object... args) {
        String s = lookup(locale.getLanguage(), key);
        if (s == null) s = lookup("", key);
        if (s == null) return key;
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    /** {@code key.one} when {@code count} is 1, else {@code key.other}; {@code {0}} is the count. */
    public static String n(String key, long count, Object... more) {
        Object[] args = new Object[more.length + 1];
        args[0] = count;
        System.arraycopy(more, 0, args, 1, more.length);
        return t(key + (count == 1 ? ".one" : ".other"), args);
    }

    public static Locale locale() { return locale; }

    /** Follows {@code choice} ({@link #AUTO} or a language code) and tells the listeners when the language changes. */
    public static void use(String choice) {
        Locale next = resolve(choice, Locale.getDefault());
        if (next.equals(locale)) return;
        locale = next;
        LISTENERS.forEach(Runnable::run);
    }

    /** Called (on the thread that changed the language) after every change. */
    public static void onChange(Runnable r) { LISTENERS.add(r); }

    public static void removeListener(Runnable r) { LISTENERS.remove(r); }

    /** The language for {@code choice}: the one asked for, else the system's, else English, among those that exist. */
    static Locale resolve(String choice, Locale system) {
        if (choice != null && !AUTO.equals(choice) && exists(choice)) return Locale.forLanguageTag(choice);
        if (system != null && exists(system.getLanguage())) return Locale.forLanguageTag(system.getLanguage());
        return Locale.ENGLISH;
    }

    /** Every language with a file, by code, sorted by its own name ({@code language.name}). */
    public static List<String> available() {
        List<String> found = new ArrayList<>();
        for (String code : Locale.getISOLanguages()) {
            if (exists(code)) found.add(code);
        }
        found.sort(Comparator.comparing(I18n::nativeName, String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    /** How a language calls itself: "Español", "English". */
    public static String nativeName(String code) {
        String s = lookup(code, "language.name");
        return s != null ? s : Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(code));
    }

    static boolean exists(String code) {
        return "en".equals(code) || !file(code).isEmpty();
    }

    private static String lookup(String lang, String key) {
        return file("en".equals(lang) ? "" : lang).getProperty(key);
    }

    /** The texts of one file: {@code ""} is English (messages.properties); empty when there's no such file. */
    static Properties file(String lang) {
        return FILES.computeIfAbsent(lang, l -> {
            Properties p = new Properties();
            String name = DIR + (l.isEmpty() ? "messages.properties" : "messages_" + l + ".properties");
            try (InputStream in = I18n.class.getResourceAsStream(name)) {
                if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("Can't read " + name + ": " + e.getClass().getSimpleName());
            }
            return p;
        });
    }

    /** Every key of every file, for tests: language → sorted keys. */
    static Map<String, java.util.Set<String>> keysByLanguage() {
        Map<String, java.util.Set<String>> m = new TreeMap<>();
        for (String code : available()) m.put(code, new java.util.TreeSet<>(file("en".equals(code) ? "" : code).stringPropertyNames()));
        return m;
    }
}

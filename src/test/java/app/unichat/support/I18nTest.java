package app.unichat.support;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nTest {
    @Test
    void everyLanguageHasEveryTextWithTheSamePlaceholders() {
        Map<String, Set<String>> keys = I18n.keysByLanguage();
        Set<String> english = keys.get("en");
        for (var e : keys.entrySet()) {
            Set<String> missing = new TreeSet<>(english);
            missing.removeAll(e.getValue());
            Set<String> extra = new TreeSet<>(e.getValue());
            extra.removeAll(english);
            assertTrue(missing.isEmpty(), e.getKey() + " lacks " + missing);
            assertTrue(extra.isEmpty(), e.getKey() + " has keys English doesn't: " + extra);
            for (String k : english) {
                assertEquals(placeholders(I18n.file("").getProperty(k)), placeholders(I18n.file("en".equals(e.getKey()) ? "" : e.getKey()).getProperty(k)),
                        e.getKey() + ": " + k);
            }
        }
    }

    private static Set<String> placeholders(String s) {
        Set<String> found = new TreeSet<>();
        Matcher m = Pattern.compile("\\{\\d+}").matcher(s);
        while (m.find()) found.add(m.group());
        return found;
    }

    @Test
    void spanishAndEnglishAreOffered() {
        assertTrue(I18n.available().containsAll(java.util.List.of("es", "en")), I18n.available().toString());
        assertEquals("Español", I18n.nativeName("es"));
        assertEquals("English", I18n.nativeName("en"));
    }

    @Test
    void theChoiceWinsThenTheSystemThenEnglish() {
        assertEquals("en", I18n.resolve("en", Locale.forLanguageTag("es-CO")).getLanguage());
        assertEquals("es", I18n.resolve(I18n.AUTO, Locale.forLanguageTag("es-CO")).getLanguage());
        assertEquals("en", I18n.resolve(I18n.AUTO, Locale.JAPANESE).getLanguage(), "a language Unichat doesn't have");
        assertEquals("es", I18n.resolve("xx", Locale.forLanguageTag("es")).getLanguage(), "an unknown choice falls to the system");
    }

    @Test
    void argumentsAndCountsAreFilledInAndAMissingKeyShowsItself() {
        Locale before = I18n.locale();
        try {
            I18n.use("en");
            assertEquals("English", I18n.t("language.name"));
            assertEquals("no.such.key", I18n.t("no.such.key"));
            I18n.use("es");
            assertEquals("Español", I18n.t("language.name"));
        } finally {
            I18n.use(before.getLanguage());
        }
    }

    @Test
    void listenersHearAChange() {
        Locale before = I18n.locale();
        int[] heard = {0};
        Runnable r = () -> heard[0]++;
        I18n.onChange(r);
        try {
            I18n.use("es".equals(before.getLanguage()) ? "en" : "es");
            assertEquals(1, heard[0]);
            I18n.use(I18n.locale().getLanguage());
            assertEquals(1, heard[0], "same language: nothing to redo");
        } finally {
            I18n.removeListener(r);
            I18n.use(before.getLanguage());
        }
    }
}

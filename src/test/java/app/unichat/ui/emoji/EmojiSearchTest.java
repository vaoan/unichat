package app.unichat.ui.emoji;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmojiSearchTest {
    private static final List<String> ALL = EmojiPicker.CATEGORIES.values().stream().flatMap(List::stream).toList();
    private final EmojiSearch es = EmojiSearch.forLanguage("es");

    @Test
    void wordsBeginWordsOfTheNameIgnoringAccentsAndCase() {
        assertTrue(es.find("corazon roj", ALL).contains("❤️"), "without the accent, half a word");
        assertTrue(es.find("Bandera Col", ALL).contains("🇨🇴"));
        assertTrue(es.find("derrit", ALL).contains("🫠"));
        assertFalse(es.find("corazon roj", ALL).contains("😀"));
        assertTrue(es.find("razon", ALL).isEmpty() || !es.find("razon", ALL).contains("❤️"), "the start of a word, not the middle");
    }

    @Test
    void blankShowsEverythingInTheListsOrder() {
        assertEquals(ALL, es.find("  ", ALL));
        List<String> hearts = es.find("corazón", ALL);
        assertEquals(hearts.stream().sorted((a, b) -> ALL.indexOf(a) - ALL.indexOf(b)).toList(), hearts);
    }

    @Test
    void eachLanguageItsOwnNamesAndEnglishForOneWithout() {
        assertTrue(EmojiSearch.forLanguage("en").find("red heart", ALL).contains("❤️"));
        assertTrue(EmojiSearch.forLanguage("xx").find("thumbs up", ALL).contains("👍"), "a language without names: English");
        assertEquals("cara derritiéndose", es.name("🫠"));
    }

    @Test
    void splittingDropsAccentsCaseAndPunctuation() {
        assertEquals(List.of("bandera", "colombia"), EmojiSearch.split("Bandera: Colombia"));
        assertEquals(List.of("+1"), EmojiSearch.split("+1"));
    }
}

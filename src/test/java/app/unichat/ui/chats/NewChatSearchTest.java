package app.unichat.ui.chats;

import app.unichat.model.Contact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Searching contacts in "Nuevo chat" was exact where the network can search (its bridge's search wants the whole
 * username or number) and case-sensitive on usernames and numbers. A part of a name, a username or a number finds the
 * contacts already loaded, whatever the case or accents; the bridge's own results come after them, once each.
 */
class NewChatSearchTest {
    private static final Contact ANA = new Contact("1", "Ana María López", List.of("@AnaMaria", "+57 300 111 2233"), null, null);
    private static final Contact BETO = new Contact("2", "Beto", List.of("@beto_99"), null, null);
    private static final List<Contact> ALL = List.of(ANA, BETO);

    @Test
    void aPartOfANameUsernameOrNumberFindsThemWhateverTheCase() {
        assertEquals(List.of(ANA), NewChatPane.matching(ALL, "maria"), "part of the name, no accent");
        assertEquals(List.of(ANA), NewChatPane.matching(ALL, "LÓPEZ"), "another case and an accent");
        assertEquals(List.of(ANA), NewChatPane.matching(ALL, "anamar"), "part of the username, another case");
        assertEquals(List.of(ANA), NewChatPane.matching(ALL, "3001112"), "part of the number, without its spaces");
        assertEquals(List.of(BETO), NewChatPane.matching(ALL, "beto_"));
        assertEquals(List.of(), NewChatPane.matching(ALL, "zz"));
    }

    @Test
    void theBridgesResultsComeAfterTheContactsFoundOnceEach() {
        Contact remote = new Contact("3", "Ana Remota", List.of(), null, null);
        assertEquals(List.of(ANA, remote), NewChatPane.merged(List.of(ANA), List.of(ANA, remote)));
    }
}

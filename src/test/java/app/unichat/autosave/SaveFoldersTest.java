package app.unichat.autosave;

import app.unichat.model.Chat;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SaveFoldersTest {
    /** Anyone in a group can rename it: its files then went to a new folder, and exploring downloaded them all again. */
    @Test
    void aChatRenamedKeepsItsFolder() {
        Chat chat = new Chat("!renombrado-" + System.nanoTime() + ":demo");
        chat.setTitle("Familia");
        Path first = SaveFolders.of(chat);
        assertEquals(SaveFolders.base().resolve("Familia"), first);
        chat.setTitle("Familia 🎉 cumple de Ana");
        assertEquals(first, SaveFolders.of(chat), "the folder it had");
        Chat other = new Chat("!otro-" + System.nanoTime() + ":demo");
        other.setTitle("Familia 🎉 cumple de Ana");
        assertEquals(SaveFolders.base().resolve("Familia 🎉 cumple de Ana"), SaveFolders.of(other), "a new chat takes its name");
    }
}

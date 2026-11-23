package app.unichat.cloud;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSyncTest {
    private static final String DESK = "1b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44", LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55";
    private static final String CHAT = "3f2a9c1b2d4e", FILE = CHAT + " 2026-11.jsonl";

    @TempDir
    Path dir;

    private TextSync computer(String machine) {
        return new TextSync(dir.resolve(machine + "/texts"), dir.resolve(machine + "/cloud-texts.json"), machine);
    }

    private Path own(String machine) throws IOException {
        return Files.createDirectories(dir.resolve(machine + "/texts/here"));
    }

    @Test
    void eachComputerSendsOnlyItsOwnAndBringsTheOthersSoBothHaveAll() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            WebDav dav = new WebDav(cloud.url(), "u", "p");
            TextSync desk = computer(DESK), laptop = computer(LAPTOP);
            Files.writeString(own(DESK).resolve(FILE), "{\"id\":\"$1\"}\n", StandardCharsets.UTF_8);
            Files.writeString(own(LAPTOP).resolve(FILE), "{\"id\":\"$2\"}\n", StandardCharsets.UTF_8);

            assertEquals(Set.of(), desk.run(dav), "nobody else yet");
            assertEquals(Set.of(CHAT), laptop.run(dav), "the desk's chat came");
            assertEquals(Set.of(CHAT), desk.run(dav), "and the laptop's");
            assertEquals("{\"id\":\"$2\"}\n", Files.readString(dir.resolve(DESK + "/texts/" + LAPTOP + "/" + FILE)));
            assertEquals("{\"id\":\"$1\"}\n", Files.readString(dir.resolve(LAPTOP + "/texts/" + DESK + "/" + FILE)));
            Path brought = dir.resolve(LAPTOP + "/texts");
            if (brought.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(brought)),
                        "what the others said, readable by this account alone");
            }
            assertTrue(cloud.files.containsKey("Unichat/Texts/" + DESK + "/" + FILE));
            assertFalse(cloud.files.keySet().stream().anyMatch(p -> p.contains("/here/")), "each under its own machine");

            int puts = cloud.puts.get(), gets = cloud.gets.get();
            assertEquals(Set.of(), desk.run(dav), "nothing new");
            assertEquals(puts, cloud.puts.get(), "nothing sent again");
            assertEquals(gets, cloud.gets.get(), "nothing brought again: the index's stamp didn't change");

            Files.writeString(own(LAPTOP).resolve(FILE), "{\"id\":\"$3\"}\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            laptop.run(dav);
            assertEquals(Set.of(CHAT), computer(DESK).run(dav), "what grew comes again, the record kept across a restart");
            assertEquals("{\"id\":\"$2\"}\n{\"id\":\"$3\"}\n", Files.readString(dir.resolve(DESK + "/texts/" + LAPTOP + "/" + FILE)));
        }
    }

    @Test
    void aRestartSendsNothingAgainAndAnotherAccountSendsItsOwnOnce() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            WebDav dav = new WebDav(cloud.url(), "u", "p");
            Files.writeString(own(DESK).resolve(FILE), "{\"id\":\"$1\"}\n", StandardCharsets.UTF_8);
            computer(DESK).run(dav, TextSync.PLAIN);
            int puts = cloud.puts.get();
            TextSync restarted = computer(DESK); // the same record, as after a launch
            restarted.unseen();
            restarted.run(dav, TextSync.PLAIN);
            assertEquals(puts, cloud.puts.get(), "the same account: nothing sent again");

            cloud.files.clear(); // the vault opened here: its files are another storage's, which has none of them
            TextSync opened = computer(DESK);
            opened.unseen();
            opened.run(dav, "vault:1");
            assertTrue(cloud.files.containsKey("Unichat/Texts/" + DESK + "/" + FILE), "this computer is the only one to send its own: again");
            assertTrue(new String(cloud.files.get("Unichat/Texts/" + DESK + "/index.json"), StandardCharsets.UTF_8).contains(FILE));
            puts = cloud.puts.get();
            computer(DESK).run(dav, "vault:1");
            assertEquals(puts, cloud.puts.get(), "once");
        }
    }

    @Test
    void anIndexThatFailedToGoUpGoesAtTheNextRoundSoTheOthersSeeTheEnd() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            WebDav dav = new WebDav(cloud.url(), "u", "p");
            Files.writeString(own(DESK).resolve(FILE), "{\"id\":\"$1\"}\n", StandardCharsets.UTF_8);
            cloud.failOnce.add("Unichat/Texts/" + DESK + "/index.json");
            assertThrows(IOException.class, () -> computer(DESK).run(dav), "the network dropped after the file");
            computer(DESK).run(dav); // nothing new to send, but the index is still due
            assertEquals(Set.of(CHAT), computer(LAPTOP).run(dav), "the laptop sees the desk's file");
        }
    }

    @Test
    void namesInTheAccountThatArentOursNeverBecomePathsHere() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            WebDav dav = new WebDav(cloud.url(), "u", "p");
            cloud.dirs.add("Unichat");
            cloud.dirs.add("Unichat/Texts");
            cloud.put("Unichat/Texts/here/index.json", ("{\"files\": {\"" + FILE + "\": 3}}").getBytes(StandardCharsets.UTF_8));
            cloud.put("Unichat/Texts/here/" + FILE, "mal".getBytes(StandardCharsets.UTF_8));
            cloud.put("Unichat/Texts/" + LAPTOP + "/index.json",
                    "{\"files\": {\"../../x.jsonl\": 3, \"notas.txt\": 3, \"3f2a9c1b2d4e 2026-11.jsonl\": 2}}".getBytes(StandardCharsets.UTF_8));
            cloud.put("Unichat/Texts/" + LAPTOP + "/" + FILE, "ok".getBytes(StandardCharsets.UTF_8));
            assertEquals(Set.of(CHAT), computer(DESK).run(dav));
            try (var all = Files.walk(dir.resolve(DESK))) {
                assertEquals(Set.of(dir.resolve(DESK + "/texts/" + LAPTOP + "/" + FILE)),
                        Set.copyOf(all.filter(Files::isRegularFile).filter(p -> !p.endsWith("cloud-texts.json")).toList()),
                        "only a machine's own file with a name of ours");
            }
        }
    }
}

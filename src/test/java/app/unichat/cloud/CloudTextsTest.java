package app.unichat.cloud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The texts in the cloud's rounds: alone when asked, before the media in a round, and never holding the media back. */
class CloudTextsTest {
    private static final String LAPTOP = "2c7b4a4f-1e2d-4d1f-8b62-7a3e8d2f3c55", CHAT = "3f2a9c1b2d4e", FILE = CHAT + " 2026-11.jsonl";

    @TempDir
    Path dir;
    private FakeDav cloud;
    private final List<Set<String>> arrived = new CopyOnWriteArrayList<>();
    private boolean settingsWere;

    @BeforeEach
    void start() throws Exception {
        cloud = new FakeDav("u", "p");
        settingsWere = CloudAccount.settings();
        CloudAccount.setSettings(false); // the settings' own round is SyncedPartTest's
    }

    @AfterEach
    void stop() {
        CloudAccount.setSettings(settingsWere);
        cloud.close();
    }

    private Cloud cloud(CloudAccount account, Path media) {
        return cloud(() -> account, media);
    }

    private Cloud cloud(java.util.function.Supplier<CloudAccount> account, Path media) {
        SyncedPart unused = new SyncedPart("Unichat/Ajustes/x.json", "x", now -> null, b -> { });
        return new Cloud(account, unused, unused,
                new MediaUpload(dir.resolve("cloud-uploaded.txt"), () -> media == null ? List.of() : List.of(media), p -> false),
                new TextSync(dir.resolve("texts"), dir.resolve("cloud-texts.json"), Cloud.machine()), arrived::add);
    }

    private CloudAccount account() {
        return new CloudAccount(CloudAccount.Service.OTHER, cloud.url(), "u", "p");
    }

    private void laptopKept(String index) {
        cloud.dirs.add("Unichat");
        cloud.dirs.add("Unichat/Textos");
        cloud.put("Unichat/Textos/" + LAPTOP + "/index.json", index.getBytes(StandardCharsets.UTF_8));
        cloud.put("Unichat/Textos/" + LAPTOP + "/" + FILE, "{}\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void askedAloneTheTextsComeAndSayForWhichChats() throws Exception {
        laptopKept("{\"files\": {\"" + FILE + "\": 3}}");
        cloud((CloudAccount) null, null).textsNow().get();
        assertEquals(List.of(), arrived, "no account: nothing, done at once");
        cloud(account(), null).textsNow().get();
        assertEquals(List.of(Set.of(CHAT)), arrived);
    }

    @Test
    void asksWhileOneIsWaitingJoinItAndOneAfterItStartsGetsItsOwn() throws Exception {
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1), goOn = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger rounds = new java.util.concurrent.atomic.AtomicInteger();
        Cloud c = cloud(() -> {
            if (rounds.incrementAndGet() == 1) {
                running.countDown();
                try {
                    goOn.await(); // the first one busy, as with a slow account
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        }, null);
        var first = c.textsNow();
        running.await();
        var second = c.textsNow();
        var third = c.textsNow();
        assertSame(second, third, "both wait for the same turn");
        assertNotSame(first, second, "the one going on may have missed what was kept since");
        goOn.countDown();
        first.get();
        third.get();
        assertEquals(2, rounds.get(), "two turns for three asks");
    }

    @Test
    void textsThatFailDontHoldTheMediaBack() throws Exception {
        laptopKept("{no es json");
        Path chat = Files.createDirectories(dir.resolve("Ana"));
        Files.writeString(chat.resolve("a.jpg"), "foto");
        Cloud.Status s = cloud(account(), chat).syncNow().get();
        assertTrue(cloud.files.containsKey(CloudAccount.MEDIA + "/Ana/a.jpg"), "the photo went up all the same");
        assertNotNull(s.error(), "and the texts' failure is told");
    }
}

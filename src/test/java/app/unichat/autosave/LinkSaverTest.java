package app.unichat.autosave;

import app.unichat.support.I18n;
import instatray.core.Fetch;
import instatray.core.Site;
import instatray.core.Tools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chat's link saved through InstaTray's engine: Unichat's folder, name and progress, the engine's sites and reasons. */
class LinkSaverTest {
    @TempDir
    Path dir;

    /** A yt-dlp that prints what the real one does with the engine's options, and saves a file; it says it ran. */
    private Tools fakeYtDlp() throws Exception {
        Path tool = dir.resolve("yt-dlp");
        Files.writeString(tool, """
                #!/bin/sh
                touch "$FAKE_OUT/.ran"
                f="$FAKE_OUT/clip [abc].mp4"
                echo "TITLE Un video"
                echo "[download] Destination: $f"
                echo "PROG 512 1024 2048 3"
                printf video > "$f"
                echo "$f"
                """);
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwx------"));
        Path out = Files.createDirectories(dir.resolve("Ana"));
        return new Tools() {
            @Override public String ytDlp() { return tool.toString(); }
            @Override public String galleryDl() { return null; }
            @Override public String ffmpeg() { return null; }
            @Override public Map<String, String> environment() {
                Map<String, String> env = new HashMap<>(Tools.super.environment());
                env.put("FAKE_OUT", out.toString());
                return env;
            }
        };
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the fake tool is a shell script
    void aLinkIsSavedInTheChatsFolderWithTheMessagesIdAndSaysHowFarItGot() throws Exception {
        List<Double> progress = new ArrayList<>();
        Failure failed = LinkSaver.save("https://www.youtube.com/watch?v=dQw4w9WgXcQ", dir.resolve("Ana"), "abc", progress::add,
                fakeYtDlp(), Files.createDirectories(dir.resolve("Downloads")));
        assertNull(failed, "saved");
        assertTrue(Files.exists(dir.resolve("Ana/clip [abc].mp4")));
        assertEquals(List.of(0.5), progress, "the engine's progress, as Unichat shows it");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void textThatIsntAWebAddressNeverReachesATool() throws Exception {
        Tools tools = fakeYtDlp();
        Failure why = LinkSaver.save("--exec=rm -rf ~", dir.resolve("Ana"), "abc", p -> { }, tools, dir);
        assertEquals(Failure.forGood(I18n.t("autosave.link.notALink")), why, "and trying again won't change that");
        assertFalse(Files.exists(dir.resolve("Ana/.ran")), "yt-dlp never ran");
    }

    @Test
    void theEnginesSitesOrAnyOtherAddress() {
        assertEquals(Site.YOUTUBE, LinkSaver.linkOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ").site());
        assertEquals(Site.OTHER, LinkSaver.linkOf("https://example.org/un-video").site(), "anything else yt-dlp may know");
    }

    @Test
    void theEnginesReasonsInThePersonsWords() {
        assertEquals(I18n.t("autosave.link.login"), LinkSaver.reason(Fetch.LOGIN_NEEDED));
        assertEquals(I18n.t("autosave.error.tooLong"), LinkSaver.reason(Fetch.TIMED_OUT));
        assertEquals(I18n.t("autosave.link.missingTool", "gallery-dl"), LinkSaver.reason("gallery-dl" + Fetch.NOT_INSTALLED));
        assertEquals("Unsupported URL: https://x.org", LinkSaver.reason("Unsupported URL: https://x.org"), "a tool's own words as they are");
    }

    @Test
    void whatTheEngineCallsForGoodIsntTriedAgainNorIsWhatWouldHoldSavingForHours() {
        assertTrue(LinkSaver.failure(new Fetch.Result(Fetch.Outcome.FAILED_PERMANENT, Fetch.NOT_FOUND, List.of(), null)).permanent(),
                "removed or private");
        assertFalse(LinkSaver.failure(new Fetch.Result(Fetch.Outcome.FAILED, "Unable to download webpage: connection reset",
                List.of(), null)).permanent(), "the network: tried again at the chat's next change");
        // Tried again at every change of the chat, a video that took the 2 hours allowed would hold all saving for hours
        // each time; a missing tool won't be there at the next change either, and would hold the chat's later files.
        assertTrue(LinkSaver.failure(new Fetch.Result(Fetch.Outcome.FAILED, Fetch.TIMED_OUT, List.of(), null)).permanent());
        assertTrue(LinkSaver.failure(new Fetch.Result(Fetch.Outcome.FAILED, "gallery-dl" + Fetch.NOT_INSTALLED, List.of(), null))
                .permanent());
    }

    /**
     * A page no site knows (yt-dlp's [generic]) that refuses or isn't there has nothing to save: a Linktree profile
     * answered 403 every time, and taken as "for now" it held every later file of its chat (five tries in a day's log).
     * The network failing on a known site is still tried again.
     */
    @Test
    void aPageNoSiteKnowsThatRefusesHasNothingToSave() {
        String linktree = "[generic] katsudoggo: Unable to download webpage: HTTP Error 403: Forbidden (caused by <HTTPError 403: Forbidden>)";
        assertTrue(LinkSaver.failure(new Fetch.Result(Fetch.Outcome.FAILED, linktree, List.of(), null)).permanent());
        assertTrue(LinkSaver.failure(new Fetch.Result(Fetch.Outcome.FAILED,
                "[generic] x: Unable to download webpage: HTTP Error 404: Not Found", List.of(), null)).permanent());
        assertFalse(LinkSaver.failure(new Fetch.Result(Fetch.Outcome.FAILED,
                "[generic] x: Unable to download webpage: HTTP Error 503: Service Unavailable", List.of(), null)).permanent(),
                "a server busy for now");
        assertFalse(LinkSaver.failure(new Fetch.Result(Fetch.Outcome.FAILED,
                "[Instagram] abc: Unable to download webpage: HTTP Error 403: Forbidden", List.of(), null)).permanent(),
                "a known site refusing may be rate limiting");
    }
}

package app.unichat.claude;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each guard of `.claude/guards.tsv` stops what it was written for, and lets the everyday commands run. */
class GuardTest {
    private static final Guard GUARD = load();

    /** A command each guard must stop. A new guard comes with one. */
    private static final Map<String, String> STOPPED = Map.of(
            "ui-tests-on-screen", "./mvnw test -Dunichat.uiTests=true -Dtest=KeyboardUiTest",
            "real-display-tests", "./mvnw test -Pheadless-ui -Dunichat.perfTests=true",
            "app-window", "JAVA_OPTS=-Dunichat.dataDir=/tmp/x ./run-dev.sh",
            "open-installed-app", "open /Applications/Unichat.app",
            "real-data-write", "rm -rf ~/Library/Application\\ Support/Unichat/cache");

    private static Guard load() {
        try {
            return new Guard(Path.of("").toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void eachGuardStopsItsExample() {
        for (Guard.Rule r : GUARD.all()) {
            String command = STOPPED.get(r.id());
            assertTrue(command != null, "Guard " + r.id() + " needs an example in GuardTest.STOPPED");
            Guard.Rule judged = GUARD.judge(command);
            assertEquals(r.id(), judged == null ? null : judged.id(), command);
        }
    }

    @Test
    void everydayCommandsRun() {
        for (String ok : new String[] {
            "./mvnw verify",
            "./mvnw verify -Pheadless-ui -Dtest=KeyboardUiTest -Dsurefire.failIfNoSpecifiedTests=false",
            "JAVA_OPTS='-Dunichat.snapshot=out.png -Dunichat.dataDir=/tmp/demo' ./run-dev.sh",
            "git status",
            "grep -rn openFile src/main/java",
            "grep sticker ~/Library/Application\\ Support/Unichat/logs/unichat.log",
            "tail ~/Library/Application\\ Support/Unichat/server/telegram/bridge.log",
            "~/Library/Application\\ Support/Unichat/tools/cloak/env/bin/python3 -I x.py"}) {
            assertNull(GUARD.judge(ok), ok);
        }
    }

    @Test
    void readingTheDataFolderRunsAndChangingItAsks() {
        for (String c : new String[] {
            "cat ~/Library/Application\\ Support/Unichat/session.properties",
            "ls ~/Library/Application\\ Support/Unichat 2>/dev/null",
            "grep -i sticker ~/Library/Application\\ Support/Unichat/server/telegram/bridge.log | tail -5 >/dev/null"}) {
            assertNull(GUARD.judge(c), c);
        }
        for (String c : new String[] {
            "mv ~/Library/Application\\ Support/Unichat/settings.properties /tmp/s",
            "echo x > ~/Library/Application\\ Support/Unichat/preferences.json",
            "sed -i '' s/a/b/ ~/Library/Application\\ Support/Unichat/server/telegram/config.yaml",
            "del %APPDATA%\\..\\AppData\\Roaming\\Unichat\\x & rm AppData/Roaming/Unichat/y"}) {
            Guard.Rule r = GUARD.judge(c);
            assertEquals("real-data-write", r == null ? null : r.id(), c);
        }
    }
}

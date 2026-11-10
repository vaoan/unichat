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
            "real-data-dir", "rm -rf ~/Library/Application\\ Support/Unichat/cache");

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
            "grep -rn openFile src/main/java"}) {
            assertNull(GUARD.judge(ok), ok);
        }
    }
}

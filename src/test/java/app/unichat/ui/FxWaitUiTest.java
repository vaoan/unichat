package app.unichat.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The test helper itself: a wait inside a wait's condition fails at once, never hangs to JUnit's limit. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class FxWaitUiTest {
    @BeforeAll
    static void toolkit() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void settledInsideAnUntilsConditionFailsSayingWhy() {
        FxWait.OnFxThread e = assertThrows(FxWait.OnFxThread.class, () -> FxWait.until("the list", () -> {
            FxWait.settled();
            return true;
        }));
        assertTrue(e.getMessage().contains("look, don't wait"), e.getMessage());
    }
}

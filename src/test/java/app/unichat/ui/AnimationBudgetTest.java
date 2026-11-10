package app.unichat.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chat full of stickers kept 56 animations with 2 520 decoded frames alive at once, each up to 24 MB, against a 1 GB
 * heap: an OutOfMemoryError killed the sync and froze the window. Animations playing at once share a budget; past it a
 * sticker shows still until one leaves the screen.
 */
class AnimationBudgetTest {
    @Test
    void pastTheBudgetTheyShowStillUntilOneStops() {
        AnimationBudget budget = new AnimationBudget(100);
        assertTrue(budget.take(60));
        assertTrue(budget.take(40));
        assertFalse(budget.take(1), "full");
        budget.release(60);
        assertTrue(budget.take(50));
    }

    @Test
    void oneAloneAlwaysPlays() {
        AnimationBudget budget = new AnimationBudget(100);
        assertTrue(budget.take(500), "nothing else playing: a big one still plays (it was decoded to fit already)");
        assertFalse(budget.take(1));
        budget.release(500);
        assertTrue(budget.take(1));
    }
}

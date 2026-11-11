package app.unichat.ui.timeline;

import javafx.geometry.Rectangle2D;
import javafx.scene.control.TextArea;
import javafx.scene.control.skin.TextAreaSkin;

/**
 * Shift+↑ / Shift+↓ in the message box: the first press selects from the cursor to the start (↑) or the end (↓) of its
 * line as drawn; each press after it takes one more line, to where ↑ or ↓ alone would put the cursor, in the column the
 * run started from. Anything else that moves the cursor starts a new run. Measured from the characters, not the drawn
 * cursor, which exists only while the window has the system's focus.
 */
final class LineSelection {
    private static final double FAR = 1e6;
    private final TextArea box;
    /** Where the last step left the cursor: found anywhere else, the cursor was moved otherwise, and a run starts again. */
    private int left = -1;
    private boolean up;
    /** The run's column, as the x of the cursor when it started. */
    private double column;

    LineSelection(TextArea box) {
        this.box = box;
    }

    void step(boolean towardsStart) {
        if (!(box.getSkin() instanceof TextAreaSkin skin) || box.getLength() == 0) return;
        int caret = box.getCaretPosition();
        Rectangle2D at = place(skin, caret);
        if (caret != left || towardsStart != up) {
            up = towardsStart;
            column = at.getMinX();
            int edge = index(skin, up ? -FAR : FAR, at);
            if (edge != caret) {
                select(edge);
                return;
            }
            // At that end of its line already: on to the next line now.
        }
        double y = up ? at.getMinY() - at.getHeight() / 2 : at.getMaxY() + at.getHeight() / 2;
        int to = within(skin.getIndex(column, y).getInsertionIndex());
        // No line further that way: the run ends at the text's own start or end, as ↑ or ↓ alone gets nowhere.
        if (place(skin, to).getMinY() == at.getMinY()) to = up ? 0 : box.getLength();
        select(to);
    }

    private void select(int to) {
        box.selectRange(box.getAnchor(), to);
        left = box.getCaretPosition();
    }

    /** The insertion point at {@code x} on the line drawn at {@code line}. */
    private int index(TextAreaSkin skin, double x, Rectangle2D line) {
        return within(skin.getIndex(x, line.getMinY() + line.getHeight() / 2).getInsertionIndex());
    }

    /** An insertion point the text has: past the last line, the skin answers one beyond its end. */
    private int within(int index) {
        return Math.max(0, Math.min(index, box.getLength()));
    }

    /** Where the cursor at {@code index} is drawn: before its character, or after the one before it at a line's end. */
    private Rectangle2D place(TextAreaSkin skin, int index) {
        String text = box.getText();
        if (index < text.length() && text.charAt(index) != '\n') {
            Rectangle2D c = skin.getCharacterBounds(index);
            return new Rectangle2D(c.getMinX(), c.getMinY(), 0, c.getHeight());
        }
        if (index > 0 && text.charAt(index - 1) != '\n') {
            Rectangle2D c = skin.getCharacterBounds(index - 1);
            return new Rectangle2D(c.getMaxX(), c.getMinY(), 0, c.getHeight());
        }
        return skin.getCharacterBounds(Math.min(index, text.length() - 1)); // an empty line: its line break
    }
}

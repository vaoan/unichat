package app.unichat.ui.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a long picture of a chat is cut into pieces of one height (a phone's screen): each piece ends at the last
 * message that fits it whole, so no bubble is cut; one taller than a piece alone is cut inside, as little as it takes.
 * Pure: heights in pixels.
 */
final class ShotPieces {
    /** From {@code top} (in) to {@code bottom} (out) of the long picture. */
    record Piece(int top, int bottom) {}

    private ShotPieces() {}

    /**
     * @param ends where each message (and the header before them) ends, top to bottom
     * @param total the picture's height (a footer may follow the last message)
     * @param height each piece's
     */
    static List<Piece> cut(List<Integer> ends, int total, int height) {
        List<Piece> out = new ArrayList<>();
        int top = 0;
        while (top < total) {
            int limit = top + height;
            int bottom = -1;
            // Not after the last message: what follows it (the footer) never goes alone.
            for (int end : ends.subList(0, Math.max(0, ends.size() - 1))) if (end > top && end <= limit) bottom = end;
            if (total <= limit) bottom = total; // the rest fits: the last piece
            if (bottom < 0) bottom = limit; // a message taller than a piece: cut inside it
            out.add(new Piece(top, bottom));
            top = bottom;
        }
        return out;
    }
}

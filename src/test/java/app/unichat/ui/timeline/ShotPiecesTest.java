package app.unichat.ui.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a long capture is cut into pieces of one height: between messages, inside one only when it's taller than a piece. */
class ShotPiecesTest {
    @Test
    void eachPieceEndsAtTheLastMessageThatFitsIt() {
        // Messages ending at 100, 250, 380, 520, 700; pieces 400 tall.
        assertEquals(List.of(new ShotPieces.Piece(0, 380), new ShotPieces.Piece(380, 700)),
                ShotPieces.cut(List.of(100, 250, 380, 520, 700), 700, 400));
    }

    @Test
    void whatFitsInOneIsOne() {
        assertEquals(List.of(new ShotPieces.Piece(0, 300)), ShotPieces.cut(List.of(100, 300), 300, 400));
    }

    @Test
    void aMessageTallerThanAPieceIsCutInsideOnlyThen() {
        // The second message runs from 100 to 1000: taller than 400.
        assertEquals(List.of(new ShotPieces.Piece(0, 100), new ShotPieces.Piece(100, 500), new ShotPieces.Piece(500, 900),
                        new ShotPieces.Piece(900, 1200)), // its end and what follows fit one piece
                ShotPieces.cut(List.of(100, 1000, 1200), 1200, 400));
    }

    @Test
    void theEndIsAlwaysTheLastCut() {
        assertEquals(List.of(new ShotPieces.Piece(0, 350), new ShotPieces.Piece(350, 420)),
                ShotPieces.cut(List.of(350, 400), 420, 400), "a footer after the last message goes in the last piece");
    }
}

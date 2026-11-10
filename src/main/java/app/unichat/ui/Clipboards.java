package app.unichat.ui;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/** The system clipboard. */
public final class Clipboards {
    private Clipboards() {}

    public static void copy(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }
}

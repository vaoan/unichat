package app.unichat.ui.timeline;

import app.unichat.ui.chats.ChatMenus;
import app.unichat.model.RoomFeatures;
import app.unichat.model.TextFormat;
import app.unichat.support.I18n;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.util.function.Supplier;

/**
 * The message box's right-click menu: the usual edits, then Format with what the chat's network shows (its bridge's
 * {@code formatting} feature). A format wraps the selection in its marks, and takes them off when they're there.
 */
final class FormatMenu {
    private FormatMenu() {}

    /** ⌘B, ⌘U and ⌘⇧X, as in text editors; italic has none, ⌘I writes with AI. */
    private static KeyCombination shortcut(TextFormat f) {
        return switch (f) {
            case BOLD -> new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN);
            case UNDERLINE -> new KeyCodeCombination(KeyCode.U, KeyCombination.SHORTCUT_DOWN);
            case STRIKE -> new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
            default -> null;
        };
    }

    static ContextMenu of(TextArea box, Supplier<RoomFeatures> features) {
        MenuItem undo = ChatMenus.item(I18n.t("composer.menu.undo"), box::undo);
        MenuItem redo = ChatMenus.item(I18n.t("composer.menu.redo"), box::redo);
        MenuItem cut = ChatMenus.item(I18n.t("composer.menu.cut"), box::cut);
        MenuItem copy = ChatMenus.item(I18n.t("composer.menu.copy"), box::copy);
        MenuItem paste = ChatMenus.item(I18n.t("composer.menu.paste"), box::paste);
        MenuItem delete = ChatMenus.item(I18n.t("composer.menu.delete"), () -> box.replaceSelection(""));
        MenuItem all = ChatMenus.item(I18n.t("composer.menu.selectAll"), box::selectAll);
        Menu format = new Menu(I18n.t("composer.menu.format"));
        ContextMenu menu = new ContextMenu(undo, redo, new SeparatorMenuItem(), cut, copy, paste, delete,
                new SeparatorMenuItem(), all, new SeparatorMenuItem(), format);
        menu.setOnShowing(e -> {
            boolean selected = !box.getSelectedText().isEmpty();
            undo.setDisable(!box.isUndoable());
            redo.setDisable(!box.isRedoable());
            cut.setDisable(!selected || !box.isEditable());
            copy.setDisable(!selected);
            delete.setDisable(!selected || !box.isEditable());
            RoomFeatures f = features.get();
            format.getItems().clear();
            for (TextFormat t : TextFormat.values()) {
                if (f != null && !f.formats(t)) continue;
                MenuItem m = ChatMenus.item(I18n.t("composer.format." + t.name().toLowerCase(java.util.Locale.ROOT)), () -> apply(box, t));
                m.setAccelerator(shortcut(t)); // shown; the box's key handler does it (onKey)
                format.getItems().add(m);
            }
            format.setDisable(format.getItems().isEmpty() || !box.isEditable());
        });
        return menu;
    }

    /** The format a shortcut asks for, if the chat shows it; the key is used either way so nothing else takes it. */
    static boolean onKey(KeyEvent e, TextArea box, RoomFeatures features) {
        for (TextFormat t : TextFormat.values()) {
            KeyCombination k = shortcut(t);
            if (k == null || !k.match(e)) continue;
            e.consume();
            if (features == null || features.formats(t)) apply(box, t);
            return true;
        }
        return false;
    }

    static void apply(TextArea box, TextFormat f) {
        int from = box.getSelection().getStart(), to = box.getSelection().getEnd();
        TextFormat.Edit edit = f.apply(box.getText(), from, to);
        box.replaceText(0, box.getLength(), edit.text()); // one step: ⌘Z takes it back whole
        box.selectRange(edit.from(), edit.to());
    }
}

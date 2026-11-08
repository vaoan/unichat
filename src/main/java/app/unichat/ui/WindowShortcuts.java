package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/** The keys that work anywhere in the main window, focus cycling between its zones (F6) and the text size. */
final class WindowShortcuts implements EventHandler<KeyEvent> {
    private final MainView view;
    private final ChatService svc;
    private final Rail rail;
    private final ChatListPane listPane;
    private final ThreadPane thread;
    private final SidePanel panel;

    WindowShortcuts(MainView view, ChatService svc, Rail rail, ChatListPane listPane, ThreadPane thread, SidePanel panel) {
        this.view = view;
        this.svc = svc;
        this.rail = rail;
        this.listPane = listPane;
        this.thread = thread;
        this.panel = panel;
    }

    /** Text size: delta 0 resets to 100 %. */
    private void zoom(double delta) {
        var s = Settings.get();
        s.setFontScale(delta == 0 ? 1.0 : s.fontScale() + delta);
        Theme.applyAll();
        listPane.refresh();
        thread.redraw();
        view.toast(I18n.t("windowShortcuts.textSize", Math.round(s.fontScale() * 100)));
    }

    /** F6 / Shift+F6: rail → chat list → messages → message input (→ side panel when open). */
    private void cycleFocus(int dir) {
        Node owner = view.getScene() == null ? null : view.getScene().getFocusOwner();
        int current = -1;
        for (Node n = owner; n != null; n = n.getParent()) {
            if (n == rail) current = 0;
            else if (n == listPane) current = 1;
            else if (n instanceof javafx.scene.control.ListView<?> lv && lv.getStyleClass().contains("timeline")) current = 2;
            else if (n == thread.composer()) current = 3;
            else if (n == panel) current = 4;
            if (current >= 0) break;
        }
        int zones = thread.chat() == null ? 2 : view.infoVisible() ? 5 : 4;
        int next = Math.floorMod((current < 0 ? (dir > 0 ? -1 : 0) : current) + dir, zones);
        switch (next) {
            case 0 -> { Node b = rail.lookup(".rail-button"); if (b != null) b.requestFocus(); }
            case 1 -> listPane.focusList();
            case 2 -> thread.focusTimeline();
            case 4 -> { Node b = panel.lookup(".chip"); if (b != null) b.requestFocus(); }
            default -> thread.composer().focus();
        }
    }

    /**
     * A plain key that nothing used (the timeline's R, T, P and E are consumed before this), pressed outside a text box
     * with a chat open: the message box takes the focus, and the character that follows lands there.
     */
    void typeIntoComposer(KeyEvent e) {
        if (e.isConsumed() || e.isShortcutDown() || e.isControlDown() || e.isMetaDown() || thread.chat() == null) return;
        if (!isTypedText(e.getText()) || view.overlayOpen()) return;
        Node owner = view.getScene() == null ? null : view.getScene().getFocusOwner();
        if (owner instanceof javafx.scene.control.TextInputControl) return;
        if (inTimeline(owner) && isTimelineKey(e.getCode())) return; // a shortcut this message doesn't allow: nothing
        thread.composer().takeTyping();
    }

    /** The message keys (ThreadPane.onTimelineKey): reply, transcribe, play, edit, and the digits that react. */
    static boolean isTimelineKey(KeyCode code) {
        return code == KeyCode.R || code == KeyCode.T || code == KeyCode.P || code == KeyCode.E || code.isDigitKey();
    }

    private static boolean inTimeline(Node n) {
        for (; n != null; n = n.getParent()) {
            if (n instanceof javafx.scene.control.ListView<?> lv && lv.getStyleClass().contains("timeline")) return true;
        }
        return false;
    }

    /** Letters, digits and signs; not space or Enter (they press the focused button or open a message's menu). */
    static boolean isTypedText(String text) {
        return text != null && !text.isEmpty() && !text.isBlank() && text.codePoints().noneMatch(Character::isISOControl);
    }

    @Override public void handle(KeyEvent e) {
        boolean mod = e.isShortcutDown();
        Chat c = thread.chat();
        if (mod && e.getCode() == KeyCode.K) {
            view.showCommandBar();
        } else if (mod && e.getCode() == KeyCode.N) {
            view.showNewChat();
        } else if (e.getCode() == KeyCode.ESCAPE && view.overlayOpen()) {
            view.hideOverlay();
        } else if (mod && e.getCode() == KeyCode.COMMA) {
            view.showSettings();
        } else if (mod && e.getCode() == KeyCode.F) {
            listPane.focusSearch();
        } else if (e.isAltDown() && (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN)) {
            listPane.move(e.getCode() == KeyCode.DOWN ? 1 : -1, e.isShiftDown());
        } else if (mod && e.getCode() == KeyCode.E && c != null) {
            svc.setArchived(c.id(), !c.archived());
            view.toast(c.archived() ? I18n.t("windowShortcuts.unarchived") : I18n.t("windowShortcuts.archived"));
        } else if (mod && e.isShiftDown() && e.getCode() == KeyCode.U && c != null) {
            if (c.isUnread()) svc.markRead(c.id()); else svc.setMarkedUnread(c.id(), true);
        } else if (mod && e.isShiftDown() && e.getCode() == KeyCode.I && c != null) {
            view.toggleInfo();
        } else if (mod && !e.isShiftDown() && c != null && thread.composer().aiKeyPressed(e.getCode())) {
            e.consume();
        } else if (e.getCode() == KeyCode.F6) {
            cycleFocus(e.isShiftDown() ? -1 : 1);
        } else if (mod && (e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.PLUS || e.getCode() == KeyCode.ADD)) {
            zoom(0.1);
        } else if (mod && (e.getCode() == KeyCode.MINUS || e.getCode() == KeyCode.SUBTRACT)) {
            zoom(-0.1);
        } else if (mod && (e.getCode() == KeyCode.DIGIT0 || e.getCode() == KeyCode.NUMPAD0)) {
            zoom(0);
        } else if (mod && e.getCode().isDigitKey()) {
            switch (e.getCode()) {
                case DIGIT1 -> view.setView(ChatFilter.View.INBOX, false);
                case DIGIT2 -> view.setView(ChatFilter.View.INBOX, true);
                case DIGIT3 -> view.setView(ChatFilter.View.FAVORITES, false);
                case DIGIT4 -> view.setView(ChatFilter.View.LOW_PRIORITY, false);
                default -> { return; }
            }
        } else {
            return;
        }
        e.consume();
    }
}

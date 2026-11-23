package app.unichat.ui.desktop;

import app.unichat.support.ScreenAway;
import java.util.function.Consumer;

/**
 * macOS: the menu-bar helper (native/UnichatBar.swift) says when the screen locks or the display sleeps, and back.
 * Its lines are read on {@link NativeBar}'s own thread; when it ends, or isn't running, the screen counts as here.
 */
public final class MacScreen implements ScreenAway {
    @Override
    public void start(Consumer<Boolean> away) {
        NativeBar.onScreen(away);
    }
}

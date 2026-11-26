package app.unichat.ui.media;

import javafx.stage.Screen;

/** How many device pixels a layout pixel is on the sharpest screen the window may be dragged to (at least 1). */
public final class ScreenScale {
    private ScreenScale() {}

    /** The largest output scale of the connected screens; read each call (screens come and go), it's cheap. Read on the FX thread. */
    public static double max() {
        double scale = 1;
        for (Screen screen : Screen.getScreens()) scale = Math.max(scale, screen.getOutputScaleX());
        return scale;
    }
}

package app.unichat.ui;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;

/** Line icons drawn from 24x24 SVG paths, colored by the {@code .icon} CSS class (-fx-background-color). */
public final class Icons {
    private Icons() {}

    /** A button showing only an icon; {@code tooltip} is also what a screen reader says. */
    static javafx.scene.control.Button iconButton(String icon, String tooltip, Runnable action) {
        javafx.scene.control.Button b = new javafx.scene.control.Button();
        b.setGraphic(icon(icon, 18));
        b.getStyleClass().add("icon-button");
        b.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        b.setAccessibleText(tooltip);
        if (action != null) b.setOnAction(e -> action.run());
        return b;
    }

    public static final String SEARCH = "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14Zm-9 7a9 9 0 1 1 16.03 5.62l3.68 3.67-1.42 1.42-3.67-3.68A9 9 0 0 1 2 11Z";
    public static final String INBOX = "M4 4h16l2 9v7H2v-7l2-9Zm1.6 2-1.33 6H9l1 2h4l1-2h4.73L18.4 6H5.6ZM4 14v4h16v-4h-3.76l-1 2H8.76l-1-2H4Z";
    public static final String ARCHIVE = "M3 3h18v5h-1v13H4V8H3V3Zm3 5v11h12V8H6Zm-1-3v1h14V5H5Zm4 6h6v2H9v-2Z";
    public static final String FOLDER = "M3 5h7l2 2h9v13H3V5Zm2 2v11h14V9h-7.83l-2-2H5Z";
    public static final String SETTINGS = "M10.3 2h3.4l.5 2.6 1.6.9 2.5-.9 1.7 2.9-2 1.8v1.8l2 1.7-1.7 3-2.5-.9-1.6.9-.5 2.6h-3.4l-.5-2.6-1.6-.9-2.5.9-1.7-3 2-1.7V9.3l-2-1.8 1.7-2.9 2.5.9 1.6-.9.5-2.6ZM12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Z";
    public static final String SEND = "M3.4 20.4 21 12 3.4 3.6 3.4 10l12.6 2-12.6 2v6.4Z";
    public static final String ATTACH = "M16.5 6v11.5a4 4 0 0 1-8 0V5a2.5 2.5 0 0 1 5 0v10.5a1 1 0 0 1-2 0V6H10v9.5a2.5 2.5 0 0 0 5 0V5a4 4 0 0 0-8 0v12.5a5.5 5.5 0 0 0 11 0V6h-1.5Z";
    public static final String EMOJI = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20Zm0 2a8 8 0 1 0 0 16 8 8 0 0 0 0-16Zm-3.5 4a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3Zm7 0a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3ZM7.5 13.5h9a4.5 4.5 0 0 1-9 0Z";
    public static final String REPLY = "M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11Z";
    public static final String MORE = "M5 10a2 2 0 1 1 0 4 2 2 0 0 1 0-4Zm7 0a2 2 0 1 1 0 4 2 2 0 0 1 0-4Zm7 0a2 2 0 1 1 0 4 2 2 0 0 1 0-4Z";
    public static final String STAR = "m12 2.5 2.9 6.1 6.6.8-4.9 4.6 1.3 6.6L12 17.3l-5.9 3.3 1.3-6.6-4.9-4.6 6.6-.8L12 2.5Z";
    public static final String BELL_OFF = "M20.8 19.4 4.6 3.2 3.2 4.6l3.1 3.1A6 6 0 0 0 6 9.5V15l-2 2v1h12.6l2.8 2.8 1.4-1.4ZM18 15V9.5a6 6 0 0 0-8.7-5.4L18 12.8V15Zm-6 7a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2Z";
    public static final String PIN = "M16 3v2h-1v5.2l3 3V15h-5v6l-1 1-1-1v-6H6v-1.8l3-3V5H8V3h8Z";
    public static final String INFO = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20Zm0 2a8 8 0 1 0 0 16 8 8 0 0 0 0-16Zm-1 6h2v7h-2v-7Zm0-3h2v2h-2V7Z";
    public static final String CHECK = "m9 16.2-4.2-4.2-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2Z";
    public static final String CHECK_DOUBLE = "m1.5 12.6 1.4-1.4 4.6 4.6-1.4 1.4-4.6-4.6Zm5.8 2.8L18.1 4.6l1.4 1.4L7.3 18.2l-.7-.7.7-2.1ZM12.4 16.8l9.8-9.8 1.4 1.4L12.4 19.6l-4.1-4.1 1.4-1.4 2.7 2.7Z";
    public static final String CLOCK = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20Zm0 2a8 8 0 1 0 0 16 8 8 0 0 0 0-16Zm1 3v4.6l3.5 2.1-1 1.7L11 12.7V7h2Z";
    public static final String CLOSE = "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12l5.6 5.6-1.4 1.4-5.6-5.6L6.4 19 5 17.6l5.6-5.6L5 6.4 6.4 5Z";
    public static final String EDIT = "M4 17.3V20h2.7l9.9-9.9-2.7-2.7L4 17.3Zm16.7-10.1a1 1 0 0 0 0-1.4l-2.5-2.5a1 1 0 0 0-1.4 0l-1.6 1.6 3.9 3.9 1.6-1.6Z";
    public static final String TRASH = "M9 3h6l1 1h4v2H4V4h4l1-1ZM5 7h14l-1 14H6L5 7Zm4 3v8h2v-8H9Zm4 0v8h2v-8h-2Z";
    public static final String CHEVRON_RIGHT = "M9 6l6 6-6 6-1.4-1.4 4.6-4.6-4.6-4.6L9 6Z";
    public static final String CHEVRON_LEFT = "M15 6l-6 6 6 6 1.4-1.4-4.6-4.6 4.6-4.6L15 6Z";
    public static final String CHEVRON_DOWN = "M6 9l6 6 6-6-1.4-1.4-4.6 4.6-4.6-4.6L6 9Z";
    public static final String PLUS = "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6V5Z";
    public static final String CHAT = "M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2Zm0 2v11.2L7.2 16H20V6H4Z";
    public static final String UNREAD = "M12 6a6 6 0 1 1 0 12 6 6 0 0 1 0-12Z";
    public static final String LOW = "M12 16 6 10l1.4-1.4 4.6 4.6 4.6-4.6L18 10l-6 6Z";
    public static final String FILE = "M6 2h8l6 6v14H6V2Zm7 1.5V9h5.5L13 3.5ZM8 4v16h10V11h-7V4H8Z";
    public static final String IMAGE = "M3 4h18v16H3V4Zm2 2v8.6l4-4 4 4 2.5-2.5 3.5 3.5V6H5Zm10.5 1.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3Z";
    public static final String UPLOAD = "M11 16V6.8L7.4 10.4 6 9l6-6 6 6-1.4 1.4L13 6.8V16h-2Zm-7 2h16v2H4v-2Z";
    public static final String REWIND = "M11 6v12L2.5 12 11 6Zm9.5 0v12L12 12l8.5-6Z";
    public static final String FAST_FORWARD = "M13 6v12l8.5-6L13 6ZM3.5 6v12L12 12 3.5 6Z";
    public static final String REPLAY = "M12 5V1L7 6l5 5V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8Z";
    public static final String REPEAT = "M7 7h10v3l4-4-4-4v3H5v6h2V7Zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4Z";
    public static final String FULLSCREEN = "M4 4h6v2H6v4H4V4Zm10 0h6v6h-2V6h-4V4ZM4 14h2v4h4v2H4v-6Zm14 0h2v6h-6v-2h4v-4Z";
    public static final String FULLSCREEN_EXIT = "M8 4h2v6H4V8h4V4Zm6 0h2v4h4v2h-6V4ZM4 14h6v6H8v-4H4v-2Zm10 0h6v2h-4v4h-2v-6Z";
    public static final String CAMERA = "M9 4h6l1.5 2H20a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h3.5L9 4Zm3 4.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9Zm0 2a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5Z";
    public static final String EXTERNAL = "M14 3h7v7h-2V6.4l-8.3 8.3-1.4-1.4L17.6 5H14V3ZM5 5h6v2H5v12h12v-6h2v8H3V5h2Z";
    public static final String DOWNLOAD = "M11 3h2v9.2l3.6-3.6 1.4 1.4-6 6-6-6 1.4-1.4 3.6 3.6V3ZM4 19h16v2H4v-2Z";
    public static final String PLAY = "M8 5v14l11-7L8 5Z";
    public static final String SPARKLE = "M11 3l2 6 6 2-6 2-2 6-2-6-6-2 6-2 2-6Zm7.5 11 1 2.5 2.5 1-2.5 1-1 2.5-1-2.5-2.5-1 2.5-1 1-2.5Z";
    public static final String MIC = "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3Zm-6 9h2a4 4 0 0 0 8 0h2a6 6 0 0 1-5 5.9V21h-2v-3.1A6 6 0 0 1 6 12Z";
    public static final String PAUSE = "M7 5h4v14H7V5Zm6 0h4v14h-4V5Z";
    public static final String VOLUME = "M4 9v6h4l5 4V5L8 9H4Zm12 .5a4 4 0 0 1 0 5v-5Z";
    public static final String VOLUME_OFF = "M4 9v6h4l5 4V5L8 9H4Zm11.5.5 1-1 2 2 2-2 1 1-2 2 2 2-1 1-2-2-2 2-1-1 2-2-2-2Z";
    public static final String ERROR = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20Zm-1 5v7h2V7h-2Zm0 9v2h2v-2h-2Z";
    public static final String LOGOUT = "M10 3h9a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-9v-2h9V5h-9V3Zm1.6 4.4L13 6l6 6-6 6-1.4-1.4L15.2 13H3v-2h12.2l-3.6-3.6Z";
    public static final String PEOPLE = "M9 4a4 4 0 1 1 0 8 4 4 0 0 1 0-8Zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4Zm7.5 0a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7ZM1 20c0-3.3 3.6-6 8-6s8 2.7 8 6v1h-2v-1c0-2-2.7-4-6-4s-6 2-6 4v1H1v-1Zm16.7-5.9c3 .4 5.3 2.3 5.3 4.9v2h-2v-2c0-1.4-1.3-2.6-3.3-3.1v-1.8Z";
    public static final String SPELLCHECK = "M7.5 3h2L15 16h-2.2l-1.2-3H5.4l-1.2 3H2L7.5 3Zm1 2.6L6.2 11h4.6L8.5 5.6ZM21 12l-7.5 8-3.5-3.5 1.4-1.4 2.1 2.1 6.1-6.6L21 12Z";
    public static final String MINUS = "M5 11h14v2H5v-2Z";
    public static final String ROTATE = "M12 5V1l5 5-5 5V7a6 6 0 1 0 6 6h2a8 8 0 1 1-8-8Z";
    public static final String BACK = "M15.4 6 14 4.6 6.6 12l7.4 7.4 1.4-1.4-6-6 6-6Z";

    /** The app logo at {@code size} px (rendered from the bundled 256 px PNG). */
    public static Node logo(double size) {
        javafx.scene.image.ImageView v = new javafx.scene.image.ImageView(new javafx.scene.image.Image(
                java.util.Objects.requireNonNull(Icons.class.getResource("/app/unichat/icons/app-256.png")).toExternalForm(),
                size * 2, size * 2, true, true));
        v.setFitWidth(size);
        v.setFitHeight(size);
        return v;
    }

    /**
     * The text size (Ajustes → Accesibilidad, ⌘+ and ⌘−), set by {@link Theme}: icons grow with the text, or at 160 % they
     * look small and off-centre beside it. FX thread.
     */
    static final javafx.beans.property.DoubleProperty SCALE = new javafx.beans.property.SimpleDoubleProperty(1);

    /** An icon at exactly {@code size} px whatever the text size: for glyphs inside a box of fixed size (a network's tile). */
    public static Node fixedIcon(String path, double size) {
        Region r = (Region) icon(path, size);
        for (var p : java.util.List.of(r.minWidthProperty(), r.minHeightProperty(), r.prefWidthProperty(),
                r.prefHeightProperty(), r.maxWidthProperty(), r.maxHeightProperty())) {
            p.unbind();
            p.set(size);
        }
        return r;
    }

    /** An icon node sized {@code size} px at 100 % text; color comes from CSS ({@code .icon} / parent rules). */
    public static Node icon(String path, double size) {
        SVGPath svg = new SVGPath();
        // Two invisible specks pin the bounds to the 24x24 viewbox so non-square glyphs aren't stretched.
        svg.setContent(path + " M0 0h.01v.01H0Z M23.99 23.99h.01v.01h-.01Z");
        Region r = new Region();
        r.setShape(svg);
        r.getStyleClass().add("icon");
        javafx.beans.binding.DoubleBinding px = SCALE.multiply(size);
        for (var p : java.util.List.of(r.minWidthProperty(), r.minHeightProperty(), r.prefWidthProperty(),
                r.prefHeightProperty(), r.maxWidthProperty(), r.maxHeightProperty())) p.bind(px);
        return r;
    }
}

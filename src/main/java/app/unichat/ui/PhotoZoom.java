package app.unichat.ui;

/**
 * Where a photo sits in the viewer: its scale (1 = one image pixel per screen pixel), its turn and how far it moved
 * from the centre. Until the person zooms it follows the window; it can't be dragged away beyond its own edges.
 */
final class PhotoZoom {
    private static final double MARGIN = 40;
    private static final double MAX_SCALE = 8;
    private static final double SAME_SCALE = 1e-6;

    private double imageWidth;
    private double imageHeight;
    private double viewWidth;
    private double viewHeight;
    private double scale = 1;
    private double offsetX;
    private double offsetY;
    private int rotation;
    private boolean fitted = true;

    /** A new picture: fits the window, unturned. */
    void setImage(double width, double height) {
        imageWidth = width;
        imageHeight = height;
        rotation = 0;
        fit();
    }

    void resize(double width, double height) {
        viewWidth = width;
        viewHeight = height;
        if (fitted) fit(); else {
            scale = clamp(scale);
            keepInside();
        }
    }

    void fit() {
        fitted = true;
        scale = fitScale();
        offsetX = 0;
        offsetY = 0;
    }

    /** One image pixel per screen pixel, keeping the point under ({@code x}, {@code y}) in place. */
    void actualSize(double x, double y) {
        zoomAt(1 / scale, x, y);
    }

    void actualSize() {
        actualSize(viewWidth / 2, viewHeight / 2);
    }

    /** Double click: the whole picture, or its real size around the click. */
    void toggle(double x, double y) {
        if (fitted) actualSize(x, y); else fit();
    }

    void zoomBy(double factor) {
        zoomAt(factor, viewWidth / 2, viewHeight / 2);
    }

    /** Scales by {@code factor}, keeping the point under ({@code x}, {@code y}) in place. */
    void zoomAt(double factor, double x, double y) {
        double next = clamp(scale * factor);
        double applied = next / scale;
        double fromCentreX = x - viewWidth / 2;
        double fromCentreY = y - viewHeight / 2;
        offsetX = fromCentreX - (fromCentreX - offsetX) * applied;
        offsetY = fromCentreY - (fromCentreY - offsetY) * applied;
        scale = next;
        fitted = Math.abs(scale - fitScale()) < SAME_SCALE;
        keepInside();
    }

    void pan(double dx, double dy) {
        offsetX += dx;
        offsetY += dy;
        keepInside();
    }

    /** A quarter turn clockwise, around the centre of the window. */
    void rotate() {
        rotation = (rotation + 90) % 360;
        double x = offsetX;
        offsetX = -offsetY;
        offsetY = x;
        if (fitted) fit(); else keepInside();
    }

    boolean canPan() {
        return shownWidth() > viewWidth || shownHeight() > viewHeight;
    }

    boolean isFitted() {
        return fitted;
    }

    double scale() {
        return scale;
    }

    int rotation() {
        return rotation;
    }

    int percent() {
        return (int) Math.round(scale * 100);
    }

    /** The unturned picture's size on screen. */
    double width() {
        return imageWidth * scale;
    }

    double height() {
        return imageHeight * scale;
    }

    /** The unturned picture's corner: a node turns around its own centre. */
    double left() {
        return viewWidth / 2 + offsetX - width() / 2;
    }

    double top() {
        return viewHeight / 2 + offsetY - height() / 2;
    }

    private boolean sideways() {
        return rotation % 180 != 0;
    }

    private double shownWidth() {
        return sideways() ? height() : width();
    }

    private double shownHeight() {
        return sideways() ? width() : height();
    }

    private double fitScale() {
        if (imageWidth <= 0 || imageHeight <= 0) return 1;
        double across = sideways() ? imageHeight : imageWidth;
        double down = sideways() ? imageWidth : imageHeight;
        return Math.min(Math.max(1, viewWidth - MARGIN) / across, Math.max(1, viewHeight - MARGIN) / down);
    }

    private double clamp(double wanted) {
        double fit = fitScale();
        return Math.max(Math.min(fit, 1), Math.min(Math.max(fit, MAX_SCALE), wanted));
    }

    private void keepInside() {
        double roomX = Math.max(0, (shownWidth() - viewWidth) / 2);
        double roomY = Math.max(0, (shownHeight() - viewHeight) / 2);
        offsetX = Math.max(-roomX, Math.min(roomX, offsetX));
        offsetY = Math.max(-roomY, Math.min(roomY, offsetY));
    }
}

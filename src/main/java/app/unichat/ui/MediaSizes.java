package app.unichat.ui;

/** How big media shows in a message, from the size the bridge reports (0 when unknown). */
final class MediaSizes {
    record Size(double width, double height) {}

    private static final double MAX_MEDIA = 320;
    private static final double MAX_STICKER = 180;
    private static final double MIN_STICKER = 60;

    private MediaSizes() {}

    /** Photos and videos, scaled down to fit; 240 x 180 when unknown. */
    static Size media(int width, int height) {
        double w = width > 0 ? width : 240, h = height > 0 ? height : 180;
        double s = Math.min(1, Math.min(MAX_MEDIA / w, MAX_MEDIA / h));
        return new Size(Math.max(80, w * s), Math.max(60, h * s));
    }

    /** Stickers are small and shown without a bubble, like on the phone; square when unknown. */
    static Size sticker(int width, int height) {
        double w = width > 0 ? width : MAX_STICKER, h = height > 0 ? height : MAX_STICKER;
        double s = Math.min(1, Math.min(MAX_STICKER / w, MAX_STICKER / h));
        return new Size(Math.max(MIN_STICKER, w * s), Math.max(MIN_STICKER, h * s));
    }
}

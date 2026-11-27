package app.unichat.ui.media;

/**
 * Every picture the window decoded and keeps for its next look: avatars ({@link ImageCache}), photos ({@link Stills}),
 * animations ({@link AnimatedImage}'s decoded cache) and files' previews ({@link MessageMedia}). Let go together when
 * the window is (closed to the menu bar a while), so nothing it showed stays in memory; it decodes them again on opening.
 */
public final class Pictures {
    private Pictures() {}

    /** All of them let go. FX thread or any. */
    public static void forget() {
        ImageCache.forget();
        Stills.forget();
        AnimatedImage.forget();
        MessageMedia.FILE_PREVIEWS.clear();
    }
}

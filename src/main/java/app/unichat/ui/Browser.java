package app.unichat.ui;

/** The person's web browser. */
final class Browser {
    private Browser() {}

    /** Through AWT's own thread (mistakes.md). No browser: nothing happens, so the address must also be on screen. */
    static void open(String url) {
        java.awt.EventQueue.invokeLater(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception ignored) {
                // See above.
            }
        });
    }
}

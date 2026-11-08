package app.unichat.ui;

/** The person's web browser, and the system's file manager. */
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

    /** Shows {@code folder} in Finder / Explorer (created if missing), through AWT's thread; {@code failed} on the FX thread. */
    static void openFolder(java.nio.file.Path folder, Runnable failed) {
        java.awt.EventQueue.invokeLater(() -> {
            try {
                java.nio.file.Files.createDirectories(folder);
                java.awt.Desktop.getDesktop().open(folder.toFile());
            } catch (Exception e) {
                javafx.application.Platform.runLater(failed);
            }
        });
    }
}

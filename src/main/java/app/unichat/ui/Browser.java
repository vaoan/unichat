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

    /** How a file is opened with the system's app; tests put a recorder here. Runs on AWT's thread. */
    @FunctionalInterface
    interface Opener {
        void open(java.nio.file.Path file) throws Exception;
    }

    static Opener opener = Browser::systemOpen;

    /**
     * Opens {@code file} with the system's app for it, through AWT's own thread (mistakes.md): called from wherever a
     * download finished, it did nothing at all on macOS. {@code failed} on the FX thread when there's no way.
     */
    static void openFile(java.nio.file.Path file, Runnable failed) {
        java.awt.EventQueue.invokeLater(() -> {
            try {
                opener.open(file);
            } catch (Exception e) {
                javafx.application.Platform.runLater(failed);
            }
        });
    }

    /** AWT's Desktop; where it can't (it isn't supported), the system's own command: open, start, xdg-open. */
    private static void systemOpen(java.nio.file.Path file) throws Exception {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
            java.awt.Desktop.getDesktop().open(file.toFile());
            return;
        }
        String f = file.toString();
        java.util.List<String> cmd = app.unichat.support.Os.mac() ? java.util.List.of("open", f)
                : app.unichat.support.Os.windows() ? java.util.List.of("rundll32", "url.dll,FileProtocolHandler", f)
                : java.util.List.of("xdg-open", f);
        new ProcessBuilder(cmd).start();
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

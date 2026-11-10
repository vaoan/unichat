package app.unichat.ui;

/** The person's web browser, and the system's file manager. */
public final class Browser {
    private static final java.util.Set<String> WEB = java.util.Set.of("http", "https");

    private Browser() {}

    /**
     * The address, if it's a web page: http or https, with a host. Links come from messages anyone sends, and the system hands
     * other schemes to whatever claims them ({@code file:} runs a program, {@code smb:} sends the Windows password's hash,
     * {@code ms-msdt:} was a remote-code hole), so nothing else ever leaves Unichat.
     */
    static java.util.Optional<java.net.URI> web(String url) {
        if (url == null) return java.util.Optional.empty();
        try {
            java.net.URI uri = new java.net.URI(url.strip());
            String scheme = uri.getScheme();
            boolean ok = scheme != null && WEB.contains(scheme.toLowerCase(java.util.Locale.ROOT)) && uri.getHost() != null;
            return ok ? java.util.Optional.of(uri) : java.util.Optional.empty();
        } catch (java.net.URISyntaxException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Every external link goes through here (only web pages, see {@link #web}). Through AWT's own thread (mistakes.md). No
     * browser, or an address refused: nothing happens, so the address must also be on screen.
     */
    public static void open(String url) {
        web(url).ifPresent(uri -> java.awt.EventQueue.invokeLater(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(uri);
            } catch (Exception ignored) {
                // See above.
            }
        }));
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
    public static void openFile(java.nio.file.Path file, Runnable failed) {
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
    public static void openFolder(java.nio.file.Path folder, Runnable failed) {
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

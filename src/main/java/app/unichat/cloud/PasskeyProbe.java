package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import app.unichat.support.I18n;

import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Runs the passkey probe ({@link PasskeyPage}) when the person asks for it: started with {@code -Dunichat.passkeyProbe=true}
 * or the argument {@link #ARGUMENT} (the installed app takes arguments, not {@code -D}). Each finding goes to
 * {@code <data>/logs/passkey-probe.txt}, which the person sends back, and to the log; never the PRF's bytes.
 */
public final class PasskeyProbe {
    public static final String FLAG = "unichat.passkeyProbe";
    public static final String ARGUMENT = "--passkey-probe";

    /** The one probe running: the port is one, so a new one ends it first. */
    private static CompletableFuture<Void> current;

    private PasskeyProbe() {}

    public static boolean asked() {
        return Boolean.getBoolean(FLAG);
    }

    /** {@link #ARGUMENT} among the app's arguments asks for it as the flag does. */
    public static void fromArguments(String[] args) {
        if (Arrays.asList(args).contains(ARGUMENT)) System.setProperty(FLAG, "true");
    }

    public static Path file() {
        return Settings.dataDir().resolve("logs").resolve("passkey-probe.txt");
    }

    /**
     * At start: the page in the browser if the probe was asked for, else nothing (null). Its address goes to the log too
     * (no browser opened: it can be typed), and so does why it couldn't start (the port busy); the future ends once
     * anything is told.
     */
    public static CompletableFuture<Void> onStart(Consumer<URI> browser) {
        return onStart(browser, Loopback.PORT, file(), System.err::println);
    }

    static CompletableFuture<Void> onStart(Consumer<URI> browser, int port, Path file, Consumer<String> log) {
        return onStart(browser, port, file, log, Loopback.DEADLINE);
    }

    static CompletableFuture<Void> onStart(Consumer<URI> browser, int port, Path file, Consumer<String> log, Duration deadline) {
        if (!asked()) return null;
        Consumer<URI> told = uri -> {
            log.accept("Passkey probe: open " + uri + " in the browser");
            browser.accept(uri);
        };
        return run(told, f -> { }, port, file, deadline).whenComplete((ok, e) -> {
            Throwable why = e == null ? null : Errors.unwrap(e);
            if (why != null && !(why instanceof java.util.concurrent.CancellationException) && !(why instanceof Loopback.Expired)) {
                log.accept("Passkey probe: couldn't start: " + Errors.message(why));
            }
        });
    }

    /** Opens the page (a probe already running ends first); {@code told} hears each finding once it's written down. */
    public static CompletableFuture<Void> run(Consumer<URI> browser, Consumer<PasskeyPage.Finding> told) {
        return run(browser, told, Loopback.PORT, file());
    }

    static CompletableFuture<Void> run(Consumer<URI> browser, Consumer<PasskeyPage.Finding> told, int port, Path file) {
        return run(browser, told, port, file, Loopback.DEADLINE);
    }

    private static synchronized CompletableFuture<Void> run(Consumer<URI> browser, Consumer<PasskeyPage.Finding> told, int port,
                                                            Path file, Duration deadline) {
        stop();
        try {
            current = PasskeyPage.start(browser, f -> {
                record(file, f);
                told.accept(f);
            }, port, deadline);
            return current;
        } catch (BindException e) {
            return CompletableFuture.failedFuture(new IOException(I18n.t("settings.cloud.portBusy", port), e));
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static synchronized void stop() {
        if (current != null) current.cancel(false);
        current = null;
    }

    private static void record(Path file, PasskeyPage.Finding f) {
        String line = Instant.now() + " unichatOs=\"" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + "\" " + f.line();
        System.err.println("Passkey probe: " + line);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Passkey probe: couldn't write " + file + ": " + Errors.message(e));
        }
    }
}

package app.unichat.server;

import app.unichat.bridge.BridgePorts;
import app.unichat.settings.SyncedFile;
import app.unichat.support.Folders;
import app.unichat.support.I18n;
import app.unichat.support.Processes;
import app.unichat.support.Redactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Leaves a bridge ready to start: downloaded, with its config written and registered with the server. */
final class BridgeInstaller {
    /**
     * Bridges on the older mautrix framework (not bridgev2): no {@code -e} to write an example config and no login
     * API, so they log in through the bot chat.
     */
    static final Set<String> LEGACY_BRIDGES = Set.of("discord");

    /** Telegram only runs with the person's own keys (my.telegram.org). */
    record TelegramApi(String id, String hash) { }

    private final Path dir;
    private final BinaryStore store;
    private final Manifest manifest;
    private final Function<String, List<String>> program;

    BridgeInstaller(Path dir, BinaryStore store, Manifest manifest) {
        this(dir, store, manifest, b -> List.of(store.path(b).toString()));
    }

    /** {@code program}: the command that runs a bridge (tests use a child JVM). */
    BridgeInstaller(Path dir, BinaryStore store, Manifest manifest, Function<String, List<String>> program) {
        this.dir = dir;
        this.store = store;
        this.manifest = manifest;
        this.program = program;
    }

    /** {@code telegram} may be null for any other bridge, and without it Telegram can't be prepared. Blocking. */
    void prepare(String b, String doublePuppetToken, String statusUrl, TelegramApi telegram, Consumer<String> progress)
            throws IOException, InterruptedException {
        Manifest.Component c = manifest.bridge(b);
        if (c == null) throw new IOException(I18n.t("bridge.noPinnedVersion", b));
        if (!store.isInstalled(c)) {
            progress.accept(I18n.t("bridge.downloading", b));
            store.install(c);
        }
        Path bd = dir.resolve(b);
        Files.createDirectories(bd);
        Path config = bd.resolve("config.yaml");
        if (!Files.exists(config)) generateConfig(b, bd, config);
        boolean isTelegram = b.equals("telegram");
        if (isTelegram && telegram == null) throw new IOException(I18n.t("bridge.telegramKeys"));
        BridgeConfig.rewrite(config, new BridgeConfig.Settings(b, BridgePorts.BRIDGE_PORTS.get(b), LocalServer.DOMAIN,
                "@" + LocalServer.USER + ":" + LocalServer.DOMAIN, LocalServer.URL, bd, doublePuppetToken,
                isTelegram ? telegram.id() : null, isTelegram ? telegram.hash() : null, statusUrl));
        Path reg = bd.resolve("registration.yaml");
        if (!Files.exists(reg)) run(bd, command(b, "-g", "-c", "config.yaml", "-r", "registration.yaml"));
        // The bridge writes these with the default mode (and -g puts the tokens in config.yaml): only the person's.
        byte[] registration = Files.readAllBytes(reg);
        SyncedFile.replace(reg, registration, true);
        SyncedFile.replace(config, Files.readAllBytes(config), true);
        SyncedFile.replace(dir.resolve("appservices").resolve(b + ".yaml"), registration, true);
    }

    private void generateConfig(String b, Path bd, Path config) throws IOException, InterruptedException {
        if (LEGACY_BRIDGES.contains(b)) {
            // Legacy bridges have no -e: given an empty config they fill in every default, then stop asking for
            // the homeserver (expected: the non-zero exit is ignored, the filled file is what we want).
            Files.writeString(config, "{}\n");
            runIgnoringExit(bd, command(b, "-c", "config.yaml"));
            if (Files.size(config) < 100) throw new IOException(I18n.t("bridge.noConfig", b));
            // That run logged to its default file (unfiltered); from now on it logs to stdout (Redactor).
            Folders.delete(bd.resolve("logs"));
        } else {
            run(bd, command(b, "-e", "-c", "config.yaml"));
        }
    }

    private List<String> command(String b, String... args) {
        List<String> cmd = new ArrayList<>(program.apply(b));
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static void runIgnoringExit(Path workDir, List<String> cmd) throws IOException, InterruptedException {
        if (!Processes.quiet(cmd, workDir, 60).finished()) throw new IOException(I18n.t("bridge.timeout", name(cmd)));
    }

    /** Runs a bridge setup command; on failure its (redacted) output goes to the log and the error message. */
    private static void run(Path workDir, List<String> cmd) throws IOException, InterruptedException {
        Processes.Result r = Processes.capture(new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true), 60);
        if (!r.finished()) throw new IOException(I18n.t("bridge.timeout", name(cmd)));
        if (r.exit() != 0) {
            String text = Redactor.redact(r.out().strip());
            System.err.println("Bridge setup failed (" + name(cmd) + ", exit " + r.exit() + "): " + text);
            String last = text.lines().reduce((a, b) -> b).orElse(I18n.t("bridge.noDetails"));
            throw new IOException(I18n.t("bridge.setupFailed", name(cmd), last));
        }
    }

    private static Path name(List<String> cmd) {
        return Path.of(cmd.get(0)).getFileName();
    }
}

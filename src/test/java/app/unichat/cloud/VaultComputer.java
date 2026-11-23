package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.PathParts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One computer of the person's, for tests of rounds over a {@link FakeDav}: its own folder, its own settings (swapped in
 * and out of the one {@link Settings} around each call), its own {@link Cloud}.
 */
final class VaultComputer {
    /** What each computer holds of the settings. */
    static final List<String> KEYS = List.of(CloudNames.DONE, CloudNames.KEPT, VaultNames.LEFT, "cloud.machine", VaultParts.ID, VaultParts.KEY, VaultParts.PRIVATE, VaultParts.DECLINED,
        "cloud.base.settings", "cloud.base.ai", "cloud.base.secrets", "cloud.base.pinned", "cloud.media", "cloud.settings", "cloud.lastVerify",
        "server.telegram.apiId", "server.telegram.apiHash", "gif.giphy.key", "cloud.dropboxAppKey", "cloud.pcloudClientId", "stt.command",
        "ui.theme", VaultPeers.KEY, VaultActions.UNCONFIRMED, VaultMove.MOVED_AT, VaultMove.QUIET_SINCE, VaultMove.STRAYS_SEEN, VaultOff.KEY, VaultMove.RECORD, VaultParts.SEEN, VaultMove.KNOWN);

    final Map<String, String> values = new HashMap<>();
    final Path data;
    final Cloud cloud;
    final AtomicInteger pinnedAdopted = new AtomicInteger();
    /** Whether the account is still connected here: false, as disconnecting in Ajustes leaves it (no account). */
    volatile boolean connected = true;

    VaultComputer(FakeDav account, Path dir, String machine) throws IOException {
        values.put("cloud.machine", machine);
        data = Files.createDirectories(dir.resolve(machine));
        Path photos = Files.createDirectories(data.resolve("Ana"));
        Files.writeString(photos.resolve("a.jpg"), "foto");
        CloudAccount a = new CloudAccount(CloudAccount.Service.OTHER, account.url(), "u", "p");
        in(); // its machine id, which the texts are made with
        cloud = Cloud.create(() -> connected ? a : null, data, () -> null, b -> true, () -> { }, () -> List.of(photos),
            new Cloud.Texts(data.resolve("texts"), p -> false, s -> { }),
            new Cloud.Personal(this::pins, theirs -> {
                pinnedAdopted.incrementAndGet();
                try {
                    Files.write(data.resolve("pinned-messages.json"), theirs);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, data.resolve("transcripts")));
        out();
    }

    /** The settings {@link #KEYS} as they are, to put back after the test. */
    static Map<String, String> kept() {
        Map<String, String> out = new HashMap<>();
        for (String k : KEYS) out.put(k, Settings.get().getString(k, null));
        return out;
    }

    /** As {@code PinnedMessages.json} gives them: what the file holds, here. */
    private byte[] pins() {
        try {
            Path p = data.resolve("pinned-messages.json");
            return Files.exists(p) ? Files.readAllBytes(p) : "{}".getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void in() {
        for (String k : KEYS) Settings.get().set(k, values.get(k));
    }

    private void out() {
        for (String k : KEYS) values.put(k, Settings.get().getString(k, null));
    }

    VaultComputer set(String key, String value) {
        values.put(key, value);
        return this;
    }

    String get(String key) {
        return values.get(key);
    }

    VaultComputer write(String file, String text) throws IOException {
        Path p = data.resolve(file);
        Files.createDirectories(PathParts.parent(p));
        Files.writeString(p, text);
        return this;
    }

    String read(String file) throws IOException {
        Path p = data.resolve(file);
        return Files.exists(p) ? Files.readString(p) : null;
    }

    /** What starts the work {@link #as} waits for; throwing nothing checked, it adds nothing to what {@code as} throws. */
    @FunctionalInterface
    interface Call<T, E extends Exception> {
        T call() throws E;
    }

    /** What {@code call} gives, run as this computer: its settings in while it runs. */
    <T, E extends Exception> T as(Call<Future<T>, E> call) throws E, InterruptedException, java.util.concurrent.ExecutionException {
        in();
        try {
            return call.call().get();
        } finally {
            out();
        }
    }

    Cloud.Status round() throws InterruptedException, java.util.concurrent.ExecutionException {
        return as(cloud::syncNow);
    }

    void textsNow() throws InterruptedException, java.util.concurrent.ExecutionException {
        as(cloud::textsNow);
    }

    /** The vault {@code made} here, as {@code VaultActions.create} leaves this computer: opened, and the one to record who sent plain. */
    VaultComputer madeHere(Vault.Created made) {
        return opened(made.info(), made.computer()).set(VaultMove.RECORD, made.info().id());
    }

    /** Opened here: this computer's key and the vault's id kept, as opening it with a way in leaves them. */
    VaultComputer opened(Vault.Info vault, Vault.Computer computer) {
        set(VaultParts.ID, vault.id());
        set(VaultParts.KEY, Base64.getEncoder().encodeToString(computer.key()));
        return set(VaultParts.PRIVATE, Base64.getEncoder().encodeToString(computer.pair().getPrivate().getEncoded()));
    }
}

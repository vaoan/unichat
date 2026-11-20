package app.unichat.cloud;

import app.unichat.settings.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One computer of the person's, for tests of rounds over a {@link FakeDav}: its own folder, its own settings (swapped in
 * and out of the one {@link Settings} around each call), its own {@link Cloud}.
 */
final class VaultComputer {
    /** What each computer holds of the settings. */
    static final List<String> KEYS = List.of("cloud.machine", VaultParts.ID, VaultParts.KEY, VaultParts.PRIVATE, VaultParts.DECLINED,
        "cloud.base.settings", "cloud.base.ai", "cloud.base.secrets", "cloud.base.pinned", "cloud.media", "cloud.settings", "cloud.lastVerify",
        "server.telegram.apiId", "server.telegram.apiHash", "gif.giphy.key", "cloud.dropboxAppKey", "cloud.pcloudClientId", "stt.command",
        "ui.theme", VaultPeers.KEY, VaultMove.MOVED_AT, VaultMove.QUIET_SINCE, VaultMove.STRAYS_SEEN);

    final Map<String, String> values = new HashMap<>();
    final Path data;
    final Cloud cloud;
    final AtomicInteger pinnedAdopted = new AtomicInteger();

    VaultComputer(FakeDav account, Path dir, String machine) throws Exception {
        values.put("cloud.machine", machine);
        data = Files.createDirectories(dir.resolve(machine));
        Path photos = Files.createDirectories(data.resolve("Ana"));
        Files.writeString(photos.resolve("a.jpg"), "foto");
        CloudAccount a = new CloudAccount(CloudAccount.Service.OTHER, account.url(), "u", "p");
        in(); // its machine id, which the texts are made with
        cloud = Cloud.create(() -> a, data, () -> null, b -> true, () -> { }, () -> List.of(photos),
            new Cloud.Texts(data.resolve("texts"), p -> false, s -> { }),
            new Cloud.Personal(this::pins, theirs -> {
                pinnedAdopted.incrementAndGet();
                try {
                    Files.write(data.resolve("pinned-messages.json"), theirs);
                } catch (java.io.IOException e) {
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
        } catch (java.io.IOException e) {
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

    VaultComputer write(String file, String text) throws Exception {
        Path p = data.resolve(file);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        return this;
    }

    String read(String file) throws Exception {
        Path p = data.resolve(file);
        return Files.exists(p) ? Files.readString(p) : null;
    }

    /** What {@code call} gives, run as this computer: its settings in while it runs. */
    <T> T as(Callable<Future<T>> call) throws Exception {
        in();
        try {
            return call.call().get();
        } finally {
            out();
        }
    }

    Cloud.Status round() throws Exception {
        return as(cloud::syncNow);
    }

    void textsNow() throws Exception {
        as(cloud::textsNow);
    }

    /** Opened here: this computer's key and the vault's id kept, as opening it with a way in leaves them. */
    VaultComputer opened(Vault.Info vault, Vault.Computer computer) {
        set(VaultParts.ID, vault.id());
        set(VaultParts.KEY, Base64.getEncoder().encodeToString(computer.key()));
        return set(VaultParts.PRIVATE, Base64.getEncoder().encodeToString(computer.pair().getPrivate().getEncoded()));
    }
}

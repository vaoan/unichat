package app.unichat.cloud;

import app.unichat.settings.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Whether the vault is open on this computer this round, and what only the vault carries: the person's secrets
 * ({@link Settings#vaultSecrets}), the pinned messages and the transcripts of voice notes. Their paths are logical ones,
 * which {@link SealedStorage} keeps inside the vault; they never go up plain.
 */
final class VaultParts {
    /** This computer's hold on the vault (machine-only, as every {@code cloud.} key): its id, its slot's key, its X25519 key. */
    static final String ID = "cloud.vault.id", KEY = "cloud.vault.key", PRIVATE = "cloud.vault.private";
    /** "Seguir sin la bóveda en este equipo": never asked again, nothing personal sent, everything entered by hand. */
    static final String DECLINED = "cloud.vault.declined";
    static final String SECRETS_FILE = CloudAccount.ROOT + "/Ajustes/unichat-secrets.json",
        PINNED_FILE = CloudAccount.ROOT + "/Ajustes/unichat-pinned.json",
        TRANSCRIPTS_FILE = CloudAccount.ROOT + "/Ajustes/unichat-transcripts.json";
    private static final String PINNED = "pinned-messages.json";
    /** A transcript's file as {@code stt.Transcripts} names it (the audio's SHA-256): no other name from the account becomes a path. */
    private static final Pattern TRANSCRIPT = Pattern.compile("[0-9a-f]{64}\\.txt");

    private final List<SyncedPart> parts;
    private final MergedPart transcripts;

    /**
     * @param pinned {@code pinned-messages.json}
     * @param pinnedAdopted after another computer's pins were written there: whatever holds them reads them again
     * @param transcripts the transcripts' folder
     */
    VaultParts(Path pinned, Runnable pinnedAdopted, Path transcripts) {
        SyncedPart secrets = new SyncedPart(SECRETS_FILE, "secrets",
            now -> Bundle.of(Cloud.machine(), now, Settings.get().vaultSecrets(), null),
            theirs -> Settings.get().adoptVaultSecrets(theirs.settings()));
        SyncedPart pins = new SyncedPart(PINNED_FILE, "pinned",
            now -> Bundle.of(Cloud.machine(), now, Map.of(PINNED, Cloud.read(pinned)), null),
            theirs -> {
                Cloud.write(pinned, theirs.settings().getOrDefault(PINNED, ""));
                pinnedAdopted.run();
            });
        this.parts = List.of(secrets, pins);
        this.transcripts = new MergedPart(TRANSCRIPTS_FILE, () -> transcriptsIn(transcripts), theirs -> theirs.forEach((name, text) -> {
            if (TRANSCRIPT.matcher(name).matches() && !Files.exists(transcripts.resolve(name))) Cloud.write(transcripts.resolve(name), text);
        }));
    }

    /** One round of the vault's own parts, over the vault. */
    void sync(Storage sealed, Instant now) throws IOException {
        for (SyncedPart p : parts) p.sync(sealed, now);
        transcripts.sync(sealed);
    }

    void unseen() {
        parts.forEach(SyncedPart::unseen);
        transcripts.unseen();
    }

    private static Map<String, String> transcriptsIn(Path dir) {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> TRANSCRIPT.matcher(p.getFileName().toString()).matches())
                .forEach(p -> out.put(p.getFileName().toString(), Cloud.read(p)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /**
     * This round's hold on the vault: its state, and the vault's key while {@link Cloud.VaultState#OPEN}, in memory only
     * until {@link #close} clears it.
     */
    static final class Round implements AutoCloseable {
        private final Cloud.VaultState state;
        private final byte[] vk;

        private Round(Cloud.VaultState state, byte[] vk) {
            this.state = state;
            this.vk = vk;
        }

        Cloud.VaultState state() { return state; }

        boolean open() { return state == Cloud.VaultState.OPEN; }

        /** Where the personal parts go this round: the account itself without a vault, the vault when open, nowhere otherwise. */
        Storage personal(Storage plain) {
            return switch (state) {
                case NONE -> plain;
                case OPEN -> new SealedStorage(plain, () -> vk.clone());
                case LOCKED, DECLINED, REMOVED -> null;
            };
        }

        @Override
        public void close() {
            if (vk != null) Arrays.fill(vk, (byte) 0);
        }

        @Override
        public String toString() {
            return "VaultParts.Round[" + state + "]";
        }
    }

    /**
     * The vault's state here, read from the account: none there (and one this computer held is gone, so its hold here is
     * forgotten); there but not this computer's to open (another vault, no key here, or declined); this computer's slot
     * gone or not opening with its key (removed: nothing is sent, nothing deleted); or open.
     */
    static Round open(Storage plain) throws IOException {
        Settings s = Settings.get();
        Vault.Info info = Vault.info(plain);
        String held = s.getString(ID, null);
        if (info == null) {
            // A vault file that doesn't read isn't a vault gone: the key here is never forgotten over it.
            if (held != null && plain.exists(Vault.INFO)) return new Round(Cloud.VaultState.LOCKED, null);
            if (held != null) {
                for (String k : new String[] {ID, KEY, PRIVATE}) s.set(k, null);
            }
            return new Round(Cloud.VaultState.NONE, null);
        }
        String key = s.getString(KEY, null);
        if (key == null || !info.id().equals(held)) {
            return new Round(s.getBool(DECLINED, false) ? Cloud.VaultState.DECLINED : Cloud.VaultState.LOCKED, null);
        }
        VaultSlot slot = Vault.slot(plain, info, Cloud.machine());
        if (slot == null || slot.method() != VaultSlot.Method.COMPUTER) return new Round(Cloud.VaultState.REMOVED, null);
        byte[] slotKey;
        try {
            slotKey = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException e) {
            return new Round(Cloud.VaultState.REMOVED, null);
        }
        try {
            return new Round(Cloud.VaultState.OPEN, slot.unwrap(slotKey));
        } catch (VaultCrypto.Broken e) {
            return new Round(Cloud.VaultState.REMOVED, null);
        } finally {
            Arrays.fill(slotKey, (byte) 0);
        }
    }
}

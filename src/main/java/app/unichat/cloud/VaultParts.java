package app.unichat.cloud;

import app.unichat.settings.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
     * @param pins the pinned messages as {@code pinned-messages.json} holds them
     * @param adoptPins takes another computer's in their place, at once
     * @param transcripts the transcripts' folder
     */
    VaultParts(Supplier<byte[]> pins, Consumer<byte[]> adoptPins, Path transcripts) {
        // Merged: a key only this computer had stays, and goes up next round.
        SyncedPart secrets = SyncedPart.merged(SECRETS_FILE, "secrets",
            now -> Bundle.of(Cloud.machine(), now, Settings.get().vaultSecrets(), null),
            theirs -> Settings.get().adoptVaultSecrets(theirs.settings()));
        SyncedPart pinned = new SyncedPart(PINNED_FILE, "pinned",
            now -> Bundle.of(Cloud.machine(), now, Map.of(PINNED, new String(pins.get(), StandardCharsets.UTF_8)), null),
            theirs -> adoptPins.accept(theirs.settings().getOrDefault(PINNED, "").getBytes(StandardCharsets.UTF_8)));
        this.parts = List.of(secrets, pinned);
        this.transcripts = new MergedPart(TRANSCRIPTS_FILE, () -> transcriptsIn(transcripts), name -> Cloud.read(transcripts.resolve(name)),
            theirs -> theirs.forEach((name, text) -> {
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

    /** The transcripts' names here, each one of ours. */
    private static Set<String> transcriptsIn(Path dir) {
        if (!Files.isDirectory(dir)) return Set.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> TRANSCRIPT.matcher(n).matches()).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * This round's hold on the vault: its state, and the vault's key while {@link Cloud.VaultState#OPEN}, in memory only
     * until {@link #close} clears it.
     */
    static final class Round implements AutoCloseable {
        private final Cloud.VaultState state;
        private final byte[] vk;
        private final String vault;

        private Round(Cloud.VaultState state, byte[] vk) {
            this(state, vk, null);
        }

        private Round(Cloud.VaultState state, byte[] vk, String vault) {
            this.state = state;
            this.vk = vk;
            this.vault = vault;
        }

        Cloud.VaultState state() { return state; }

        boolean open() { return state == Cloud.VaultState.OPEN; }

        /** A copy of the vault's key while open, for its holder to clear. */
        byte[] key() {
            if (vk == null) throw new IllegalStateException("The vault isn't open");
            return vk.clone();
        }

        /** Where the texts go this round, for {@link TextSync#run(Storage, String)}: this vault, or the plain account. */
        String textsTo() { return open() ? "vault:" + vault : TextSync.PLAIN; }

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

    /** A vault there and not this computer's to open: locked, or declined if this computer chose to go on without it. */
    private static Round closed(Settings s) {
        return new Round(s.getBool(DECLINED, false) ? Cloud.VaultState.DECLINED : Cloud.VaultState.LOCKED, null);
    }

    /**
     * The vault's state here, read from the account: none there (and one this computer held is gone, so its hold here is
     * forgotten); there but not this computer's to open (a vault file that doesn't read, another vault, no key here, or
     * declined); this computer's slot gone or not opening with its key (removed: nothing is sent, nothing deleted); or open.
     */
    static Round open(Storage plain) throws IOException {
        Settings s = Settings.get();
        byte[] file = plain.get(Vault.INFO);
        Vault.Info info = Vault.parse(file);
        String held = s.getString(ID, null);
        // A vault file that doesn't read (damaged, half written, another's) is no sign of no vault: nothing goes up plain,
        // and a key held here isn't forgotten over it.
        if (info == null && file != null) return closed(s);
        if (info == null) {
            if (held != null) forget();
            return new Round(Cloud.VaultState.NONE, null);
        }
        if (!info.id().equals(held)) VaultMove.forget(); // another vault than the one moved into: it's moved into afresh
        String key = s.getString(KEY, null);
        if (key == null || !info.id().equals(held)) return closed(s);
        VaultSlot slot = Vault.slot(plain, info, Cloud.machine());
        if (slot == null || slot.method() != VaultSlot.Method.COMPUTER) return new Round(Cloud.VaultState.REMOVED, null);
        byte[] slotKey;
        try {
            slotKey = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException e) {
            return new Round(Cloud.VaultState.REMOVED, null);
        }
        try {
            if (info.generation() > slot.generation()) return taken(plain, info, slot, slotKey, s);
            return new Round(Cloud.VaultState.OPEN, slot.unwrap(slotKey), info.id());
        } catch (VaultCrypto.Broken e) {
            return new Round(Cloud.VaultState.REMOVED, null);
        } finally {
            Arrays.fill(slotKey, (byte) 0);
        }
    }

    /** This computer's hold on the vault forgotten here: the vault itself and the other computers left as they are. */
    static void forget() {
        Settings s = Settings.get();
        for (String k : new String[] {ID, KEY, PRIVATE}) s.set(k, null);
        VaultMove.forget();
    }

    /**
     * The vault's key changed since this computer's slot was written: the new one offered to it, opened with its private
     * key; what it sent with the old one meanwhile sealed again (its slot still opens the old key); its slot then taken to
     * the new key. Until that last write, the next round does it all again. No offer for it: it was removed first.
     */
    private static Round taken(Storage plain, Vault.Info info, VaultSlot slot, byte[] slotKey, Settings s) throws IOException {
        byte[] privateKey;
        try {
            privateKey = Base64.getDecoder().decode(s.getString(PRIVATE, ""));
        } catch (IllegalArgumentException e) {
            return new Round(Cloud.VaultState.REMOVED, null);
        }
        byte[] vk;
        try {
            vk = VaultNext.take(slot, privateKey, info.generation());
        } catch (VaultCrypto.Broken e) {
            return new Round(Cloud.VaultState.REMOVED, null);
        } finally {
            Arrays.fill(privateKey, (byte) 0);
        }
        byte[] old = null;
        try {
            try {
                old = slot.unwrap(slotKey);
            } catch (VaultCrypto.Broken e) {
                // Nothing of the old key to seal again.
            }
            if (old != null) VaultReseal.run(plain, old, vk);
            Vault.rewrap(plain, info, slot, slotKey, vk);
            return new Round(Cloud.VaultState.OPEN, vk, info.id());
        } catch (IOException | RuntimeException e) {
            Arrays.fill(vk, (byte) 0);
            throw e;
        } finally {
            if (old != null) Arrays.fill(old, (byte) 0);
        }
    }
}

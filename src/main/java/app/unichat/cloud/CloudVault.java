package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import app.unichat.support.I18n;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * What Ajustes → Nube → Bóveda does with the vault, through {@link Cloud}: each job on its worker, never alongside a round
 * or the texts alone; the page never sees a key, and a secret given is cleared once used.
 */
public final class CloudVault {
    private final Cloud cloud;

    CloudVault(Cloud cloud) {
        this.cloud = cloud;
    }

    /**
     * A new key for the vault ({@link Vault#rotate}), on the cloud's thread: only with the vault open here. This computer
     * then takes it at once, as the others do on their next round; if that fails, its next round does it.
     */
    CompletableFuture<Cloud.KeyChanged> rotateKey() {
        return cloud.onWorker(dav -> rotate(dav, id -> true));
    }

    /**
     * The same, the new key offered only to the computers in {@code confirmed} (those the person saw listed and said yes
     * to) and this one; any other, one that came after the list included, gets nothing and is named in {@link Cloud.KeyChanged#refused}.
     */
    public CompletableFuture<Cloud.KeyChanged> rotateKey(java.util.Set<String> confirmed) {
        java.util.Set<String> asked = java.util.Set.copyOf(confirmed);
        return cloud.onWorker(dav -> rotate(dav, asked::contains));
    }

    /**
     * Another computer taken out of the vault and the key changed, in one: a removed computer still holds the key, so
     * only a new one shuts it out of anything new. Kept out first, so it's never offered the new key.
     */
    public CompletableFuture<Cloud.KeyChanged> removeAndChangeKey(String machine, java.util.Set<String> confirmed) {
        java.util.Set<String> asked = java.util.Set.copyOf(confirmed);
        return cloud.onWorker(dav -> {
            Vault.Info before = Vault.info(dav);
            VaultActions.remove(dav, machine);
            try {
                return rotate(dav, id -> !id.equals(machine) && asked.contains(id));
            } catch (IOException | RuntimeException e) {
                // Removed all the same: the person must know whether the key it holds still opens the vault, and never
                // read "kept" when the account can't say it is (the change may have gone through, its answer lost).
                String key = before != null && Vault.at(dav, before) ? "cloud.error.vaultRemovedKeyKept" : "cloud.error.vaultRemovedKeyUnknown";
                throw new IOException(I18n.t(key, Errors.message(e)), e);
            }
        });
    }

    private Cloud.KeyChanged rotate(Storage dav, java.util.function.Predicate<String> confirmed) throws IOException {
        Vault.Rotated r = withKey(dav, (vault, old) -> {
            VaultPeers peers = VaultPeers.load(vault.info().id());
            try {
                // Before the generation rises: an answer lost after it never hides that a new code is to be kept.
                return Vault.rotate(dav, old, Cloud.machine(), peers, confirmed,
                    () -> Settings.get().set(VaultActions.UNCONFIRMED, vault.info().id()));
            } finally {
                peers.save(); // a computer seen for the first time is pinned, the change done or not
            }
        });
        Arrays.fill(r.vk(), (byte) 0);
        try {
            cloud.openVault(dav).close();
        } catch (IOException | RuntimeException e) {
            System.err.println("Cloud: the vault's new key: " + Errors.message(e)); // the next round takes it
        }
        return new Cloud.KeyChanged(r.code().shown(), r.dropped().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()), r.left(), r.refused(), r.resealFailed());
    }

    /**
     * The person says {@code machine}'s key, as its slot shows it now, is that computer's (it was set up again): pinned in
     * place of what this computer saw, so the next change of key offers it one. Only with the vault open here, and only a
     * key the vault key sealed.
     */
    public CompletableFuture<Void> acceptKey(String machine) {
        return cloud.onWorker(dav -> {
            if (!Ids.ours(machine)) throw new IllegalArgumentException("Not a machine id");
            return withKey(dav, (vault, vk) -> {
                VaultSlot slot = Vault.slot(dav, vault.info(), machine);
                byte[] key = slot == null || slot.method() != VaultSlot.Method.COMPUTER ? null : VaultNext.publicKey(slot, vk);
                if (key == null) throw new IOException(I18n.t("cloud.error.vaultNoSuchKey"));
                VaultPeers peers = VaultPeers.load(vault.info().id());
                peers.accept(machine, key);
                peers.save();
                return null;
            });
        });
    }

    /** Another computer's way into the vault taken away, on the cloud's thread; this one's own goes by {@link #forgetHere}. */
    public CompletableFuture<Void> removeComputer(String machine) {
        return cloud.onWorker(dav -> {
            VaultActions.remove(dav, machine);
            return null;
        });
    }

    /**
     * "Volver a sincronizar sin cifrar": the vault this computer holds is gone from the account with nothing saying it was
     * turned off ({@link Cloud.VaultState#MISSING}), and the person says it was: its hold here forgotten, so the next round
     * is today's plain one. Only in that state: a vault there again is opened or locked as any other.
     */
    public CompletableFuture<Void> leaveMissingVault() {
        return cloud.onWorker(dav -> {
            try (VaultParts.Round vault = cloud.stateOf(dav)) {
                if (vault.state() != Cloud.VaultState.MISSING) throw new IOException(I18n.t("cloud.error.vaultStillThere"));
            }
            VaultParts.gone();
            return null;
        });
    }

    /**
     * "Borrar esta bóveda y empezar de nuevo": a vault this computer holds no key for ({@link Cloud.VaultState#LOCKED})
     * deleted whole, and this computer's hold and "declined" forgotten, so it starts again without one. Nothing in it can
     * be opened again; no mark says it was turned off (that needs its key), so a computer that held it is asked, never
     * sent plain by itself ({@link Cloud.VaultState#MISSING}). Its file alone goes when its folder won't.
     */
    public CompletableFuture<Void> deleteLockedVault() {
        return cloud.onWorker(dav -> {
            try (VaultParts.Round vault = cloud.stateOf(dav)) {
                if (vault.state() != Cloud.VaultState.LOCKED) throw new IOException(I18n.t("cloud.error.vaultNotLocked"));
            }
            try {
                dav.delete(Vault.ROOT);
            } catch (IOException e) {
                dav.delete(Vault.INFO); // no computer finds a vault; the rest is cleared by the next one
            }
            VaultParts.gone();
            return null;
        });
    }

    /**
     * The vault forgotten on this computer, on the cloud's thread: its slot deleted, then its hold on the vault here (whether
     * it declined is left as it is). The next round finds it locked; it can be opened again later.
     */
    public CompletableFuture<Void> forgetHere() {
        return cloud.onWorker(dav -> {
            Vault.removeSlot(dav, Cloud.machine());
            VaultParts.forget();
            return null;
        });
    }

    /**
     * The vault turned off, on the cloud's thread, only with it open here: everything with a plain place back there, read
     * back, and only then the vault deleted ({@link VaultOut}); then this computer's hold on it forgotten, its texts
     * counted as sent plain (they were just copied). A failure before the delete leaves the vault as it was; a folder that
     * won't go once its file did is told apart ({@code vaultLeftovers}: off all the same, cleared by the next vault). The other
     * computers find it gone on their next round and go back to plain by themselves.
     */
    public CompletableFuture<Void> turnOffVault() {
        return cloud.onWorker(dav -> withKey(dav, (vault, vk) -> {
            synchronized (cloud.texts()) { // TextSync.run's own lock: no text of this computer's goes up halfway through
                boolean whole = VaultOut.run(dav, vault.personal(dav), vault.info(), vk);
                VaultParts.gone();
                cloud.texts().sentAt(TextSync.PLAIN);
                if (!whole) throw new IOException(I18n.t("cloud.error.vaultLeftovers"));
            }
            return null;
        }));
    }

    /** What Ajustes → Nube → Bóveda shows: the vault as it stands here, its ways in and, open, its computers. */
    public CompletableFuture<VaultView> vaultView() {
        return cloud.onWorker(dav -> {
            try (VaultParts.Round vault = cloud.stateOf(dav)) {
                return VaultActions.view(dav, vault);
            }
        });
    }

    /** A new vault, open here; its recovery code as printed, shown once and never kept. */
    public CompletableFuture<String> createVault() {
        return cloud.onWorker(VaultActions::create);
    }

    /**
     * The vault opened here with a way in ({@code secret}: the recovery code as typed, or the password). A
     * secret that opens nothing fails with "wrong" and writes nothing. {@code secret} is cleared once used.
     */
    public CompletableFuture<Void> openVault(VaultView.Way way, char[] secret) {
        CompletableFuture<Void> f = cloud.onWorker(dav -> {
            VaultActions.open(dav, way, secret);
            return null;
        });
        f.whenComplete((r, e) -> Arrays.fill(secret, '\0'));
        return f;
    }

    /** "Seguir sin la bóveda en este equipo" ({@code true}), or taken back. */
    public CompletableFuture<Void> declineVault(boolean declined) {
        return cloud.onWorker(dav -> {
            VaultActions.decline(declined);
            return null;
        });
    }

    /** A password as one more way in, only with the vault open here; {@code password} is cleared once used. */
    public CompletableFuture<Void> addPassword(char[] password) {
        CompletableFuture<Void> f = cloud.onWorker(dav -> withKey(dav, (vault, vk) -> {
            VaultActions.addPassword(dav, vk, password);
            return null;
        }));
        f.whenComplete((r, e) -> Arrays.fill(password, '\0'));
        return f;
    }

    /** A new recovery code in place of the old one (which no longer opens it), only with the vault open here. */
    public CompletableFuture<String> newRecoveryCode() {
        return cloud.onWorker(dav -> withKey(dav, (vault, vk) -> VaultActions.newRecovery(dav, vk, vault.info())));
    }

    /** The person typed back the groups asked of the latest recovery code: kept. */
    public CompletableFuture<Void> codeKept() {
        return cloud.onWorker(dav -> {
            VaultActions.codeKept();
            return null;
        });
    }

    private interface KeyJob<T> {
        T run(VaultParts.Round vault, byte[] vk) throws IOException;
    }

    /** {@code job} with the vault open here and a copy of its key, cleared after; not open → "the vault isn't open". */
    private <T> T withKey(Storage dav, KeyJob<T> job) throws IOException {
        try (VaultParts.Round vault = cloud.openVault(dav)) {
            if (!vault.open()) throw new IOException(I18n.t("cloud.error.vaultNotOpen"));
            byte[] vk = vault.key();
            try {
                return job.run(vault, vk);
            } finally {
                Arrays.fill(vk, (byte) 0);
            }
        }
    }

}

package app.unichat.cloud;

import app.unichat.support.Errors;
import app.unichat.support.I18n;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What a round of {@link Cloud} does about the account's old names: the plain folders first ({@link CloudNames}), then the
 * vault where it's open here ({@link VaultNames}), and which machines still write the old names.
 */
final class CloudRenames {
    /** Every part reading the account's file afresh. */
    interface Unseen {
        void run() throws IOException;
    }

    /** The vault's state here, read through the storage given ({@link Cloud#openVault}). */
    interface Opener {
        VaultParts.Round open(Storage at) throws IOException;
    }

    private final TextSync texts;
    private final ReentrantLock keyLock;
    private final Unseen unseen;
    private final Opener opener;
    /** Whether the last round was this computer's first move (no older Unichat known to write the old names since). */
    private volatile boolean firstMove;
    /** The machines the last round found still writing the account's old names. */
    private volatile Set<String> older = Set.of();

    CloudRenames(TextSync texts, ReentrantLock keyLock, Unseen unseen, Opener opener) {
        this.texts = texts;
        this.keyLock = keyLock;
        this.unseen = unseen;
        this.opener = opener;
    }

    Set<String> older() {
        return older;
    }

    /**
     * The account's plain old names moved to the new ones, with the texts' lock held over the texts; why it couldn't (the
     * round goes on: what's left is moved by the next), or null.
     */
    String plain(Storage dav, Instant now) {
        try {
            firstMove = CloudNames.firstMove();
            older = CloudNames.migrate(dav, Cloud.machine(), texts, now);
            return null;
        } catch (IOException | RuntimeException e) {
            return Errors.message(e);
        }
    }

    /**
     * Media's old folder moved before media reads it, its duplicates deleted only on this computer's first move (after it,
     * an older Unichat's daily check would only send them again); whether the round's check may run (nothing left under
     * the old name that it would send again).
     */
    boolean media(Storage dav) {
        return CloudNames.media(dav, firstMove && older.isEmpty());
    }

    /**
     * The vault open this round moved to its new names, nothing of this computer writing it meanwhile; the account as
     * the round is to see the vault after. Cut short, {@code failed} is told why, and the account is read again: the
     * old names while they're still the vault's, the new ones while they hold this round's key, else null (switched with
     * another key meanwhile: the round's personal parts wait for the next).
     */
    Storage vault(Storage dav, Storage at, VaultParts.Round vault, java.util.function.Consumer<String> failed) {
        try {
            return move(dav, at, vault);
        } catch (IOException | RuntimeException e) {
            failed.accept(Errors.message(e));
            try {
                Storage now = VaultNames.at(dav);
                return now instanceof OldVault || Vault.at(now, vault.info()) ? now : null;
            } catch (IOException again) {
                return null;
            }
        }
    }

    private Storage move(Storage dav, Storage at, VaultParts.Round vault) throws IOException {
        byte[] vk = vault.key();
        keyLock.lock();
        try {
            synchronized (texts) {
                Storage now = VaultNames.migrate(dav, at, vault.info(), vk);
                if (now != at) unseen.run(); // another file now, whatever its stamp
                return now;
            }
        } finally {
            keyLock.unlock();
            Arrays.fill(vk, (byte) 0);
        }
    }

    /**
     * The account as a vault job is to see it, held under the job's locks: a vault still under its old names and open here
     * is moved first, so the job never writes the old names while another computer switches them; one that can't be moved
     * now refuses the job (the next round tries again). Not open here (a way in about to open it), the old names'.
     */
    Storage forJob(Storage dav) throws IOException {
        Storage at = VaultNames.at(dav);
        if (!(at instanceof OldVault)) return at;
        boolean open;
        try (VaultParts.Round vault = opener.open(at)) {
            open = vault.open();
            if (open) {
                Storage now = vault(dav, at, vault, why -> { });
                if (now != null && !(now instanceof OldVault)) return now;
            }
        }
        Storage again = VaultNames.at(dav); // another computer may have switched it meanwhile
        if (!(again instanceof OldVault)) return again;
        if (open) throw new IOException(I18n.t("cloud.error.vaultRenameFirst"));
        return at;
    }

    /**
     * After a job that ran under the old names (a way in opened the vault here): a slot it wrote there after another
     * computer switched them is taken to the new ones, and the vault, open here now, moved. Best effort: a round does it too.
     */
    void afterJob(Storage dav, Storage at) {
        if (!(at instanceof OldVault)) return;
        try {
            VaultNames.slotsLeft(dav);
            Storage now = VaultNames.at(dav);
            try (VaultParts.Round vault = opener.open(now)) {
                if (vault.open() && (now != dav || VaultNames.left(dav))) vault(dav, now, vault, why -> { });
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Cloud: the vault's new names after a job: " + Errors.message(e)); // the next round does it
        }
    }
}

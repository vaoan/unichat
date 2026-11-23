package app.unichat.cloud;

import app.unichat.support.Errors;
import app.unichat.support.I18n;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What a round of {@link Cloud} does about the account's old names: the plain folders first ({@link CloudNames}), then the
 * vault where it's open here ({@link VaultNames}), and which machines still write the old names.
 */
final class CloudRenames {
    /** Every part reading the account's file afresh. */
    @FunctionalInterface
    interface Unseen {
        void run() throws IOException;
    }

    /** The vault's state here, read through the storage given ({@link Cloud#openVault}). */
    @FunctionalInterface
    interface Opener {
        VaultParts.Round open(Storage at) throws IOException;
    }

    private final TextSync texts;
    private final ReentrantLock keyLock;
    private final Unseen unseen;
    private final Opener opener;
    /** Whether the last round's move went through, so no media file is missing under the new name: its check may run. */
    private volatile boolean mayCheck = true;
    /** Whether the last round's plain move failed with a settings part still under its old name: the plain parts wait. */
    private volatile boolean settingsWait;

    CloudRenames(TextSync texts, ReentrantLock keyLock, Unseen unseen, Opener opener) {
        this.texts = texts;
        this.keyLock = keyLock;
        this.unseen = unseen;
        this.opener = opener;
    }

    /** Whether the round's media check may run: no media file missing under the new name, which it would send again. */
    boolean mayCheck() {
        return mayCheck;
    }

    /**
     * The account's plain old folders moved to the new names and deleted once confirmed, with the texts' lock held over
     * the texts; why it couldn't (the round goes on: the next one goes on from there), or null.
     */
    String plain(Storage dav) {
        try {
            CloudNames.migrate(dav, texts);
            mayCheck = true; // nothing missing under media's new name: what's kept under the old one differs
            settingsWait = false;
            return null;
        } catch (IOException | RuntimeException e) {
            mayCheck = false;
            settingsWait = CloudNames.settingsLeft(dav);
            return Errors.message(e);
        }
    }

    /**
     * Whether the plain settings and the AI's part wait this round: the move failed with one still under its old name,
     * where its own copy would go up over it and win (newer).
     */
    boolean settingsWait() {
        return settingsWait;
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
     * The account as a job that writes the vault's keys or files is to see it, held under the job's locks. Anything of the
     * vault still under its old names is moved first where it's open here; once the names are switched, only the slots
     * left are finished (they need no key; the files are the rounds' to finish). So the job never writes the old names while another computer switches them, and no old slot comes back
     * later over a way in it changes or takes away. One that can't be finished now refuses the job (the next round tries
     * again). Still under the old names and not open here (a way in about to open it), the old names'.
     */
    Storage forJob(Storage dav) throws IOException {
        Storage at = VaultNames.at(dav);
        if (!(at instanceof OldVault)) {
            if (VaultNames.left(dav)) slots(dav); // switched already: only the slots matter to a job; the files are the rounds'
            return at;
        }
        boolean open;
        try (VaultParts.Round vault = opener.open(at)) {
            open = vault.open();
            if (open) {
                try {
                    Storage now = move(dav, at, vault);
                    if (!(now instanceof OldVault)) return now;
                } catch (IOException | RuntimeException e) {
                    throw new IOException(I18n.t("cloud.error.vaultRenameFirst"), e);
                }
            }
        }
        if (open) throw new IOException(I18n.t("cloud.error.vaultRenameFirst"));
        Storage again = VaultNames.at(dav); // another computer may have switched it meanwhile
        if (again instanceof OldVault) return at;
        slots(dav);
        return again;
    }

    /** The slots left under the old names taken across, or the job refused: none may come back over what it writes. */
    private static void slots(Storage dav) throws IOException {
        try {
            VaultNames.slotsLeft(dav);
        } catch (IOException | RuntimeException e) {
            throw new IOException(I18n.t("cloud.error.vaultRenameFirst"), e);
        }
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

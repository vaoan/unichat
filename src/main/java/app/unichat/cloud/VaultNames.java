package app.unichat.cloud;

import app.unichat.support.I18n;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The vault moving from its old names ({@link CloudNames#OLD_VAULT}: "Bóveda", "llaves", "boveda.json", and inside it the
 * old "Ajustes", "Textos", "Registro") to the new ones, by a computer where it's open (only the vault's key can seal a
 * file again: each file's logical path is its associated data). Every step leaves a vault that opens: until
 * {@link Vault#INFO} is written ({@code names: 2}) it's read under the old names ({@link OldVault}), after it under the
 * new ones, and what's left under the old ones is finished by the next round. Run in the round's vault part, under
 * {@code vaultPart}, the key's lock and TextSync's, so nothing of this computer writes the vault meanwhile.
 * <ol>
 * <li>Every sealed file copied to its new place, sealed again under its new path (where both are there, the newer is
 * kept: {@link CloudNames#winner}); nothing old is deleted yet.</li>
 * <li>Every slot copied as it is (a slot is sealed with its vault, method and id, never its path).</li>
 * <li>{@link Vault#INFO} written, only if no other computer wrote it first, and read back; then the old one deleted, so
 * the old names never open again.</li>
 * <li>The slots again (where the new names have none, or one was written after it was copied), then each old file
 * deleted once the new place, read again, holds it; the old folder once empty.</li>
 * </ol>
 * Copies rather than moves before step 3: both names stay whole until the switch, so a computer reading the old names
 * meanwhile (another one's round) finds every slot and file where it was.
 */
final class VaultNames {
    /**
     * Tests: each runs once, on the migrating thread holding what it holds: right before {@link Vault#INFO} is written
     * (every file and slot copied), and right after the old one is deleted (before the old files go).
     */
    static volatile Runnable beforeSwitch, afterSwitch;

    private VaultNames() {}

    private static void hook(boolean before) {
        Runnable r = before ? beforeSwitch : afterSwitch;
        if (before) beforeSwitch = null;
        else afterSwitch = null;
        if (r != null) r.run();
    }

    /** The account as the vault's code is to see it now: under the new names once they're the vault's, else the old ones'. */
    static Storage at(Storage dav) throws IOException {
        if (dav.exists(Vault.INFO) || !dav.exists(CloudNames.OLD_INFO)) return dav;
        return new OldVault(dav);
    }

    /** Whether anything of the vault is still under its old names. */
    static boolean left(Storage dav) throws IOException {
        return dav.exists(CloudNames.OLD_VAULT);
    }

    /**
     * The vault {@code info}, open here with {@code vk} and read through {@code at}, moved to its new names as far as it
     * goes this round; the account as the vault's code is to see it after ({@code dav} once switched, else {@code at}).
     * A file that doesn't open with {@code vk} is left under its old name (said in the log); any failure before the
     * switch leaves the old names the vault's, to try again next round. The vault changed meanwhile (another computer
     * switched it, or changed its key) stops it: the next round goes on from what the account says then.
     */
    static Storage migrate(Storage dav, Storage at, Vault.Info info, byte[] vk) throws IOException {
        Map<String, String> slotsCopied = new HashMap<>();
        if (at instanceof OldVault) {
            copy(dav, vk, true);
            slots(dav, false, slotsCopied);
            hook(true);
            if (!Vault.at(at, info)) throw changed(); // its key changed meanwhile
            if (!dav.exists(Vault.INFO)) dav.put(Vault.INFO, Vault.json(info));
            Vault.Info now = Vault.info(dav);
            if (now == null || !now.id().equals(info.id()) || now.generation() != info.generation()) throw changed();
            dav.delete(CloudNames.OLD_INFO); // from here on, only the new names open
            hook(false);
        }
        if (!left(dav)) return dav;
        slots(dav, true, slotsCopied); // first: a way in never waits behind a file that won't go
        copy(dav, vk, false);
        deleteEmpty(dav, CloudNames.OLD_VAULT);
        return dav;
    }

    private static IOException changed() {
        return new IOException(I18n.t("cloud.error.vaultChanged"));
    }

    /**
     * Every sealed file under the old names into its new place. {@code before} (the old names still the vault's): each
     * copied; after: each old one deleted once its new place holds it, read again now (another computer may have written
     * it since, older or newer), and only if the old one wasn't written again meanwhile.
     */
    private static void copy(Storage dav, byte[] vk, boolean before) throws IOException {
        IOException[] failed = {null};
        Set<String> made = new HashSet<>();
        VaultReseal.walk(dav, CloudNames.OLD_VAULT, Set.of(CloudNames.OLD_SLOTS, CloudNames.OLD_INFO), stored -> {
            String stamp = dav.stamp(stored);
            if (stamp == null) return; // gone meanwhile
            boolean held = file(dav, vk, stored, before, made); // false: it doesn't open, and stays where it is
            if (!before && held && stamp.equals(dav.stamp(stored))) dav.delete(stored);
        }, stored -> {
            if (failed[0] == null) failed[0] = new IOException(I18n.t("cloud.error.vaultRename"));
        });
        if (failed[0] != null) throw failed[0];
    }

    /**
     * One file: opened under its old path, sealed under its new one where its new place has none or loses to it;
     * whether its new place holds it now (false for one that doesn't open with this key: left as it is). Before the
     * switch, written only while the old names are still the vault's (another computer may have switched them).
     */
    private static boolean file(Storage dav, byte[] vk, String stored, boolean before, Set<String> made) throws IOException {
        String old = CloudAccount.ROOT + stored.substring(CloudNames.OLD_VAULT.length()), logical = CloudNames.renamed(old);
        String target = CloudNames.newStored(stored);
        byte[] plain;
        try {
            byte[] sealed = dav.get(stored);
            if (sealed == null) return false;
            plain = VaultCrypto.open(vk, old, sealed);
        } catch (VaultCrypto.Broken e) {
            System.err.println("Cloud: a file of the vault doesn't open with its key: left under its old name");
            return false;
        }
        byte[] now = null;
        byte[] there = dav.get(target);
        if (there != null) {
            try {
                now = VaultCrypto.open(vk, logical, there);
            } catch (VaultCrypto.Broken e) {
                now = null; // damaged where it's going: what opens wins
            }
        }
        byte[] wins = CloudNames.winner(logical, plain, now, before);
        if (wins == null) return true;
        if (before && !dav.exists(CloudNames.OLD_INFO)) throw changed();
        String folder = target.substring(0, target.lastIndexOf('/'));
        if (made.add(folder)) dav.mkdirs(folder);
        dav.put(target, VaultCrypto.seal(vk, logical, wins));
        return true;
    }

    /**
     * Each slot under the old names copied as it is (a slot is sealed with its vault, method and id, never its path),
     * its stamp noted in {@code copied}. {@code finishing}: copied again where the new names have none, or where it was
     * written after it was copied (before the switch: the newer), and the old one deleted after.
     */
    private static void slots(Storage dav, boolean finishing, Map<String, String> copied) throws IOException {
        for (String name : dav.list(CloudNames.OLD_SLOTS)) {
            if (!name.endsWith(".json") || !Ids.ours(name.substring(0, name.length() - ".json".length()))) continue;
            String from = CloudNames.OLD_SLOTS + "/" + name, to = Vault.SLOTS + "/" + name;
            String stamp = dav.stamp(from);
            if (stamp == null) continue;
            boolean again = copied.containsKey(from) && !stamp.equals(copied.get(from));
            if (!finishing || again || !dav.exists(to)) {
                byte[] slot = dav.get(from);
                if (slot == null) continue;
                dav.mkdirs(Vault.SLOTS);
                dav.put(to, slot);
                copied.put(from, stamp);
            }
            if (finishing) dav.delete(from);
        }
        if (finishing) CloudNames.deleteIfEmpty(dav, CloudNames.OLD_SLOTS);
    }

    /** The slots left under the old names once the new ones are the vault's (a way in written there late), taken across. */
    static void slotsLeft(Storage dav) throws IOException {
        if (dav.exists(Vault.INFO) && dav.exists(CloudNames.OLD_SLOTS)) slots(dav, true, new HashMap<>());
    }

    /** {@code dir} and every folder in it deleted when no file is left in any of them. */
    private static boolean deleteEmpty(Storage dav, String dir) throws IOException {
        boolean empty = true;
        for (String name : dav.list(dir)) {
            if (!Storage.plainName(name)) continue;
            empty &= name.indexOf('.') < 0 && deleteEmpty(dav, dir + "/" + name);
        }
        if (empty) dav.delete(dir);
        return empty;
    }
}

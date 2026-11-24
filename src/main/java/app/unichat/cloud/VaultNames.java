package app.unichat.cloud;

import app.unichat.settings.Settings;
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
 * {@link Vault#INFO} is written (its being there is the mark) it's read under the old names ({@link OldVault}), after it under the
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
    static volatile Runnable beforeSwitch; // NOPMD: MutableStaticState, a seam tests swap
    static volatile Runnable afterSwitch; // NOPMD: MutableStaticState, a seam tests swap

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

    /**
     * Machine-only: the files left under the old names because they don't open with the vault's key, each with its stamp
     * then ("path<TAB>stamp" lines): while all of them are as they were, nothing else is left to move.
     */
    static final String LEFT = "cloud.names.vaultLeft";

    /**
     * Whether anything of the vault is still to move from its old names: what's there isn't only the files known not to
     * open ({@link #LEFT}), unchanged (each one's stamp asked, never a walk of the folder).
     */
    static boolean left(Storage dav) throws IOException {
        if (!dav.exists(CloudNames.OLD_VAULT)) return false;
        String known = Settings.get().getString(LEFT, null);
        if (known == null) return true;
        for (String line : known.split("\n")) {
            String[] f = line.split("\t", 2);
            if (f.length < 2 || !f[1].equals(String.valueOf(dav.stamp(f[0])))) return true;
        }
        return false;
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
            copy(dav, vk, true, new HashMap<>());
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
        Map<String, String> unopened = new java.util.TreeMap<>();
        boolean all = copy(dav, vk, false, unopened);
        boolean gone = deleteEmpty(dav, CloudNames.OLD_VAULT);
        if (gone || !all || unopened.isEmpty()) {
            Settings.get().set(LEFT, null);
            return dav;
        }
        StringBuilder known = new StringBuilder();
        unopened.forEach((path, stamp) -> known.append(known.isEmpty() ? "" : "\n").append(path).append('\t').append(stamp));
        if (!known.toString().equals(Settings.get().getString(LEFT, null))) {
            System.err.println("Cloud: " + unopened.size() + " file(s) of the vault don't open with its key: left under the old names");
            Settings.get().set(LEFT, known.toString());
        }
        return dav;
    }

    private static IOException changed() {
        return new IOException(I18n.t("cloud.error.vaultChanged"));
    }

    /**
     * Every sealed file under the old names into its new place. {@code before} (the old names still the vault's): each
     * copied; after: each old one deleted once its new place holds it, read again now (another computer may have written
     * it since, older or newer), and only if the old one wasn't written again meanwhile. Each that doesn't open goes in
     * {@code unopened} with its stamp; whether every other one went.
     */
    private static boolean copy(Storage dav, byte[] vk, boolean before, Map<String, String> unopened) throws IOException {
        IOException[] failed = {null};
        boolean[] all = {true};
        Set<String> made = new HashSet<>();
        VaultReseal.walk(dav, CloudNames.OLD_VAULT, Set.of(CloudNames.OLD_SLOTS, CloudNames.OLD_INFO), stored -> {
            String stamp = dav.stamp(stored);
            if (stamp == null) return; // gone meanwhile
            boolean held = file(dav, vk, stored, before, made); // false: it doesn't open, and stays where it is
            if (!held) unopened.put(stored, stamp);
            else if (!before && stamp.equals(dav.stamp(stored))) dav.delete(stored);
            else if (!before) all[0] = false; // written again meanwhile: the next round takes it
        }, stored -> {
            if (failed[0] == null) failed[0] = new IOException(I18n.t("cloud.error.vaultRename"));
        });
        if (failed[0] != null) throw failed[0];
        return all[0];
    }

    /**
     * One file: opened under its old path, sealed under its new one where its new place has none or loses to it;
     * whether its new place holds it now (false for one that doesn't open with this key: left as it is). Before the
     * switch, written only while the old names are still the vault's (another computer may have switched them).
     */
    private static boolean file(Storage dav, byte[] vk, String stored, boolean before, Set<String> made) throws IOException {
        String old = CloudAccount.ROOT + stored.substring(CloudNames.OLD_VAULT.length());
        String logical = CloudNames.renamed(old);
        String target = CloudNames.newStored(stored);
        byte[] plain;
        try {
            byte[] sealed = dav.get(stored);
            if (sealed == null) return false;
            plain = VaultCrypto.open(vk, old, sealed);
        } catch (VaultCrypto.Broken e) {
            return false; // told once, with the others left (migrate)
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
     * written after it was copied in this same call (before the switch: the newer); the old one deleted after. A slot
     * under the new names written in a later call is the newer: every job that writes slots finishes this first
     * ({@link CloudRenames#forJob}), so nothing old comes back over a way in changed or taken away since.
     */
    private static void slots(Storage dav, boolean finishing, Map<String, String> copied) throws IOException {
        for (String name : dav.list(CloudNames.OLD_SLOTS)) {
            if (!name.endsWith(".json") || !Ids.ours(name.substring(0, name.length() - ".json".length()))) continue;
            String from = CloudNames.OLD_SLOTS + "/" + name;
            String to = Vault.SLOTS + "/" + name;
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

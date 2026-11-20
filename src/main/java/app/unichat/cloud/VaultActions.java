package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What the person does with the vault from Ajustes → Nube → Bóveda, over the account: make it, open it here with a way
 * in, go on without it, add a password, make a new recovery code, and what the page shows of it. {@link Cloud} runs
 * each on its worker; the secrets given are cleared once used, and never said.
 */
final class VaultActions {
    /** The vault id whose latest recovery code the person hasn't yet confirmed they kept (machine-only). */
    static final String UNCONFIRMED = "cloud.vault.codeUnconfirmed";

    private VaultActions() {}

    /** A secret that opens none of the vault's ways in: nothing was written. */
    static final class WrongSecret extends IOException {
        WrongSecret() {
            super(I18n.t("cloud.error.vaultWrongSecret"));
        }
    }

    /** What the page shows, as {@code round} found the vault here. */
    static VaultView view(Storage dav, VaultParts.Round round) throws IOException {
        if (round.state() == Cloud.VaultState.NONE) return VaultView.NONE;
        Vault.Info info = Vault.info(dav);
        if (info == null) return new VaultView(round.state(), Set.of(), List.of(), false);
        Set<VaultView.Way> ways = EnumSet.noneOf(VaultView.Way.class);
        List<VaultView.Computer> computers = new ArrayList<>();
        byte[] vk = round.open() ? round.key() : null;
        try {
            VaultPeers peers = VaultPeers.load(info.id());
            String here = Cloud.machine();
            for (VaultSlot slot : Vault.slots(dav)) {
                if (slot.method() != VaultSlot.Method.COMPUTER) {
                    // A change nobody vouches for leaves the ways in from before it as the ones that open the vault.
                    if (slot.generation() == info.generation() || round.state() == Cloud.VaultState.UNRECOGNIZED) {
                        ways.add(VaultView.Way.valueOf(slot.method().name()));
                    }
                } else if (vk != null) {
                    boolean mine = slot.id().equals(here);
                    byte[] key = VaultNext.publicKey(slot, vk);
                    computers.add(new VaultView.Computer(slot.id(), VaultView.label(slot.label()), peers.firstSeen(slot.id()), mine,
                        !mine && peers.keyChanged(slot.id(), key), !mine && peers.newSinceRemoval(slot.id()), !mine && !peers.seen(slot.id())));
                }
            }
        } finally {
            if (vk != null) Arrays.fill(vk, (byte) 0);
        }
        computers.sort(Comparator.comparing((VaultView.Computer c) -> !c.here()).thenComparing(VaultView.Computer::label));
        return new VaultView(round.state(), ways, computers, info.id().equals(Settings.get().getString(UNCONFIRMED, null)));
    }

    /** A new vault, open here; its recovery code as printed (shown once: never kept). */
    static String create(Storage dav) throws IOException {
        Vault.Created made;
        try {
            made = Vault.create(dav, Cloud.machine(), computerName());
        } catch (Vault.Exists e) {
            throw new IOException(I18n.t("cloud.error.vaultExists"), e);
        }
        try {
            hold(made.info(), made.computer());
            Settings.get().set(UNCONFIRMED, made.info().id());
            Settings.get().set(VaultMove.RECORD, made.info().id()); // made here: the first to move, it says who sent plain
            return made.code().shown();
        } finally {
            Arrays.fill(made.vk(), (byte) 0);
        }
    }

    /**
     * The vault opened here with {@code way}'s {@code secret} (the recovery code as typed, or the password): this
     * computer gets a slot of its own and goes on with it. A secret that opens nothing writes nothing. Only a slot of
     * the vault's current generation counts, but on a computer that holds this vault and finds it {@code UNRECOGNIZED}
     * (a generation raised through the account): there one left from before counts too, only when it opens the key its
     * own slot opens, so a generation raised by hand locks no one out and a way in put back from before a real change
     * never hands anyone the old key.
     */
    static void open(Storage dav, VaultView.Way way, char[] secret, Cloud.VaultState here) throws IOException {
        Vault.Info info = Vault.info(dav);
        if (info == null) throw new IOException(I18n.t("cloud.error.noVault"));
        byte[] vk = null;
        List<VaultSlot> older = new ArrayList<>();
        for (VaultSlot slot : Vault.slots(dav)) {
            if (!slot.method().name().equals(way.name())) continue;
            if (slot.generation() < info.generation()) older.add(slot);
            else if (slot.generation() == info.generation() && vk == null) vk = unwrap(dav, slot, way, secret);
        }
        if (vk == null && here == Cloud.VaultState.UNRECOGNIZED) vk = heldBefore(dav, info, older, way, secret);
        if (vk == null) throw new WrongSecret();
        try {
            Vault.Computer computer = Vault.addComputer(dav, vk, Cloud.machine(), computerName());
            hold(info, computer);
        } finally {
            Arrays.fill(vk, (byte) 0);
        }
    }

    /**
     * The key this computer's own slot opens (it holds {@code vault}, its slot older than the generation), when one of
     * {@code older} opens it with this secret too; null otherwise.
     */
    private static byte[] heldBefore(Storage dav, Vault.Info vault, List<VaultSlot> older, VaultView.Way way, char[] secret)
        throws IOException {
        Settings s = Settings.get();
        VaultSlot own = vault.id().equals(s.getString(VaultParts.ID, null)) ? Vault.slot(dav, vault, Cloud.machine()) : null;
        String key = s.getString(VaultParts.KEY, null);
        if (own == null || own.method() != VaultSlot.Method.COMPUTER || key == null) return null;
        byte[] slotKey = null, held = null;
        try {
            slotKey = Base64.getDecoder().decode(key);
            held = own.unwrap(slotKey);
            for (VaultSlot slot : older) {
                byte[] old = unwrap(dav, slot, way, secret);
                if (old != null && java.security.MessageDigest.isEqual(old, held)) return old;
                if (old != null) Arrays.fill(old, (byte) 0);
            }
            return null;
        } catch (IllegalArgumentException | VaultCrypto.Broken e) {
            return null;
        } finally {
            if (slotKey != null) Arrays.fill(slotKey, (byte) 0);
            if (held != null) Arrays.fill(held, (byte) 0);
        }
    }

    /**
     * The vault key from {@code slot} with this secret; null when it doesn't open it. A slot written before its
     * generation was sealed is written again so (best effort: the next opening tries again).
     */
    private static byte[] unwrap(Storage dav, VaultSlot slot, VaultView.Way way, char[] secret) {
        byte[] key = null;
        try {
            if (way == VaultView.Way.RECOVERY) {
                RecoveryCode code = RecoveryCode.parse(secret);
                if (code == null) return null;
                key = VaultSlot.recoveryKey(code);
                Arrays.fill(code.bits(), (byte) 0);
            } else if (way == VaultView.Way.PASSWORD) {
                key = VaultSlot.passwordKey(secret, slot.params());
            } else {
                return null; // Google and the passkey open in the browser
            }
            VaultSlot.Opened opened = slot.open(key);
            if (opened.old()) Vault.writeAgain(dav, slot.sealedAgain(key, opened.vk()));
            return opened.vk();
        } catch (VaultCrypto.Broken e) {
            return null;
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    /** This computer's hold on {@code vault}: its slot's key and its private key, kept here; going on without it is over. */
    private static void hold(Vault.Info vault, Vault.Computer computer) {
        Settings s = Settings.get();
        String was = s.getString(VaultParts.ID, null);
        if (was != null && !was.equals(vault.id())) {
            VaultMove.forget();
            VaultPeers.forget();
        }
        s.set(VaultParts.ID, vault.id());
        s.set(VaultParts.KEY, Base64.getEncoder().encodeToString(computer.key()));
        s.set(VaultParts.PRIVATE, Base64.getEncoder().encodeToString(computer.pair().getPrivate().getEncoded()));
        s.set(VaultParts.DECLINED, null);
        VaultParts.seen(vault);
        Arrays.fill(computer.key(), (byte) 0);
    }

    /** Another computer's way into the vault taken away; this one's own goes by {@link Cloud#forgetHere}. */
    static void remove(Storage dav, String machine) throws IOException {
        if (Cloud.machine().equals(machine)) throw new IllegalArgumentException("This computer is forgotten with forgetHere");
        if (!Ids.ours(machine)) throw new IllegalArgumentException("Not a machine id");
        Vault.Info info = Vault.info(dav);
        if (info == null) throw new IOException(I18n.t("cloud.error.noVault"));
        // Kept out here first: a slot that can't be deleted now is never offered the next key either.
        VaultPeers peers = VaultPeers.load(info.id());
        peers.removed(machine);
        peers.save();
        Vault.removeComputer(dav, info, machine);
    }

    /** "Seguir sin la bóveda en este equipo", or taken back: never asked again while it holds. */
    static void decline(boolean declined) {
        Settings.get().set(VaultParts.DECLINED, declined ? "true" : null);
    }

    /** A password as one more way in; at least {@link Vault#MIN_PASSWORD} characters. */
    static void addPassword(Storage dav, byte[] vk, char[] password) throws IOException {
        if (!Vault.longEnough(password)) {
            throw new IOException(I18n.t("cloud.error.vaultShortPassword", Vault.MIN_PASSWORD));
        }
        Vault.addPassword(dav, vk, password);
    }

    /** A new recovery code in place of every other one, to be confirmed as kept. */
    static String newRecovery(Storage dav, byte[] vk, Vault.Info vault) throws IOException {
        String code = Vault.newRecovery(dav, vk).shown();
        Settings.get().set(UNCONFIRMED, vault.id());
        return code;
    }

    /** The person typed the groups asked back: they kept the latest code. */
    static void codeKept() {
        Settings.get().set(UNCONFIRMED, null);
    }

    /** What this computer is called in the vault's list of computers. */
    static String computerName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                name = null;
            }
        }
        if (name == null || name.isBlank()) return "Unichat";
        return VaultView.label(name.replaceFirst("(?i)\\.local$", ""));
    }
}

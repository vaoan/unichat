package app.unichat.cloud;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What Ajustes → Nube → Bóveda shows of the vault: its state here, the ways in it has (by method: a slot's own label
 * is the account's to write, so it is never what names a way in), its computers while it is open here, and whether
 * the person hasn't yet confirmed they kept its latest recovery code.
 */
public record VaultView(Cloud.VaultState state, Set<Way> ways, List<Computer> computers, boolean codeUnconfirmed) {
    /** A way in other than a computer's own. */
    public enum Way { RECOVERY, PASSWORD, GOOGLE, PASSKEY }

    /**
     * One computer of the vault. Its label comes from the account (anyone who can write there can put anything in it):
     * it is one plain line, shown as text only.
     *
     * @param machine its id
     * @param firstSeen when this computer first saw it; null when unknown
     * @param here this computer
     * @param keyChanged the next key would be refused to it (removed here, or its key isn't the one first seen)
     * @param newSinceRemoval it came after the last computer removed here
     */
    public record Computer(String machine, String label, Instant firstSeen, boolean here, boolean keyChanged, boolean newSinceRemoval) {}

    static final VaultView NONE = new VaultView(Cloud.VaultState.NONE, Set.of(), List.of(), false);
}

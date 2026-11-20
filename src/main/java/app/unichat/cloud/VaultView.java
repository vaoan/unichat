package app.unichat.cloud;

import app.unichat.support.Lines;

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
     * @param unseen this computer hasn't seen it before (not yet in a round): a key change would pin it as it is now
     */
    public record Computer(String machine, String label, Instant firstSeen, boolean here, boolean keyChanged, boolean newSinceRemoval,
                           boolean unseen) {}

    private static final int LABEL = 64;

    /**
     * A computer's name from the account as it may be shown: one line (line ends, controls and the marks that turn text
     * around become a space), at most 64 characters. The one cleaner for every place a name is shown.
     */
    public static String label(String s) {
        if (s == null) return "";
        String one = Lines.one(s);
        return one.codePointCount(0, one.length()) <= LABEL ? one : one.substring(0, one.offsetByCodePoints(0, LABEL)) + "…";
    }

    static final VaultView NONE = new VaultView(Cloud.VaultState.NONE, Set.of(), List.of(), false);
}

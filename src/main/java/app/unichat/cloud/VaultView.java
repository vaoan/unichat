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
    /** Marks that change how a name reads without showing (direction marks, zero-width spaces and joiners, the BOM). */
    private static final java.util.regex.Pattern INVISIBLE = java.util.regex.Pattern.compile("[\\u200B-\\u200F\\u061C\\u2060\\uFEFF]");

    /**
     * A computer's name from the account as it may be shown: one line (line ends, controls and the marks that turn text
     * around become a space; invisible marks go), at most 64 characters as the eye counts them. The one cleaner for every
     * place a name is shown.
     */
    public static String label(String s) {
        if (s == null) return "";
        String one = Lines.one(INVISIBLE.matcher(s).replaceAll(""));
        // Cut between whole characters as seen (a flag, an accented letter, an emoji with its skin tone), never inside one.
        java.text.BreakIterator chars = java.text.BreakIterator.getCharacterInstance(app.unichat.support.I18n.locale());
        chars.setText(one);
        int end = 0;
        for (int i = 0; i < LABEL; i++) {
            int next = chars.next();
            if (next == java.text.BreakIterator.DONE) return one;
            end = next;
        }
        return chars.next() == java.text.BreakIterator.DONE ? one : one.substring(0, end) + "…";
    }

    static final VaultView NONE = new VaultView(Cloud.VaultState.NONE, Set.of(), List.of(), false);
}

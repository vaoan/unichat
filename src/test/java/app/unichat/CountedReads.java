package app.unichat;

/**
 * Text that counts how often it is read: a regular expression's matcher reads its input one char at a time, so the
 * count says how much work a match did, with no clock. A linear one reads each char a few times; one that backtracks
 * over the same text again reads it as many times as it's long.
 */
public final class CountedReads implements CharSequence {
    private final String text;
    private long reads;

    public CountedReads(String text) {
        this.text = text;
    }

    /** How many chars were read so far, per char of the text. */
    public double perChar() {
        return (double) reads / Math.max(1, text.length());
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public char charAt(int index) {
        reads++;
        return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return text.subSequence(start, end);
    }

    @Override
    public String toString() {
        return text;
    }
}

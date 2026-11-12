package app.unichat.ui.media;

/**
 * Memory for the frames of the animations playing at once. A chat full of stickers kept 56 animations (2 520 decoded
 * frames) alive against a 1 GB heap and ran out: past the budget a sticker shows still (its first frame) until another
 * stops. One alone always plays: it was already decoded to fit {@code AnimatedImage.MAX_ANIMATION_BYTES}.
 */
final class AnimationBudget {
    static final AnimationBudget SHARED = new AnimationBudget(128L << 20);

    private final long limit;
    private long used;

    AnimationBudget(long limit) {
        this.limit = limit;
    }

    /** Takes {@code bytes} for one more animation; false when it doesn't fit (it should show still). */
    synchronized boolean take(long bytes) {
        if (used > 0 && used + bytes > limit) return false;
        used += bytes;
        return true;
    }

    synchronized void release(long bytes) {
        used = Math.max(0, used - bytes);
    }

    /** Bytes taken by the animations playing now. */
    synchronized long used() { return used; }
}

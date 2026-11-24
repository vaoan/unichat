package app.unichat.cloud;

import app.unichat.settings.Settings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One thing kept the same on every computer of the account (the settings, what the AI learned), as one file in it.
 * Each {@link #sync} compares three versions: this computer's, the account's, and the one both last agreed on (its
 * {@code base}, kept on this computer). Only one side changed: that one goes to the other. Both did: the newer wins.
 * A computer meeting the account for the first time takes what's there. What's taken applies at once ({@code adopt}).
 */
final class SyncedPart {
    enum Action { NOTHING, UPLOAD, ADOPT }

    private final String remotePath;
    private final String baseKey;
    private final Function<Instant, Bundle> local;
    private final java.util.function.Predicate<Bundle> adopt;
    /** The account's version when last seen: while it's the same, nothing is downloaded. */
    private volatile @Nullable String seenStamp;
    private @Nullable String lastLocal;
    private Instant localChangedAt = Instant.EPOCH;

    /**
     * @param local this computer's version, written now (the time it's given)
     * @param adopt takes the account's version in place of this computer's, at once
     */
    SyncedPart(String remotePath, String name, Function<Instant, Bundle> local, Consumer<Bundle> adopt) {
        this(theirs -> {
            adopt.accept(theirs);
            return false;
        }, remotePath, name, local);
    }

    /**
     * The same for a part merged rather than replaced ({@code adopt} keeps some of this computer's): {@code adopt} says
     * whether this computer now holds more than the account's, which then goes up in the next round.
     */
    static SyncedPart merged(String remotePath, String name, Function<Instant, Bundle> local, java.util.function.Predicate<Bundle> adopt) {
        return new SyncedPart(adopt, remotePath, name, local);
    }

    private SyncedPart(java.util.function.Predicate<Bundle> adopt, String remotePath, String name, Function<Instant, Bundle> local) {
        this.remotePath = remotePath;
        this.baseKey = "cloud.base." + name;
        this.local = local;
        this.adopt = adopt;
    }

    /** What to do, from the three versions' hashes; {@code remote} null when the account has none. Pure. */
    static Action decide(String local, @Nullable String remote, @Nullable String base, Instant localAt, Instant remoteAt) {
        if (remote == null) return Action.UPLOAD;
        if (remote.equals(local)) return Action.NOTHING;
        if (base == null || local.equals(base)) return Action.ADOPT; // new here, or only the account changed
        if (remote.equals(base)) return Action.UPLOAD;
        return remoteAt.isAfter(localAt) ? Action.ADOPT : Action.UPLOAD;
    }

    /** One round with the account; what it did. */
    @SuppressFBWarnings(value = "UNSAFE_HASH_EQUALS", justification = "SHA-256 of the person's own settings, compared to tell whether they changed: nothing secret to find by timing")
    Action sync(Storage dav, Instant now) throws IOException {
        Bundle mine = local.apply(now);
        String mineHash = mine.hash();
        if (!mineHash.equals(lastLocal)) {
            if (lastLocal != null) localChangedAt = now;
            lastLocal = mineHash;
        }
        String base = Settings.get().getString(baseKey);
        String stamp = dav.stamp(remotePath);
        if (stamp != null && stamp.equals(seenStamp)) {
            // The account's is the one already agreed on: only this computer can have changed.
            if (mineHash.equals(base)) return Action.NOTHING;
            upload(dav, mine, mineHash);
            return Action.UPLOAD;
        }
        Bundle theirs = stamp == null ? null : Bundle.parse(dav.get(remotePath));
        String theirsHash = theirs == null ? null : theirs.hash();
        Action a = decide(mineHash, theirsHash, base, localChangedAt, theirs == null ? Instant.EPOCH : theirs.savedAt());
        switch (a) {
            case UPLOAD -> upload(dav, mine, mineHash);
            case ADOPT -> {
                boolean more = adopt.test(theirs);
                // What this computer holds now is the agreed one, even if writing it here put it a bit differently:
                // agreeing on the account's own hash would send it back up next round, to and fro forever. Unless it
                // kept more than the account has: then only the account's is agreed, and the next round sends this one.
                lastLocal = local.apply(now).hash();
                agreed(more ? theirsHash : lastLocal, stamp);
            }
            case NOTHING -> agreed(mineHash, stamp);
        }
        return a;
    }

    /** Whether {@code plain} at {@code path} is this part as this computer last agreed on it. */
    boolean holds(String path, byte[] plain) {
        if (!path.equals(remotePath)) return false;
        Bundle b = Bundle.parse(plain);
        String base = Settings.get().getString(baseKey);
        return b != null && base != null && base.equals(b.hash());
    }

    /**
     * The account's file is another one now (the vault opened or closed here, and both may carry the same stamp): read it
     * again next round. The agreed hash stays, since it's of what's inside.
     */
    void unseen() {
        seenStamp = null;
    }

    private void upload(Storage dav, Bundle mine, String hash) throws IOException {
        dav.mkdirs(remotePath.substring(0, remotePath.lastIndexOf('/')));
        dav.put(remotePath, mine.toBytes());
        agreed(hash, dav.stamp(remotePath));
    }

    private void agreed(@Nullable String hash, @Nullable String stamp) {
        Settings.get().set(baseKey, hash);
        seenStamp = stamp;
    }
}

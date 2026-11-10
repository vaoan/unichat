package app.unichat.cloud;

import app.unichat.settings.Settings;

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
    private final Consumer<Bundle> adopt;
    /** The account's version when last seen: while it's the same, nothing is downloaded. */
    private String seenStamp;
    private String lastLocal;
    private Instant localChangedAt = Instant.EPOCH;

    /**
     * @param local this computer's version, written now (the time it's given)
     * @param adopt takes the account's version in place of this computer's, at once
     */
    SyncedPart(String remotePath, String name, Function<Instant, Bundle> local, Consumer<Bundle> adopt) {
        this.remotePath = remotePath;
        this.baseKey = "cloud.base." + name;
        this.local = local;
        this.adopt = adopt;
    }

    /** What to do, from the three versions' hashes; {@code remote} null when the account has none. Pure. */
    static Action decide(String local, String remote, String base, Instant localAt, Instant remoteAt) {
        if (remote == null) return Action.UPLOAD;
        if (remote.equals(local)) return Action.NOTHING;
        if (base == null || local.equals(base)) return Action.ADOPT; // new here, or only the account changed
        if (remote.equals(base)) return Action.UPLOAD;
        return remoteAt.isAfter(localAt) ? Action.ADOPT : Action.UPLOAD;
    }

    /** One round with the account; what it did. */
    Action sync(Storage dav, Instant now) throws IOException {
        Bundle mine = local.apply(now);
        String mineHash = mine.hash();
        if (!mineHash.equals(lastLocal)) {
            if (lastLocal != null) localChangedAt = now;
            lastLocal = mineHash;
        }
        String base = Settings.get().getString(baseKey, null);
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
                adopt.accept(theirs);
                // What this computer holds now is the agreed one, even if writing it here put it a bit differently:
                // agreeing on the account's own hash would send it back up next round, to and fro forever.
                lastLocal = local.apply(now).hash();
                agreed(lastLocal, stamp);
            }
            case NOTHING -> agreed(mineHash, stamp);
        }
        return a;
    }

    private void upload(Storage dav, Bundle mine, String hash) throws IOException {
        dav.mkdirs(remotePath.substring(0, remotePath.lastIndexOf('/')));
        dav.put(remotePath, mine.toBytes());
        agreed(hash, dav.stamp(remotePath));
    }

    private void agreed(String hash, String stamp) {
        Settings.get().set(baseKey, hash);
        seenStamp = stamp;
    }
}

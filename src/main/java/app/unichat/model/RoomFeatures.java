package app.unichat.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a chat's network supports, as its bridge publishes it in the {@code com.beeper.room_features} state event
 * (mautrix-go {@code event.RoomFeatures}). The UI asks this instead of hardcoding per network, so it follows the
 * bridges. Support levels: −2 rejected, −1 dropped, 0 unsupported, 1 partial, 2 full; offered means ≥ 1.
 * {@link #UNKNOWN} (no event: external server, older bridge) offers everything, as Unichat always did.
 */
public final class RoomFeatures {
    /** Pseudo message types used in the {@code file} map besides m.image/m.video/m.audio/m.file. */
    public static final String STICKER = "m.sticker", GIF = "fi.mau.gif", VOICE = "org.matrix.msc3245.voice";

    public static final RoomFeatures UNKNOWN = new RoomFeatures(true, 2, 0, 2, 0, false, 2, 2, 0, null, null, true, null);

    private final boolean unrestricted;
    private final int edit;
    private final long editMaxAgeSec;
    private final int delete;
    private final long deleteMaxAgeSec;
    private final boolean deleteForMe;
    private final int reply;
    private final int reaction;
    private final int reactionCount;
    private final List<String> allowedReactions;
    /** msgtype → max size in bytes (0 = not stated); only types with some MIME offered. */
    private final Map<String, Long> files;
    private final boolean anyFile;
    /** msgtype → MIME types offered (may hold wildcards like image/*); a type missing here takes any. */
    private final Map<String, java.util.Set<String>> mimes;

    private RoomFeatures(boolean unrestricted, int edit, long editMaxAgeSec, int delete, long deleteMaxAgeSec, boolean deleteForMe,
                         int reply, int reaction, int reactionCount, List<String> allowedReactions, Map<String, Long> files,
                         boolean anyFile, Map<String, java.util.Set<String>> mimes) {
        this.unrestricted = unrestricted;
        this.edit = edit;
        this.editMaxAgeSec = editMaxAgeSec;
        this.delete = delete;
        this.deleteMaxAgeSec = deleteMaxAgeSec;
        this.deleteForMe = deleteForMe;
        this.reply = reply;
        this.reaction = reaction;
        this.reactionCount = reactionCount;
        this.allowedReactions = allowedReactions;
        this.files = files == null ? Map.of() : Map.copyOf(files);
        this.anyFile = anyFile;
        this.mimes = mimes == null ? Map.of() : Map.copyOf(mimes);
    }

    public static RoomFeatures parse(JsonNode c) {
        if (c == null || c.isEmpty()) return UNKNOWN; // redacted or emptied: don't strip every option
        Map<String, Long> files = new HashMap<>();
        Map<String, java.util.Set<String>> mimes = new HashMap<>();
        JsonNode file = c.path("file");
        file.fieldNames().forEachRemaining(type -> {
            JsonNode rule = file.path(type);
            java.util.Set<String> offered = new java.util.HashSet<>();
            rule.path("mime_types").fields().forEachRemaining(m -> {
                if (m.getValue().asInt() >= 1) offered.add(m.getKey().toLowerCase(java.util.Locale.ROOT));
            });
            if (!offered.isEmpty()) {
                files.put(type, rule.path("max_size").asLong(0));
                mimes.put(type, offered);
            }
        });
        List<String> allowed = null;
        if (c.has("allowed_reactions")) {
            allowed = new ArrayList<>();
            for (JsonNode r : c.path("allowed_reactions")) allowed.add(r.asText());
        }
        return new RoomFeatures(false, c.path("edit").asInt(0), c.path("edit_max_age").asLong(0),
                c.path("delete").asInt(0), c.path("delete_max_age").asLong(0), c.path("delete_for_me").asBoolean(false),
                c.path("reply").asInt(0), c.path("reaction").asInt(0), c.path("reaction_count").asInt(0), allowed, files, false, mimes);
    }

    /** mautrix-discord predates room features: what its code supports (edits/deletes without limit, no voice). */
    public static RoomFeatures discord() {
        Map<String, Long> files = new HashMap<>();
        for (String t : List.of("m.image", "m.video", "m.audio", "m.file", STICKER, GIF)) files.put(t, 0L);
        return new RoomFeatures(false, 2, 0, 2, 0, false, 2, 2, 0, null, files, false, null);
    }

    public boolean accepts(String type) {
        return anyFile || files.containsKey(type);
    }

    /** Whether {@code type} messages may carry a {@code mime} file (exact, {@code image/*} or {@code *}{@code /*}). */
    public boolean acceptsMime(String type, String mime) {
        if (anyFile) return true;
        if (!accepts(type)) return false;
        java.util.Set<String> m = mimes.get(type);
        if (m == null || m.isEmpty()) return true;
        String x = mime == null ? "" : mime.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        int slash = x.indexOf('/');
        return m.contains(x) || m.contains("*/*") || (slash > 0 && m.contains(x.substring(0, slash) + "/*"));
    }

    /**
     * How a recorded voice note goes out here, or null when it can't: Opus in Ogg is what's recorded, so a chat that only
     * takes other formats isn't offered it. WhatsApp insists on the codec in the type, which the others don't name.
     *
     * @param voice false: as a plain audio file (the network has no voice notes, but takes the file)
     */
    public record VoiceSend(String mime, boolean voice) {}

    private static final String OPUS_WITH_CODEC = "audio/ogg; codecs=opus", OGG = "audio/ogg";

    public VoiceSend voiceSend() {
        if (anyFile) return new VoiceSend(OGG, true);
        java.util.Set<String> voice = mimes.get(VOICE);
        if (voice != null && voice.contains(OPUS_WITH_CODEC)) return new VoiceSend(OPUS_WITH_CODEC, true);
        if (voice != null && voice.contains(OGG)) return new VoiceSend(OGG, true);
        return acceptsMime("m.audio", OGG) ? new VoiceSend(OGG, false) : null;
    }

    /** Largest file the network takes for {@code type}, in bytes; 0 when not stated. */
    public long maxSize(String type) {
        return files.getOrDefault(type, 0L);
    }

    /** Edit offered for a message sent at {@code sent}, judged at {@code now} (limits count from the send time). */
    public boolean canEdit(Instant sent, Instant now) {
        return edit >= 1 && withinAge(sent, now, editMaxAgeSec);
    }

    public boolean canDeleteForEveryone(Instant sent, Instant now) {
        return delete >= 1 && withinAge(sent, now, deleteMaxAgeSec);
    }

    public boolean deleteForMe() { return deleteForMe; }
    public boolean replies() { return reply >= 1; }
    public boolean oneReactionPerUser() { return reactionCount == 1; }

    /** The quick reactions this chat accepts (all when it doesn't restrict them; none without reactions). */
    public List<String> reactionsOffered(List<String> quick) {
        if (reaction < 1) return List.of();
        if (allowedReactions == null) return quick;
        Set<String> allowed = new java.util.HashSet<>();
        for (String r : allowedReactions) allowed.add(stripVs(r));
        return quick.stream().filter(q -> allowed.contains(stripVs(q))).toList();
    }

    /** Any emoji can be a reaction (the "Más emojis" picker makes sense). */
    public boolean anyReaction() { return reaction >= 1 && allowedReactions == null; }

    public boolean isUnknown() { return unrestricted; }

    private static boolean withinAge(Instant sent, Instant now, long maxAgeSec) {
        return maxAgeSec <= 0 || sent == null || Duration.between(sent, now).getSeconds() <= maxAgeSec;
    }

    /** "❤️" and "❤" are the same reaction: drop the emoji variation selector. */
    private static String stripVs(String s) {
        return s.replace("️", "");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RoomFeatures f && unrestricted == f.unrestricted && edit == f.edit && editMaxAgeSec == f.editMaxAgeSec
                && delete == f.delete && deleteMaxAgeSec == f.deleteMaxAgeSec && deleteForMe == f.deleteForMe && reply == f.reply
                && reaction == f.reaction && reactionCount == f.reactionCount
                && java.util.Objects.equals(allowedReactions, f.allowedReactions) && files.equals(f.files) && anyFile == f.anyFile
                && mimes.equals(f.mimes);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(edit, editMaxAgeSec, delete, deleteMaxAgeSec, reply, reaction, files, anyFile);
    }
}

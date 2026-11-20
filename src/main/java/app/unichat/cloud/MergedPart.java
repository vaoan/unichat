package app.unichat.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A part kept as the union of every computer's (the transcripts, which only grow): each round what the account has and
 * this computer doesn't comes here, and what this computer has and the account doesn't goes there. Nothing is ever
 * deleted, so two computers writing at once lose nothing for long: the one left out puts its own back next round. An
 * entry both have stays as the account has it, so they never write it to and fro. Names and values come from the
 * account: {@code add} decides which become files.
 */
final class MergedPart {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String remotePath;
    private final Supplier<Set<String>> names;
    private final Function<String, String> read;
    private final Consumer<Map<String, String>> add;
    /** The account's file when last seen, and the names it held: while it's the same and nothing new is here, nothing is read. */
    private String seenStamp;
    private Set<String> seenNames = Set.of();

    /**
     * @param names this computer's entries' names (cheap: asked every round)
     * @param read one entry's text, only for what goes up
     * @param add takes the account's entries this computer lacks
     */
    MergedPart(String remotePath, Supplier<Set<String>> names, Function<String, String> read, Consumer<Map<String, String>> add) {
        this.remotePath = remotePath;
        this.names = names;
        this.read = read;
        this.add = add;
    }

    /** One round with the account. */
    synchronized void sync(Storage storage) throws IOException {
        Set<String> mine = names.get();
        String stamp = storage.stamp(remotePath);
        if (stamp != null && stamp.equals(seenStamp) && seenNames.containsAll(mine)) return;
        Map<String, String> theirs = stamp == null ? Map.of() : parse(storage.get(remotePath));
        Map<String, String> missing = new TreeMap<>(theirs);
        missing.keySet().removeAll(mine);
        if (!missing.isEmpty()) add.accept(missing);
        Map<String, String> union = new TreeMap<>(theirs);
        for (String name : mine) {
            if (union.containsKey(name)) continue;
            String text = read.apply(name);
            if (text != null && !text.isEmpty()) union.put(name, text); // gone since it was listed: never up as ""
        }
        if (union.size() > theirs.size()) {
            storage.mkdirs(remotePath.substring(0, remotePath.lastIndexOf('/')));
            storage.put(remotePath, json(union));
            stamp = storage.stamp(remotePath);
        }
        seenStamp = stamp;
        seenNames = Set.copyOf(union.keySet());
    }

    /** Whether {@code plain} at {@code path} is this part's file holding only entries this computer has, as it has them. */
    boolean holds(String path, byte[] plain) {
        if (!path.equals(remotePath)) return false;
        Map<String, String> theirs = strict(plain);
        if (theirs == null) return false;
        Set<String> mine = names.get();
        return theirs.entrySet().stream().allMatch(e -> mine.contains(e.getKey()) && e.getValue().equals(read.apply(e.getKey())));
    }

    /** The account's file may be another's after the vault opened or closed here: read it again next round. */
    synchronized void unseen() {
        seenStamp = null;
    }

    /** The entries, or null when {@code data} isn't this part's file: an object of texts under "entries". */
    private static Map<String, String> strict(byte[] data) {
        try {
            JsonNode entries = JSON.readTree(data).path("entries");
            if (!entries.isObject()) return null;
            Map<String, String> out = new TreeMap<>();
            for (var e : entries.properties()) {
                if (!e.getValue().isTextual()) return null;
                out.put(e.getKey(), e.getValue().asText());
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** What isn't an object of texts reads as nothing. */
    private static Map<String, String> parse(byte[] data) {
        Map<String, String> out = new TreeMap<>();
        if (data == null) return out;
        try {
            JsonNode entries = JSON.readTree(data).path("entries");
            if (entries.isObject()) entries.properties().forEach(e -> { if (e.getValue().isTextual()) out.put(e.getKey(), e.getValue().asText()); });
        } catch (IOException | RuntimeException e) {
            out.clear();
        }
        return out;
    }

    private static byte[] json(Map<String, String> entries) throws IOException {
        ObjectNode n = JSON.createObjectNode().put("version", 1);
        entries.forEach(n.putObject("entries")::put);
        return JSON.writeValueAsBytes(n);
    }
}

package app.unichat.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A machine's {@code index.json} in {@link TextSync#ROOT}: the size of each of its text files, by name. Read from the
 * account, so untrusted: only names of ours ({@link #FILE}) and whole numbers are kept. Pure.
 */
final class TextIndex {
    static final String NAME = "index.json";
    /** A text file of ours (its chat's key, its month): anything else in the account never becomes a path here. */
    static final Pattern FILE = Pattern.compile("([0-9a-f]{12}) \\d{4}-\\d{2}\\.jsonl");
    private static final ObjectMapper JSON = new ObjectMapper();

    private TextIndex() {}

    /**
     * The sizes {@code index} says, of files of ours; empty for none. A file that isn't JSON fails (it's told, and what it
     * stands for is tried again next round), rather than reading as an index of nothing.
     */
    static Map<String, Long> read(byte[] index) throws IOException {
        Map<String, Long> out = new TreeMap<>();
        if (index == null) return out;
        JsonNode n = JSON.readTree(index);
        if (n == null) return out;
        n.path("files").properties().forEach(e -> {
            if (FILE.matcher(e.getKey()).matches() && e.getValue().canConvertToLong()) out.put(e.getKey(), e.getValue().asLong());
        });
        return out;
    }

    static byte[] write(Map<String, Long> sizes) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        ObjectNode files = n.putObject("files");
        new TreeMap<>(sizes).forEach(files::put);
        return JSON.writeValueAsBytes(n);
    }
}

package app.unichat.assist;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What changed between two versions of {@code writing-style.md}, trait by trait: the file is a list of "- " bullets,
 * compared as bullets, whatever their order or spacing. A trait reworded counts as one removed and one added.
 *
 * @param added the traits of the newer version that the older didn't have, in the newer one's order
 * @param removed the traits of the older version that the newer doesn't have, in the older one's order
 */
public record StyleDiff(List<String> added, List<String> removed) {
    public StyleDiff {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
    }

    public static StyleDiff between(String before, String after) {
        Set<String> was = traits(before), is = traits(after);
        List<String> added = new ArrayList<>(is), removed = new ArrayList<>(was);
        added.removeAll(was);
        removed.removeAll(is);
        return new StyleDiff(added, removed);
    }

    public boolean isEmpty() { return added.isEmpty() && removed.isEmpty(); }

    /** Each bullet's text, without its "- " or "* " and with its spaces evened out; headings and other lines aren't traits. */
    static Set<String> traits(String file) {
        Set<String> out = new LinkedHashSet<>();
        if (file == null) return out;
        for (String line : file.split("\n")) {
            String l = line.strip();
            if (!l.startsWith("- ") && !l.startsWith("* ")) continue;
            String trait = l.substring(2).strip().replaceAll("\\s+", " ");
            if (!trait.isEmpty()) out.add(trait);
        }
        return out;
    }
}

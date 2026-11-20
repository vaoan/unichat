package app.unichat.cloud;

import java.util.regex.Pattern;

/** The ids Unichat makes (a machine's, a vault's, a slot's: random UUIDs): only such a name from the account becomes a path. */
final class Ids {
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private Ids() {}

    static boolean ours(String id) {
        return id != null && UUID.matcher(id).matches();
    }
}

package app.unichat.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal data from a log line before it is written to disk: Matrix user/room/event IDs, media IDs,
 * phone numbers and other long numeric IDs, e-mail addresses, secrets (tokens, passwords, cookies, sessions, as
 * JSON, YAML, query parameters or headers), and the values of JSON fields that carry names or message text (bridges
 * log those). What happened (levels, messages, errors, times) stays readable.
 */
public final class Redactor {
    private static final Pattern JSON_FIELD = Pattern.compile(
            "\"(name|displayname|display_name|full_name|first_name|last_name|username|user_name|push_name|pushname"
                    + "|chat_name|room_name|title|topic|body|text|caption|formatted_body|phone|phone_number|email"
                    + "|sender_name|contact_name|nickname|login_name|remote_name|description)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"",
            Pattern.CASE_INSENSITIVE);
    /** A key that names a secret: anything ending in token, secret, password, cookie(s) or session,
     * or an Authorization. */
    private static final String SECRET_KEY = "[A-Za-z0-9_.\\-]*(?:token|secret|password|cookies?|session|authorization)";
    /** Header values, kept to the end of the line: {@code Cookie: a=1; b=2}, {@code Authorization: Basic …}. */
    private static final Pattern HEADER = Pattern.compile(
            "(?i)((?<![\\w-])(?:set-cookie|cookie|authorization)\\s*:\\s*(?:(?:bearer|basic|digest)\\s+)?)[^\\r\\n\"]+");
    /** {@code "key":"value"}, {@code "key":{…}} or {@code "key":[…]}. */
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"" + SECRET_KEY + "\"\\s*:\\s*)(?:\"(?:[^\"\\\\]|\\\\.)*\"|\\{[^{}]*}|\\[[^\\[\\]]*])");
    /** YAML {@code key: value}, only where a YAML key starts (the line's start, a list item, a flow mapping), so a
     * sentence like "failed to get session: canceled" stays as it is. */
    private static final Pattern YAML_SECRET = Pattern.compile(
            "(?i)((?:^\\s*(?:-\\s+)?|[{,]\\s*)" + SECRET_KEY + "\\s*:[ \\t]+)(?:\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[^\\s,}]+)");
    /** Query or form parameters: {@code ?token=…}, {@code &refresh_token=…}. */
    private static final Pattern QUERY_SECRET = Pattern.compile("(?i)((?<![\\w.\\-])" + SECRET_KEY + "=)[^&\\s\"';,]+");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[^&\\s\"]+");
    private static final Pattern MXC = Pattern.compile("mxc://([^/\\s\"]+)/[A-Za-z0-9_\\-]+");
    private static final Pattern MATRIX_ID = Pattern.compile("([@!$#+])[A-Za-z0-9._=/+\\-]+:([A-Za-z0-9.\\-]+(?::\\d+)?)");
    /** Room ids from room version 12 and event ids (v3+) have no ":server" part: an opaque 20+ char hash. */
    private static final Pattern BARE_ID = Pattern.compile("(?<![A-Za-z0-9])([!$])[A-Za-z0-9_\\-+/]{20,}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    /** Phone numbers ("+57 300 111 2233") and long numeric IDs (WhatsApp JIDs, Telegram IDs): 7+ digits. Not the
     * fraction of a time ("09:51:20.1234567-05:00"): never right after a digit, '.' or ':'. */
    private static final Pattern PHONE = Pattern.compile("(?<![\\d.:])\\+?\\d[\\d ()\\-]{5,}\\d");
    /** Dates like 2026-10-26 look like phone numbers to PHONE. */
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![\\d.:\\-T])\\d{7,}(?![\\d.:])");

    private Redactor() {}

    public static String redact(String line) {
        if (line == null || line.isEmpty()) return line;
        String s = JSON_FIELD.matcher(line).replaceAll(m -> Matcher.quoteReplacement("\"" + m.group(1) + "\":\"…\""));
        s = HEADER.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "…"));
        s = JSON_SECRET.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "\"…\""));
        s = YAML_SECRET.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "…"));
        s = QUERY_SECRET.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "…"));
        s = BEARER.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "…"));
        s = MXC.matcher(s).replaceAll(m -> Matcher.quoteReplacement("mxc://" + m.group(1) + "/…"));
        s = MATRIX_ID.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "…:" + m.group(2)));
        s = BARE_ID.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "…"));
        s = EMAIL.matcher(s).replaceAll("<correo>");
        s = PHONE.matcher(s).replaceAll(m -> digits(m.group()) >= 7 && !DATE.matcher(m.group()).lookingAt()
                ? "<número>" : Matcher.quoteReplacement(m.group()));
        s = LONG_NUMBER.matcher(s).replaceAll("<número>");
        return s;
    }

    private static int digits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) n++;
        return n;
    }
}

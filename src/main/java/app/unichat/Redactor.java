package app.unichat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal data from a log line before it is written to disk: Matrix user/room/event IDs, media IDs,
 * phone numbers and other long numeric IDs, e-mail addresses, access tokens, and the values of JSON fields that
 * carry names or message text (bridges log those). What happened (levels, messages, errors, times) stays readable.
 */
public final class Redactor {
    private static final Pattern JSON_FIELD = Pattern.compile(
            "\"(name|displayname|display_name|full_name|first_name|last_name|username|user_name|push_name|pushname"
                    + "|chat_name|room_name|title|topic|body|text|caption|formatted_body|phone|phone_number|email"
                    + "|sender_name|contact_name|nickname|login_name|remote_name|description)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)(access_token=|bearer\\s+|\"(?:access_token|as_token|hs_token|token|password)\"\\s*:\\s*\")[^&\\s\"]+");
    private static final Pattern MXC = Pattern.compile("mxc://([^/\\s\"]+)/[A-Za-z0-9_\\-]+");
    private static final Pattern MATRIX_ID = Pattern.compile("([@!$#+])[A-Za-z0-9._=/+\\-]+:([A-Za-z0-9.\\-]+(?::\\d+)?)");
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
        s = TOKEN.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "…"));
        s = MXC.matcher(s).replaceAll(m -> Matcher.quoteReplacement("mxc://" + m.group(1) + "/…"));
        s = MATRIX_ID.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "…:" + m.group(2)));
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

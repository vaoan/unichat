package app.unichat.ui;

import app.unichat.support.I18n;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** Date/size formatting used across the UI, in the chosen language. Pure functions, unit-tested. */
public final class Formats {
    private Formats() {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d/M/yy");

    public static String time(Instant t, ZoneId zone) {
        return TIME.format(t.atZone(zone));
    }

    /** Chat list timestamp: "14:32", "Ayer", "lun", "3/2/26". */
    public static String listStamp(Instant t, Instant now, ZoneId zone) {
        if (t == null || t.equals(Instant.EPOCH)) return "";
        LocalDate d = t.atZone(zone).toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (d.equals(today)) return time(t, zone);
        if (d.equals(today.minusDays(1))) return I18n.t("formats.yesterday");
        if (d.isAfter(today.minusDays(7))) return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, I18n.locale()).replace(".", "");
        return SHORT_DATE.format(d);
    }

    /** Thread day separator: "Today", "Yesterday", "Monday, February 3", with year when not current. */
    public static String daySeparator(Instant t, Instant now, ZoneId zone) {
        ZonedDateTime z = t.atZone(zone);
        LocalDate d = z.toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (d.equals(today)) return I18n.t("formats.today");
        if (d.equals(today.minusDays(1))) return I18n.t("formats.yesterday");
        String pattern = I18n.t(d.getYear() == today.getYear() ? "formats.longDate" : "formats.longDateYear");
        String s = DateTimeFormatter.ofPattern(pattern, I18n.locale()).format(z);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A count on a badge. */
    public static String badge(long count) {
        return count > 99 ? "99+" : Long.toString(count);
    }

    /** "1:05", or "1:02:05" past an hour: where a video or an audio is. */
    static String clock(double seconds) {
        long s = Double.isNaN(seconds) || seconds < 0 ? 0 : (long) seconds;
        String ms = String.format(Locale.ROOT, "%02d:%02d", s / 60 % 60, s % 60);
        return s >= 3600 ? (s / 3600) + ":" + ms : ms.startsWith("0") ? ms.substring(1) : ms;
    }

    public static String fileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    /** Up to two initials for avatars: "Ana María" -> "AM", "#development" -> "D", "" -> "?". */
    public static String initials(String name) {
        if (name == null) return "?";
        String clean = name.replaceAll("[^\\p{L}\\p{N} ]", " ").trim();
        if (clean.isEmpty()) return "?";
        String[] parts = clean.split("\\s+");
        String first = parts[0].substring(0, parts[0].offsetByCodePoints(0, 1));
        if (parts.length == 1) return first.toUpperCase(I18n.locale());
        String last = parts[parts.length - 1];
        return (first + last.substring(0, last.offsetByCodePoints(0, 1))).toUpperCase(I18n.locale());
    }

    /**
     * Two characters naming one of your accounts in a tiny avatar: the last two digits of a phone number
     * ("+57 300 111 2233" → "33"), otherwise the initials ("Work" → "W", "Ana Pérez" → "AP").
     */
    public static String accountShort(String label) {
        if (label == null || label.isBlank()) return "?";
        String digits = label.replaceAll("\\D", "");
        if (label.trim().matches("[+\\d][\\d ()\\-]{5,}") && digits.length() >= 2) return digits.substring(digits.length() - 2);
        return initials(label.startsWith("@") ? label.substring(1) : label);
    }

    /** "Ana está escribiendo…", "Ana y Luis están escribiendo…", "3 personas están escribiendo…". */
    public static String typing(java.util.Collection<String> names) {
        java.util.List<String> list = java.util.List.copyOf(names);
        return switch (list.size()) {
            case 0 -> "";
            case 1 -> I18n.t("formats.typing.one", list.get(0));
            case 2 -> I18n.t("formats.typing.two", list.get(0), list.get(1));
            default -> I18n.t("formats.typing.many", list.size());
        };
    }

    /**
     * The name under a favorite: the first word, as in "Andrés", unless it's too short to say who ("La", "El"): then the
     * whole name, which the tile cuts with "…". Without a leading # or @.
     */
    static String favoriteName(String title) {
        String t = title == null ? "" : title.replaceAll("^[#@]", "").strip();
        int sp = t.indexOf(' ');
        return sp >= 4 ? t.substring(0, sp) : t;
    }
}

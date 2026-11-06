package app.unichat.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** Spanish date/size formatting used across the UI. Pure functions, unit-tested. */
public final class Formats {
    private Formats() {}

    public static final Locale ES = Locale.forLanguageTag("es");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d/M/yy");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", ES);
    private static final DateTimeFormatter LONG_DATE_YEAR = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", ES);

    public static String time(Instant t, ZoneId zone) {
        return TIME.format(t.atZone(zone));
    }

    /** Chat list timestamp: "14:32", "Ayer", "lun", "3/2/26". */
    public static String listStamp(Instant t, Instant now, ZoneId zone) {
        if (t == null || t.equals(Instant.EPOCH)) return "";
        LocalDate d = t.atZone(zone).toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (d.equals(today)) return time(t, zone);
        if (d.equals(today.minusDays(1))) return "Ayer";
        if (d.isAfter(today.minusDays(7))) return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, ES).replace(".", "");
        return SHORT_DATE.format(d);
    }

    /** Thread day separator: "Hoy", "Ayer", "Lunes, 3 de febrero", with year when not current. */
    public static String daySeparator(Instant t, Instant now, ZoneId zone) {
        ZonedDateTime z = t.atZone(zone);
        LocalDate d = z.toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (d.equals(today)) return "Hoy";
        if (d.equals(today.minusDays(1))) return "Ayer";
        String s = (d.getYear() == today.getYear() ? LONG_DATE : LONG_DATE_YEAR).format(z);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A count on a badge. */
    public static String badge(long count) {
        return count > 99 ? "99+" : Long.toString(count);
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

    /** Up to two initials for avatars: "Ana María" -> "AM", "#desarrollo" -> "D", "" -> "?". */
    public static String initials(String name) {
        if (name == null) return "?";
        String clean = name.replaceAll("[^\\p{L}\\p{N} ]", " ").trim();
        if (clean.isEmpty()) return "?";
        String[] parts = clean.split("\\s+");
        String first = parts[0].substring(0, parts[0].offsetByCodePoints(0, 1));
        if (parts.length == 1) return first.toUpperCase(ES);
        String last = parts[parts.length - 1];
        return (first + last.substring(0, last.offsetByCodePoints(0, 1))).toUpperCase(ES);
    }

    /**
     * Two characters naming one of your accounts in a tiny avatar: the last two digits of a phone number
     * ("+57 300 111 2233" → "33"), otherwise the initials ("Trabajo" → "T", "Ana Pérez" → "AP").
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
            case 1 -> list.get(0) + " está escribiendo…";
            case 2 -> list.get(0) + " y " + list.get(1) + " están escribiendo…";
            default -> list.size() + " personas están escribiendo…";
        };
    }
}

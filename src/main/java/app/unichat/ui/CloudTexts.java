package app.unichat.ui;

import app.unichat.cloud.Cloud;
import app.unichat.support.I18n;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** What the cloud is doing, in words: Ajustes → Nube says it in a line, the tray's menu in a few. Pure. */
final class CloudTexts {
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private CloudTexts() {}

    /** Ajustes → Nube's line: the step going on (with its file's progress and the speed), or how the last round went. */
    static String status(Cloud.Status s, Instant now) {
        Cloud.Activity a = s.now();
        if (a != null) return switch (a.step()) {
            case SETTINGS -> I18n.t("settings.cloud.doing.SETTINGS");
            case LEARNED -> I18n.t("settings.cloud.doing.LEARNED");
            case UPLOADING, VERIFYING -> I18n.t("settings.cloud.doing." + a.step().name(), a.done() + 1, a.total(), a.item())
                    + percentAndSpeed(a);
        };
        if (!s.connected()) return I18n.t("settings.cloud.notYet");
        String when = when(s.lastSync(), now);
        String files = I18n.n("settings.cloud.uploaded", s.uploaded()) + (s.waiting() > 0 ? " · " + I18n.n("settings.cloud.waiting", s.waiting()) : "")
                + retrying(s) + (s.conflicts() > 0 ? " · " + I18n.n("settings.cloud.conflicts", s.conflicts()) : "")
                + (s.lastVerified() != null ? " · " + I18n.t("settings.cloud.verified", when(s.lastVerified(), now)) : "");
        if (s.error() != null) return I18n.t("settings.cloud.error", s.error()) + "\n" + when + " · " + files;
        return I18n.t("settings.cloud.ok") + " · " + when + " · " + files;
    }

    /** The tray menu's lines: what it's doing and how fast, the file and how far, how many are left. None without an account. */
    static List<String> menuLines(Cloud.Status s, Instant now) {
        List<String> out = new ArrayList<>();
        Cloud.Activity a = s.now();
        if (a != null) {
            boolean files = a.step() == Cloud.Step.UPLOADING || a.step() == Cloud.Step.VERIFYING;
            out.add(I18n.t("notifier.cloud." + a.step().name(), a.done() + 1, a.total())
                    + (a.bytesPerSecond() > 0 ? " · " + Formats.fileSize(a.bytesPerSecond()) + "/s" : ""));
            if (files && a.item() != null) out.add(a.size() > 0 ? I18n.t("notifier.cloud.file", a.item(), percent(a)) : a.item());
            if (files) out.add(I18n.n("notifier.cloud.left", a.total() - a.done()) + retrying(s));
            return out;
        }
        if (!s.connected()) return out;
        out.add(s.error() != null ? I18n.t("notifier.cloud.failed", s.error()) : I18n.t("notifier.cloud.ok", when(s.lastSync(), now)));
        if (s.waiting() > 0 || s.retrying() > 0) {
            out.add((s.waiting() > 0 ? I18n.n("settings.cloud.waiting", s.waiting()) : "") + (s.waiting() > 0 ? retrying(s) : retrying(s).substring(3)));
        }
        if (s.conflicts() > 0) out.add(I18n.n("settings.cloud.conflicts", s.conflicts()));
        return out;
    }

    /** How far the round got, for the icon's ring: NaN when nothing is going up, else 0 to 1 (files done and the one going). */
    static double ring(Cloud.Status s) {
        Cloud.Activity a = s.now();
        if (a == null || (a.step() != Cloud.Step.UPLOADING && a.step() != Cloud.Step.VERIFYING) || a.total() <= 0) return Double.NaN;
        double file = a.size() > 0 ? (double) a.sent() / a.size() : 0;
        return Math.min(1, (a.done() + file) / a.total());
    }

    private static int percent(Cloud.Activity a) {
        return (int) Math.min(100, a.sent() * 100 / Math.max(1, a.size()));
    }

    private static String percentAndSpeed(Cloud.Activity a) {
        return (a.size() > 0 ? " · " + percent(a) + " %" : "") + (a.bytesPerSecond() > 0 ? " · " + Formats.fileSize(a.bytesPerSecond()) + "/s" : "");
    }

    private static String retrying(Cloud.Status s) {
        return s.retrying() > 0 && s.nextRetry() != null
                ? " · " + I18n.n("settings.cloud.retrying", s.retrying(), HOUR.format(s.nextRetry().atZone(ZoneId.systemDefault()))) : "";
    }

    private static String when(Instant at, Instant now) {
        return at == null ? I18n.t("settings.cloud.never") : I18n.t("settings.cloud.ago", Math.max(0, Duration.between(at, now).toMinutes()));
    }
}

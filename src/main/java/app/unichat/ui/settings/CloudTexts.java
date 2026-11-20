package app.unichat.ui.settings;

import app.unichat.ui.Formats;
import app.unichat.cloud.Cloud;
import app.unichat.support.I18n;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** What the cloud is doing, in words: Ajustes → Nube says it in a line, the tray's menu in a few. Pure. */
public final class CloudTexts {
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
        String vault = vaultLines(s, now).stream().map(l -> "\n" + l).collect(java.util.stream.Collectors.joining());
        if (s.error() != null) return I18n.t("settings.cloud.error", s.error()) + "\n" + when + " · " + files + vault;
        return I18n.t("settings.cloud.ok") + " · " + when + " · " + files + vault;
    }

    /**
     * The vault's lines: how it stands here (not open, gone on without, removed), what came into it, the computers still
     * sending plain copies, and what of it didn't open. None without a vault and with nothing to say.
     */
    public static List<String> vaultLines(Cloud.Status s, Instant now) {
        List<String> out = new ArrayList<>();
        if (!s.connected() || s.vault() == null) return out;
        switch (s.vault()) {
            case LOCKED -> out.add(I18n.t("settings.vault.line.LOCKED"));
            case DECLINED -> out.add(I18n.t("settings.vault.line.DECLINED"));
            case REMOVED -> out.add(I18n.t("settings.vault.line.REMOVED"));
            case UNRECOGNIZED -> out.add(I18n.t("settings.vault.line.UNRECOGNIZED"));
            case MISSING -> out.add(I18n.t("settings.vault.line.MISSING"));
            case NONE, OPEN -> { }
        }
        Cloud.Moving m = s.moving();
        if (m != null && m.files() > 0) out.add(I18n.n("settings.vault.line.moved", m.files(), when(m.at(), now)));
        if (m != null) m.strays().keySet().forEach(machine -> out.add(I18n.t("settings.vault.line.stray", computer(m.strayName(machine)))));
        Cloud.Notice n = s.vaultNotice();
        if (n != null) {
            if (!n.replaced().isEmpty()) out.add(I18n.t("settings.vault.line.replaced", parts(n.replaced())));
            if (!n.damaged().isEmpty()) out.add(I18n.t("settings.vault.line.damaged", parts(n.damaged())));
            if (!n.textsDamaged().isEmpty()) {
                out.add(I18n.t("settings.vault.line.textsDamaged",
                        String.join(", ", n.textsDamaged().values().stream().map(CloudTexts::computer).toList())));
            }
        }
        return out;
    }

    /**
     * A computer's name in the vault as shown anywhere: from the account, so one plain line of at most 64 characters
     * ({@link app.unichat.cloud.VaultView#label}); words for one that has none.
     */
    static String computer(String label) {
        String clean = app.unichat.cloud.VaultView.label(label);
        return clean.isBlank() ? I18n.t("settings.vault.unnamed") : clean;
    }

    private static String parts(List<Cloud.Part> parts) {
        return String.join(", ", parts.stream().map(p -> I18n.t("settings.vault.part." + p.name())).toList());
    }

    /** The tray menu's lines: what it's doing and how fast, the file and how far, how many are left. None without an account. */
    public static List<String> menuLines(Cloud.Status s, Instant now) {
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
        out.addAll(vaultLines(s, now));
        return out;
    }

    /** How far the round got, for the icon's ring: NaN when nothing is going up, else 0 to 1 (files done and the one going). */
    public static double ring(Cloud.Status s) {
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

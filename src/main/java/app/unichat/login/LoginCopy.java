package app.unichat.login;

import app.unichat.model.Network;
import app.unichat.support.I18n;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The human side of a bridge's login: which of the bridge's flows to offer and in what order, and translated copy for
 * the flows and steps we know. The bridge stays the source of truth: a flow or step we don't know yet is still
 * offered (under "More options") with the bridge's own text, so Unichat never falls behind. Pure logic, tested.
 */
public final class LoginCopy {
    private LoginCopy() {}

    /** A flow as shown to the user. {@code advanced}: listed under "More options". */
    public record Choice(BridgeLogin.Flow flow, String title, String subtitle, boolean advanced) {}

    /** Text for a step: a title and numbered instructions (empty when there's nothing to explain). */
    public record StepText(String title, List<String> steps) {}

    /** {@code title} and {@code subtitle} are keys in the language files. */
    private record Known(String title, String subtitle, int order) {}

    /** Curated flows per network; anything not listed here goes under "More options". */
    private static final Map<Network, Map<String, Known>> FLOWS = Map.of(
            Network.WHATSAPP, Map.of(
                    "qr", new Known("login.flow.qr.title", "login.flow.whatsapp.qr.subtitle", 0),
                    "phone", new Known("login.flow.phone.title", "login.flow.whatsapp.phone.subtitle", 1)),
            Network.TELEGRAM, Map.of(
                    "qr", new Known("login.flow.qr.title", "login.flow.telegram.qr.subtitle", 0),
                    "phone", new Known("login.flow.phone.title", "login.flow.telegram.phone.subtitle", 1)),
            Network.SIGNAL, Map.of(
                    "qr", new Known("login.flow.qr.title", "login.flow.signal.qr.subtitle", 0)),
            Network.GOOGLE_MESSAGES, Map.of(
                    "qr", new Known("login.flow.qr.title", "login.flow.googleMessages.qr.subtitle", 0),
                    "google", new Known("login.flow.googleMessages.google.title", "login.flow.googleMessages.google.subtitle", 1)),
            Network.INSTAGRAM, Map.of(
                    "instagram", new Known("login.flow.instagram.instagram.title", "login.flow.instagram.instagram.subtitle", 0)),
            Network.MESSENGER, Map.of(
                    "facebook", new Known("login.flow.messenger.facebook.title", "login.flow.messenger.facebook.subtitle", 0),
                    "messenger", new Known("login.flow.messenger.messenger.title", "login.flow.messenger.messenger.subtitle", 1)),
            Network.X, Map.of(
                    "cookies", new Known("login.flow.x.cookies.title", "login.flow.x.cookies.subtitle", 0),
                    "password", new Known("login.flow.x.password.title", "login.flow.x.password.subtitle", 1)),
            Network.LINKEDIN, Map.of(
                    "cookies", new Known("login.flow.linkedin.cookies.title", "login.flow.linkedin.cookies.subtitle", 0)));

    /** Flows that exist for developers or edge cases and confuse everyone else: never offered. */
    private static final Map<Network, Set<String>> HIDDEN = Map.of(
            Network.TELEGRAM, Set.of("bot", "manual"),
            // One mautrix-meta bridge serves both apps: each network only shows its own flows.
            Network.INSTAGRAM, Set.of("facebook", "messenger", "messenger-lite"),
            Network.MESSENGER, Set.of("instagram", "messenger-lite"));

    /** The bridge's flows to offer, curated ones first in our order, unknown ones last as advanced. */
    public static List<Choice> choices(Network n, List<BridgeLogin.Flow> flows) {
        Map<String, Known> known = FLOWS.getOrDefault(n, Map.of());
        Set<String> hidden = HIDDEN.getOrDefault(n, Set.of());
        List<Choice> out = new ArrayList<>();
        for (BridgeLogin.Flow f : flows) {
            if (hidden.contains(f.id())) continue;
            Known k = known.get(f.id());
            if (k != null) out.add(new Choice(f, I18n.t(k.title()), I18n.t(k.subtitle()), false));
            else out.add(new Choice(f, f.name().isBlank() ? f.id() : f.name(), f.description(), true));
        }
        out.sort(Comparator.comparing(Choice::advanced)
                .thenComparingInt(c -> c.advanced() ? 0 : known.get(c.flow().id()).order()));
        return out;
    }

    /** Translated title and steps for a step we know; otherwise the bridge's instructions. */
    public static StepText step(Network n, BridgeLogin.Step s) {
        String net = n.displayName();
        String id = s.stepId() == null ? "" : s.stepId();
        if (s.display() != null && "qr".equals(s.display().type())) {
            String where = switch (n) {
                case WHATSAPP -> I18n.t("login.step.qr.whatsapp");
                case TELEGRAM -> I18n.t("login.step.qr.telegram");
                case SIGNAL -> I18n.t("login.step.qr.signal");
                case GOOGLE_MESSAGES -> I18n.t("login.step.qr.googleMessages");
                default -> I18n.t("login.step.qr.other");
            };
            return new StepText(I18n.t("login.step.qr.title"),
                    List.of(I18n.t("login.step.openOnPhone", net), where, I18n.t("login.step.qr.aim")));
        }
        if (s.display() != null && "code".equals(s.display().type())) {
            return new StepText(I18n.t("login.step.code.title"), n == Network.WHATSAPP
                    ? List.of(I18n.t("login.step.code.whatsapp.open"), I18n.t("login.step.code.whatsapp.tap"),
                    I18n.t("login.step.code.type"))
                    : List.of(I18n.t("login.step.openOnPhone", net), I18n.t("login.step.code.type")));
        }
        if (s.display() != null && "emoji".equals(s.display().type())) {
            return new StepText(I18n.t("login.step.emoji.title"), List.of(I18n.t("login.step.openOnPhone", net), I18n.t("login.step.emoji.tap")));
        }
        if (id.endsWith(".history_pin")) {
            // It comes back with the bridge's reason first when the last attempt failed.
            String[] parts = s.instructions().split("\n\n", 2);
            List<String> lines = new java.util.ArrayList<>();
            if (parts.length == 2) lines.add(parts[0]);
            lines.add(I18n.t("login.step.historyPin.use"));
            lines.add(I18n.t("login.step.historyPin.skip"));
            return new StepText(I18n.t("login.step.historyPin.title"), lines);
        }
        if (id.endsWith(".juicebox_pin")) {
            // X's encrypted chats ("X Chat") are protected with a 4-digit PIN the user chooses.
            boolean create = s.instructions().contains("No PIN code is registered");
            return create
                    ? new StepText(I18n.t("login.step.juicebox.create.title"), List.of(
                    I18n.t("login.step.juicebox.create.protect"),
                    I18n.t("login.step.juicebox.create.choose")))
                    : new StepText(I18n.t("login.step.juicebox.enter.title"), List.of(I18n.t("login.step.juicebox.enter.help")));
        }
        if (id.endsWith(".phone") || id.endsWith(".phone_number")) {
            return new StepText(I18n.t("login.step.phone.title", net), List.of(I18n.t("login.step.phone.help")));
        }
        if (id.endsWith(".code") || id.endsWith(".code.incorrect")) {
            return new StepText(id.endsWith(".incorrect") ? I18n.t("login.step.code.incorrect") : I18n.t("login.step.code.enter"),
                    List.of(n == Network.TELEGRAM ? I18n.t("login.step.code.telegramHelp") : I18n.t("login.step.code.checkPhone")));
        }
        if (id.endsWith(".password") || id.endsWith(".password.incorrect")) {
            return new StepText(id.endsWith(".incorrect") ? I18n.t("login.step.password.incorrect") : I18n.t("login.step.password.title"),
                    List.of(I18n.t("login.step.password.help", net)));
        }
        if ("cookies".equals(s.type())) {
            return new StepText(I18n.t("login.step.cookies.title", net), List.of());
        }
        if (s.isComplete()) return new StepText(I18n.t("login.step.connected", net), List.of());
        return new StepText(net, s.instructions().isBlank() ? List.of() : List.of(s.instructions()));
    }

    /** The digits a pattern like {@code ^[0-9]{6}$} asks for; 0 when it doesn't say. */
    public static int digits(String pattern) {
        java.util.regex.Matcher m = pattern == null ? null : java.util.regex.Pattern.compile("\\{(\\d{1,2})}").matcher(pattern);
        return m != null && m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Translated label for an input field, by its type (the bridge's name when we don't know it). */
    public static String fieldLabel(BridgeLogin.Field f) {
        if ("pin".equals(f.id())) {
            // How long depends on the network (X asks for 4, Messenger for 6): the bridge's pattern says.
            int digits = digits(f.pattern());
            return digits > 0 ? I18n.t("login.field.pinDigits", digits) : I18n.t("login.field.pin");
        }
        return switch (f.type()) {
            case "phone_number" -> I18n.t("login.field.phone");
            case "2fa_code" -> I18n.t("login.field.code");
            case "password" -> I18n.t("login.field.password");
            case "email" -> I18n.t("login.field.email");
            case "username" -> I18n.t("login.field.username");
            case "token" -> I18n.t("login.field.token");
            case "url" -> I18n.t("login.field.url");
            case "domain" -> I18n.t("login.field.domain");
            case "captcha_code" -> I18n.t("login.field.captcha");
            default -> f.name().isBlank() ? f.id() : f.name();
        };
    }
}

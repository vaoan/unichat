package app.unichat.login;

import app.unichat.model.Network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The human side of a bridge's login: which of the bridge's flows to offer and in what order, and Spanish copy for
 * the flows and steps we know. The bridge stays the source of truth: a flow or step we don't know yet is still
 * offered (under "Más opciones") with the bridge's own text, so Unichat never falls behind. Pure logic, tested.
 */
public final class LoginCopy {
    private LoginCopy() {}

    /** A flow as shown to the user. {@code advanced}: listed under "Más opciones". */
    public record Choice(BridgeLogin.Flow flow, String title, String subtitle, boolean advanced) {}

    /** Text for a step: a title and numbered instructions (empty when there's nothing to explain). */
    public record StepText(String title, List<String> steps) {}

    private record Known(String title, String subtitle, int order) {}

    /** Curated flows per network; anything not listed here goes under "Más opciones". */
    private static final Map<Network, Map<String, Known>> FLOWS = Map.of(
            Network.WHATSAPP, Map.of(
                    "qr", new Known("Escanear un código QR", "Desde WhatsApp en tu teléfono, en Dispositivos vinculados.", 0),
                    "phone", new Known("Usar tu número de teléfono", "Te damos un código para escribir en WhatsApp. Útil si no puedes escanear.", 1)),
            Network.TELEGRAM, Map.of(
                    "qr", new Known("Escanear un código QR", "Desde Telegram en tu teléfono, en Dispositivos.", 0),
                    "phone", new Known("Usar tu número de teléfono", "Telegram te envía un código para confirmar.", 1)),
            Network.SIGNAL, Map.of(
                    "qr", new Known("Escanear un código QR", "Desde Signal en tu teléfono, en Dispositivos vinculados.", 0)),
            Network.GOOGLE_MESSAGES, Map.of(
                    "qr", new Known("Escanear un código QR", "Desde Mensajes de Google en tu teléfono Android.", 0),
                    "google", new Known("Con tu cuenta de Google", "Inicias sesión en Google y confirmas tocando un emoji en el teléfono.", 1)),
            Network.INSTAGRAM, Map.of(
                    "instagram", new Known("Iniciar sesión en Instagram", "Usamos tu sesión del navegador o abrimos Instagram aquí mismo.", 0)),
            Network.MESSENGER, Map.of(
                    "facebook", new Known("Iniciar sesión con Facebook", "Usamos tu sesión del navegador o abrimos Facebook aquí mismo.", 0),
                    "messenger", new Known("Iniciar sesión en Messenger", "Con tu cuenta de messenger.com.", 1)),
            Network.X, Map.of(
                    "cookies", new Known("Iniciar sesión en X", "Usamos tu sesión del navegador o abrimos X aquí mismo.", 0),
                    "password", new Known("Con usuario y contraseña", "Tu usuario, correo o teléfono de X y tu contraseña.", 1)),
            Network.LINKEDIN, Map.of(
                    "cookies", new Known("Iniciar sesión en LinkedIn", "Usamos tu sesión del navegador o abrimos LinkedIn aquí mismo.", 0)));

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
            if (k != null) out.add(new Choice(f, k.title(), k.subtitle(), false));
            else out.add(new Choice(f, f.name().isBlank() ? f.id() : f.name(), f.description(), true));
        }
        out.sort(Comparator.comparing(Choice::advanced)
                .thenComparingInt(c -> c.advanced() ? 0 : known.get(c.flow().id()).order()));
        return out;
    }

    /** Spanish title and steps for a step we know; otherwise the bridge's instructions. */
    public static StepText step(Network n, BridgeLogin.Step s) {
        String net = n.displayName();
        String id = s.stepId() == null ? "" : s.stepId();
        if (s.display() != null && "qr".equals(s.display().type())) {
            String where = switch (n) {
                case WHATSAPP -> "Toca Más opciones (⋮) o Ajustes → Dispositivos vinculados → Vincular un dispositivo";
                case TELEGRAM -> "Ve a Ajustes → Dispositivos → Vincular dispositivo de escritorio";
                case SIGNAL -> "Ve a Ajustes → Dispositivos vinculados → toca +";
                case GOOGLE_MESSAGES -> "Toca tu foto de perfil → Vinculación de dispositivos → Escáner de código QR";
                default -> "Busca la opción para vincular un dispositivo";
            };
            return new StepText("Escanea el código con tu teléfono",
                    List.of("Abre " + net + " en tu teléfono", where, "Apunta la cámara a este código"));
        }
        if (s.display() != null && "code".equals(s.display().type())) {
            return new StepText("Escribe este código en tu teléfono", n == Network.WHATSAPP
                    ? List.of("Abre WhatsApp en tu teléfono", "Toca el aviso de vincular dispositivo, o ve a Dispositivos vinculados → Vincular con el número de teléfono",
                    "Escribe el código que ves aquí")
                    : List.of("Abre " + net + " en tu teléfono", "Escribe el código que ves aquí"));
        }
        if (s.display() != null && "emoji".equals(s.display().type())) {
            return new StepText("Toca este emoji en tu teléfono", List.of("Abre " + net + " en tu teléfono", "Toca el emoji que coincide con este"));
        }
        if (id.endsWith(".history_pin")) {
            // It comes back with the bridge's reason first when the last attempt failed.
            String[] parts = s.instructions().split("\n\n", 2);
            List<String> lines = new java.util.ArrayList<>();
            if (parts.length == 2) lines.add(parts[0]);
            lines.add("Si usas el almacenamiento seguro de Messenger, escribe su PIN de 6 dígitos para recuperar tus chats cifrados anteriores.");
            lines.add("Si no lo tienes, déjalo vacío y pulsa Continuar: te conectas igual, sin ese historial.");
            return new StepText("Recuperar tu historial cifrado", lines);
        }
        if (id.endsWith(".juicebox_pin")) {
            // X's encrypted chats ("X Chat") are protected with a 4-digit PIN the user chooses.
            boolean create = s.instructions().contains("No PIN code is registered");
            return create
                    ? new StepText("Crea tu PIN de X Chat", List.of(
                    "X protege tus chats cifrados con un PIN de 4 dígitos.",
                    "Inventa uno que vayas a recordar: X te lo pedirá cuando abras tus chats en otro dispositivo."))
                    : new StepText("Escribe tu PIN de X Chat", List.of("Es el PIN de 4 dígitos que creaste para tus chats cifrados de X."));
        }
        if (id.endsWith(".phone") || id.endsWith(".phone_number")) {
            return new StepText("¿Cuál es tu número de " + net + "?", List.of("Incluye el código de país, por ejemplo +57 para Colombia."));
        }
        if (id.endsWith(".code") || id.endsWith(".code.incorrect")) {
            return new StepText(id.endsWith(".incorrect") ? "El código no es correcto" : "Escribe el código que te llegó",
                    List.of(n == Network.TELEGRAM ? "Telegram te lo envió como mensaje en tu teléfono (o por SMS)." : "Revisa tu teléfono."));
        }
        if (id.endsWith(".password") || id.endsWith(".password.incorrect")) {
            return new StepText(id.endsWith(".incorrect") ? "La contraseña no es correcta" : "Tu cuenta tiene verificación en dos pasos",
                    List.of("Escribe la contraseña de verificación en dos pasos de " + net + "."));
        }
        if ("cookies".equals(s.type())) {
            return new StepText("Inicia sesión en " + net, List.of());
        }
        if (s.isComplete()) return new StepText(net + " está conectado", List.of());
        return new StepText(net, s.instructions().isBlank() ? List.of() : List.of(s.instructions()));
    }

    /** The digits a pattern like {@code ^[0-9]{6}$} asks for; 0 when it doesn't say. */
    public static int digits(String pattern) {
        java.util.regex.Matcher m = pattern == null ? null : java.util.regex.Pattern.compile("\\{(\\d{1,2})}").matcher(pattern);
        return m != null && m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Spanish label for an input field, by its type (the bridge's name when we don't know it). */
    public static String fieldLabel(BridgeLogin.Field f) {
        if ("pin".equals(f.id())) {
            // How long depends on the network (X asks for 4, Messenger for 6): the bridge's pattern says.
            int digits = digits(f.pattern());
            return digits > 0 ? "PIN de " + digits + " dígitos" : "PIN";
        }
        return switch (f.type()) {
            case "phone_number" -> "Número de teléfono";
            case "2fa_code" -> "Código";
            case "password" -> "Contraseña";
            case "email" -> "Correo";
            case "username" -> "Usuario";
            case "token" -> "Token";
            case "url" -> "Dirección (URL)";
            case "domain" -> "Dominio";
            case "captcha_code" -> "Código de la imagen";
            default -> f.name().isBlank() ? f.id() : f.name();
        };
    }
}

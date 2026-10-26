package app.unichat.model;

import java.util.Locale;

/**
 * Chat networks Unichat can show. Each bridged network maps to a mautrix bridge whose
 * bot localpart and puppet prefix are set in server/ (see docs/BRIDGES.md).
 */
public enum Network {
    WHATSAPP("WhatsApp", "#25D366", "whatsappbot", "whatsapp_", "whatsapp"),
    TELEGRAM("Telegram", "#2AABEE", "telegrambot", "telegram_", "telegram"),
    SIGNAL("Signal", "#3A76F0", "signalbot", "signal_", "signal"),
    INSTAGRAM("Instagram", "#E1306C", "instagrambot", "instagram_", "instagram"),
    MESSENGER("Messenger", "#0084FF", "facebookbot", "facebook_", "facebook"),
    DISCORD("Discord", "#5865F2", "discordbot", "discord_", "discord"),
    SLACK("Slack", "#611F69", "slackbot", "slack_", "slack"),
    GOOGLE_MESSAGES("Google Messages", "#1A73E8", "gmessagesbot", "gmessages_", "gmessages"),
    X("X", "#14171A", "twitterbot", "twitter_", "twitter"),
    LINKEDIN("LinkedIn", "#0A66C2", "linkedinbot", "linkedin_", "linkedin"),
    MATRIX("Matrix", "#0DBD8B", null, null, "matrix");

    private final String displayName;
    private final String color;
    private final String botLocalpart;
    private final String puppetPrefix;
    private final String protocolId;

    Network(String displayName, String color, String botLocalpart, String puppetPrefix, String protocolId) {
        this.displayName = displayName;
        this.color = color;
        this.botLocalpart = botLocalpart;
        this.puppetPrefix = puppetPrefix;
        this.protocolId = protocolId;
    }

    public String displayName() { return displayName; }
    /** Brand color as a CSS hex string. */
    public String color() { return color; }
    /** Localpart of the bridge bot (e.g. {@code whatsappbot}); null for native Matrix. */
    public String botLocalpart() { return botLocalpart; }
    /** Localpart prefix of puppeted remote users (e.g. {@code whatsapp_}); null for native Matrix. */
    public String puppetPrefix() { return puppetPrefix; }
    /** Protocol id used in {@code m.bridge} state events. */
    public String protocolId() { return protocolId; }
    public boolean isBridged() { return botLocalpart != null; }

    /** Initial letter shown in the small network badge. */
    public String badgeLetter() { return displayName.substring(0, 1).toUpperCase(Locale.ROOT); }

    public static Network fromProtocolId(String id) {
        if (id == null) return null;
        String p = id.toLowerCase(Locale.ROOT);
        for (Network n : values()) if (n.protocolId.equals(p)) return n;
        return switch (p) {
            case "messenger", "meta" -> MESSENGER;
            case "googlemessages", "gmessage", "sms" -> GOOGLE_MESSAGES;
            case "x", "twitterdm" -> X;
            default -> null;
        };
    }

    /** Guesses the network from a Matrix user id like {@code @whatsapp_5730000:example.org}. */
    public static Network fromUserId(String userId) {
        if (userId == null || !userId.startsWith("@")) return null;
        String local = userId.substring(1, userId.indexOf(':') > 0 ? userId.indexOf(':') : userId.length());
        for (Network n : values()) {
            if (!n.isBridged()) continue;
            if (local.equals(n.botLocalpart) || local.startsWith(n.puppetPrefix)) return n;
        }
        return null;
    }
}

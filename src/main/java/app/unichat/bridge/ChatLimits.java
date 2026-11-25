package app.unichat.bridge;

import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * How many chats the person has asked each bridge for so far ("Load older chats"), as kept in the settings of this
 * computer. {@code server} writes it into each bridge's config; {@code matrix} raises it one step at a time.
 *
 * @param telegramExtra  chats asked of Telegram above its first {@link BridgePorts#TELEGRAM_CREATE_LIMIT}
 * @param discordExtra   private chats asked of Discord above its first {@link #DISCORD_BASE}
 * @param linkedinAll    LinkedIn brings every chat (kept)
 * @param gmessagesOnce  Google Messages asks the phone for {@link #GMESSAGES_ALL} chats at its next start only
 * @param instagramOnce  Instagram walks {@link #INSTAGRAM_PAGES} inbox pages at its next start only
 */
public record ChatLimits(int telegramExtra, int discordExtra, boolean linkedinAll,
                         boolean gmessagesOnce, boolean instagramOnce) {
    public static final ChatLimits NONE = new ChatLimits(0, 0, false, false, false);
    public static final int TELEGRAM_STEP = 100;
    public static final int DISCORD_STEP = 50;
    public static final int DISCORD_BASE = 50;
    public static final int GMESSAGES_ALL = 500;
    public static final int INSTAGRAM_PAGES = 3;

    public static final String TELEGRAM_EXTRA = "olderChats.telegram.extra";
    public static final String DISCORD_EXTRA = "olderChats.discord.extra";
    public static final String LINKEDIN_ALL = "olderChats.linkedin.all";
    public static final String GMESSAGES_ONCE = "olderChats.gmessages.once";
    public static final String INSTAGRAM_ONCE = "olderChats.instagram.once";

    /** What {@code get} (a settings key, its value or null) holds; a value unreadable or negative is that field's default. */
    public static ChatLimits parse(Function<String, @Nullable String> get) {
        return new ChatLimits(count(get.apply(TELEGRAM_EXTRA)), count(get.apply(DISCORD_EXTRA)), flag(get.apply(LINKEDIN_ALL)),
                flag(get.apply(GMESSAGES_ONCE)), flag(get.apply(INSTAGRAM_ONCE)));
    }

    /** Telegram's {@code create_limit} and {@code update_limit}. */
    public int telegramLimit() {
        return BridgePorts.TELEGRAM_CREATE_LIMIT + telegramExtra;
    }

    /** Discord's {@code startup_private_channel_create_limit}. */
    public int discordLimit() {
        return DISCORD_BASE + discordExtra;
    }

    private static int count(@Nullable String v) {
        if (v == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(v.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean flag(@Nullable String v) {
        return v != null && v.strip().equals("true");
    }
}

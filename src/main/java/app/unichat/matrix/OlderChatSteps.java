package app.unichat.matrix;

import app.unichat.bridge.ChatLimits;
import app.unichat.model.Network;
import app.unichat.service.OlderChats.Answer;
import app.unichat.service.OlderChats.Plan;
import app.unichat.support.I18n;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What a click on "Load older chats" would do for each network (the spec's table), read from what was asked so far.
 * Pure: it only reads the settings, never writes them, so building a plan runs nothing.
 */
final class OlderChatSteps {
    /** How a step runs: raise a limit and restart the bridge (then a command, or not), or a command alone. */
    enum Kind { RAISE_RESTART_COMMAND, COMMAND, RAISE_RESTART, NOTHING }

    /** One network's next step: how to run it, the setting it writes (key → value), the command if any, its Plan. */
    record Step(Network network, Kind kind, Map<String, String> settings, @Nullable String command, Duration commandPatience,
                Plan plan) {}

    /** The next step of a network, or the answer when there's none. */
    sealed interface Next {
        record Run(Step step) implements Next {}
        record Done(Answer answer) implements Next {}
    }

    /** The order the networks run in, whatever order they're in view. */
    static final List<Network> ORDER = List.of(Network.TELEGRAM, Network.WHATSAPP, Network.DISCORD, Network.LINKEDIN,
            Network.GOOGLE_MESSAGES, Network.INSTAGRAM);
    /** Discord's pause between steps: a restart resubscribes to every server (the 4008 loop). */
    static final Duration DISCORD_PAUSE = Duration.ofMinutes(10);
    static final Duration TELEGRAM_PATIENCE = Duration.ofMinutes(10);
    static final Duration COMMAND_PATIENCE = Duration.ofMinutes(2);
    static final int WHATSAPP_HISTORY_CHATS = 100;

    static final String WHATSAPP_GROUPS = "olderChats.whatsapp.groups";
    static final String DISCORD_AT = "olderChats.discord.at";

    private final Function<String, @Nullable String> settings;
    private final Supplier<? extends Set<Network>> withAccounts;
    private final Supplier<Instant> now;

    /** settings: Settings.get()::getString in production, a map in tests; accounts: networks with a login; now: the clock. */
    OlderChatSteps(Function<String, @Nullable String> settings, Set<Network> withAccounts, Supplier<Instant> now) {
        this(settings, () -> withAccounts, now);
    }

    /** As above, with the networks that have a login read again each time (they change as the person logs in or out). */
    OlderChatSteps(Function<String, @Nullable String> settings, Supplier<? extends Set<Network>> withAccounts, Supplier<Instant> now) {
        this.settings = settings;
        this.withAccounts = withAccounts;
        this.now = now;
    }

    /** The steps' clock: a step's start, against which a loaded room's newest message is news or history. */
    Instant now() {
        return now.get();
    }

    /** What a new login on {@code n} forgets: "nothing more" was the old account's, and so were WhatsApp's groups. */
    static List<String> keptPerLogin(Network n) {
        return n == Network.WHATSAPP ? List.of(doneKey(n), WHATSAPP_GROUPS) : List.of(doneKey(n));
    }

    /** The key that keeps {@code n}'s login ids as last seen, sorted and joined by commas. */
    static String loginKey(Network n) {
        return "olderChats." + n.protocolId() + ".login";
    }

    /** The key that says {@code n} has nothing more to load. */
    static String doneKey(Network n) {
        return "olderChats." + n.protocolId() + ".done";
    }

    /** The next step of n, or the Answer when there's none (NothingMore, NotPossible). */
    Next next(Network n) {
        if (!withAccounts.get().contains(n)) return new Next.Done(new Answer.NotPossible(I18n.t("olderChats.noAccount", n.displayName())));
        Step s = step(n);
        if (s == null) return new Next.Done(new Answer.NothingMore());
        Duration wait = s.plan().waitFirst();
        if (wait != null) return new Next.Done(new Answer.NotPossible(waitText(wait)));
        return new Next.Run(s);
    }

    /** What a click would do for these networks, in the order they run; one with nothing to do is left out. */
    List<Plan> plans(Collection<Network> inView) {
        List<Plan> out = new ArrayList<>();
        Set<Network> accounts = withAccounts.get();
        for (Network n : inViewOrder(inView)) {
            if (!accounts.contains(n)) continue;
            Step s = step(n);
            if (s != null) out.add(s.plan());
        }
        return out;
    }

    /** Whether any of these networks has something left to load (Discord in its pause too: later it will). */
    boolean hasMore(Collection<Network> inView) {
        return !plans(inView).isEmpty();
    }

    List<Network> inViewOrder(Collection<Network> inView) {
        return ORDER.stream().filter(inView::contains).toList();
    }

    /** Whether {@code s} is the network's last (LinkedIn, Google Messages, Instagram: one step each). */
    static boolean onlyOnce(Step s) {
        return s.network() == Network.LINKEDIN || s.network() == Network.GOOGLE_MESSAGES || s.network() == Network.INSTAGRAM;
    }

    /** n's step as the settings stand, its pause included; null when it has nothing more. */
    private @Nullable Step step(Network n) {
        if (flag(doneKey(n))) return null;
        ChatLimits asked = ChatLimits.parse(settings);
        return switch (n) {
            case TELEGRAM -> new Step(n, Kind.RAISE_RESTART_COMMAND,
                    Map.of(ChatLimits.TELEGRAM_EXTRA, Integer.toString(asked.telegramExtra() + ChatLimits.TELEGRAM_STEP)),
                    "sync-chats", TELEGRAM_PATIENCE, new Plan(n, ChatLimits.TELEGRAM_STEP, true, false, false, null));
            case WHATSAPP -> "done".equals(settings.apply(WHATSAPP_GROUPS))
                    ? new Step(n, Kind.COMMAND, Map.of(), "sync-history-chats " + WHATSAPP_HISTORY_CHATS, COMMAND_PATIENCE,
                            new Plan(n, WHATSAPP_HISTORY_CHATS, false, false, false, null))
                    : new Step(n, Kind.COMMAND, Map.of(WHATSAPP_GROUPS, "done"), "sync groups", COMMAND_PATIENCE,
                            new Plan(n, -1, false, false, false, null));
            case DISCORD -> new Step(n, Kind.RAISE_RESTART,
                    Map.of(ChatLimits.DISCORD_EXTRA, Integer.toString(asked.discordExtra() + ChatLimits.DISCORD_STEP),
                            DISCORD_AT, now.get().toString()),
                    null, COMMAND_PATIENCE, new Plan(n, ChatLimits.DISCORD_STEP, true, true, false, discordWait()));
            case LINKEDIN -> restartOnly(n, ChatLimits.LINKEDIN_ALL, false);
            case GOOGLE_MESSAGES -> restartOnly(n, ChatLimits.GMESSAGES_ONCE, false);
            case INSTAGRAM -> restartOnly(n, ChatLimits.INSTAGRAM_ONCE, true);
            // Facebook and X brought every chat they can list; Signal, Slack and Matrix have no list to ask for.
            default -> null;
        };
    }

    private static Step restartOnly(Network n, String key, boolean risky) {
        return new Step(n, Kind.RAISE_RESTART, Map.of(key, "true"), null, COMMAND_PATIENCE, new Plan(n, -1, true, false, risky, null));
    }

    /** What's left of Discord's pause, null when there's none. */
    private @Nullable Duration discordWait() {
        String at = settings.apply(DISCORD_AT);
        if (at == null) return null;
        try {
            Duration left = Duration.between(now.get(), Instant.parse(at.strip()).plus(DISCORD_PAUSE));
            return left.isPositive() ? left : null;
        } catch (DateTimeParseException e) {
            return null; // unreadable: no pause, as if never asked
        }
    }

    private boolean flag(String key) {
        String v = settings.apply(key);
        return v != null && v.strip().equals("true");
    }

    private static String waitText(Duration wait) {
        long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        return I18n.n("olderChats.wait", minutes);
    }
}

package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Network;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Which chats the inbox shows and in what order. Immutable; pure logic (unit-tested).
 *
 * <p>Views: INBOX hides archived and low-priority chats (low priority has its own section at the end),
 * ARCHIVE shows only archived ones. Bridge-bot management chats only appear when searching.
 */
public record ChatFilter(View view, Network network, String accountId, boolean unreadOnly, String query) {
    public enum View { INBOX, LOW_PRIORITY, ARCHIVE, FAVORITES }

    public static final ChatFilter DEFAULT = new ChatFilter(View.INBOX, null, null, false, "");

    public ChatFilter withView(View v) { return new ChatFilter(v, network, accountId, unreadOnly, query); }
    /** Filters by network (all its accounts). */
    public ChatFilter withNetwork(Network n) { return new ChatFilter(view, n, null, unreadOnly, query); }
    /** Filters by one account of a network; null id = all accounts of that network. */
    public ChatFilter withAccount(Network n, String id) { return new ChatFilter(view, n, id, unreadOnly, query); }
    public ChatFilter withUnreadOnly(boolean u) { return new ChatFilter(view, network, accountId, u, query); }
    public ChatFilter withQuery(String q) { return new ChatFilter(view, network, accountId, unreadOnly, q == null ? "" : q); }

    public boolean searching() { return query != null && !query.isBlank(); }

    public boolean matches(Chat c) {
        if (network != null && c.network() != network) return false;
        if (accountId != null && !accountId.equals(c.accountId())) return false;
        if (searching()) return normalize(c.title()).contains(normalize(query))
                || c.members().stream().anyMatch(m -> normalize(m.displayName()).contains(normalize(query)));
        if (c.bridgeBot()) return false;
        if (unreadOnly && !c.isUnread()) return false;
        return switch (view) {
            case INBOX -> !c.archived() && !c.lowPriority();
            case LOW_PRIORITY -> !c.archived() && c.lowPriority();
            case ARCHIVE -> c.archived();
            case FAVORITES -> c.favorite() && !c.archived();
        };
    }

    /** Pinned first, then most recent activity. */
    public static final Comparator<Chat> ORDER = Comparator.comparing(Chat::pinned).reversed()
            .thenComparing(Chat::lastActivity, Comparator.reverseOrder())
            .thenComparing(Chat::title);

    public List<Chat> apply(List<Chat> chats) {
        return chats.stream().filter(this::matches).sorted(ORDER).toList();
    }

    /** Lower-case, accent-insensitive form for search. */
    static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}

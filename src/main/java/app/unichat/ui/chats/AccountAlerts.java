package app.unichat.ui.chats;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.support.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * The accounts that aren't working, said plainly for the strip above the chat list: one line per network, the
 * worst account first. Pure, tested.
 */
public final class AccountAlerts {
    private AccountAlerts() {}

    /**
     * @param who    the network, and the account when it has several
     * @param text   the whole explanation (tooltip, screen readers)
     * @param relink only a new login fixes it (button shown); otherwise Unichat is recovering it by itself
     */
    /** {@code syncing}: the account is reconnecting to its network, so its chats are still catching up (top bar). */
    public record Alert(Network network, String who, String text, boolean relink, boolean syncing) {}

    /**
     * @param inUse networks with chats: a closed session only matters there (a bridge installed but never signed
     *              in to also reads as "needs login", and that's not news)
     */
    public static List<Alert> of(List<Account> accounts, java.util.Set<Network> inUse) {
        List<Alert> out = new ArrayList<>();
        for (Network n : Network.values()) {
            List<Account> mine = accounts.stream()
                    .filter(a -> a.network() == n && (a.status() != AccountStatus.NEEDS_LOGIN || inUse.contains(n))).toList();
            boolean many = mine.size() > 1;
            Account worst = null;
            for (Account a : mine) if (rank(a.status()) > (worst == null ? 0 : rank(worst.status()))) worst = a;
            if (worst == null) continue;
            String who = n.displayName() + (many ? " (" + worst.label() + ")" : "");
            switch (worst.status()) {
                case NEEDS_LOGIN -> out.add(new Alert(n, who, I18n.t("alerts.needsLogin", who), true, false));
                case ERROR -> out.add(new Alert(n, who, I18n.t("alerts.error", who), false, false));
                case CONNECTING -> out.add(new Alert(n, who, I18n.t("alerts.offline", who), false, true));
                default -> { }
            }
        }
        out.sort((a, b) -> Boolean.compare(b.relink(), a.relink()));
        return out;
    }

    /** One short line for everything that's fixing itself: its size mustn't grow with the number of networks. */
    static String recoveringLine(List<Alert> recovering) {
        return line(recovering, "alerts.reconnecting");
    }

    /** "Sincronizando Telegram y Discord…" while those reconnect, for the top bar; null when every network is up to date. */
    public static String syncingLine(List<Alert> alerts) {
        List<Alert> syncing = alerts.stream().filter(Alert::syncing).toList();
        return syncing.isEmpty() ? null : line(syncing, "main.syncingNetworks");
    }

    private static String line(List<Alert> alerts, String key) {
        List<String> names = alerts.stream().map(Alert::who).toList();
        return switch (names.size()) {
            case 1 -> I18n.t(key + ".one", names.get(0));
            case 2 -> I18n.t(key + ".two", names.get(0), names.get(1));
            default -> I18n.t(key + ".many", names.size());
        };
    }

    /** How bad, for picking the account a network's line talks about; 0 = fine. */
    private static int rank(AccountStatus s) {
        return switch (s) {
            case NEEDS_LOGIN -> 3;
            case ERROR -> 2;
            case CONNECTING -> 1;
            default -> 0;
        };
    }
}

package app.unichat.ui;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;

import java.util.ArrayList;
import java.util.List;

/**
 * The accounts that aren't working, said plainly for the strip above the chat list: one line per network, the
 * worst account first. Pure, tested.
 */
final class AccountAlerts {
    private AccountAlerts() {}

    /** {@code relink}: only a new login fixes it (button shown); otherwise Unichat is recovering it by itself. */
    record Alert(Network network, String text, boolean relink) {}

    /**
     * @param inUse networks with chats: a closed session only matters there (a bridge installed but never signed
     *              in to also reads as "needs login", and that's not news)
     */
    static List<Alert> of(List<Account> accounts, java.util.Set<Network> inUse) {
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
                case NEEDS_LOGIN -> out.add(new Alert(n, who + ": se cerró la sesión. No llegan mensajes hasta que la vuelvas a vincular.", true));
                case ERROR -> out.add(new Alert(n, who + ": tiene errores. Unichat lo está reiniciando…", false));
                case CONNECTING -> out.add(new Alert(n, who + ": sin conexión. Reconectando solo…", false));
                default -> { }
            }
        }
        out.sort((a, b) -> Boolean.compare(b.relink(), a.relink()));
        return out;
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

package app.unichat.ui;

import app.unichat.service.NetworkAccounts;
import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.Network;
import app.unichat.settings.Settings;

import java.util.List;

/** Display helpers for networks with several accounts. */
final class Accounts {
    private Accounts() {}

    /** The user's alias for the account ("Trabajo"), else the bridge label (phone, username). */
    static String name(Account a) {
        String alias = Settings.get().accountAlias(a.id());
        return alias != null ? alias : a.label();
    }

    /** Connected (or connecting) accounts of a network, excluding logins still waiting for the user. */
    static List<Account> of(NetworkAccounts svc, Network n) {
        return of(svc.accounts(), n);
    }

    /** Same, from an accounts list already fetched (computing it walks every room). */
    static List<Account> of(List<Account> accounts, Network n) {
        return accounts.stream().filter(a -> a.network() == n && a.status() != AccountStatus.NEEDS_LOGIN).toList();
    }

    /** Name of the account a chat belongs to, only when its network has several accounts; else null. */
    static String forChat(NetworkAccounts svc, Chat c) {
        return c.accountId() == null ? null : forChat(svc.accounts(), c);
    }

    /** Same, from an accounts list already fetched (list cells: computing it walks every room). */
    static String forChat(List<Account> accounts, Chat c) {
        if (c.accountId() == null) return null;
        List<Account> list = of(accounts, c.network());
        if (list.size() < 2) return null;
        return list.stream().filter(a -> a.id().equals(c.accountId())).findFirst().map(Accounts::name).orElse(null);
    }
}

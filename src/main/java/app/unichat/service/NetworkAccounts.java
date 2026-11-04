package app.unichat.service;

import app.unichat.model.Account;
import app.unichat.model.Network;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Networks and the accounts connected to them. */
public interface NetworkAccounts {
    /** Connected network accounts, one per bridge login. */
    List<Account> accounts();
    /** Networks the backend can connect (bridges installed on the server). */
    List<Network> availableNetworks();
    /**
     * Opens (or creates) the management chat with the network's bridge bot and starts its login
     * flow; completes with that chat's id. The UI shows the chat so the user can follow the
     * bot's instructions (QR code, phone number, token...).
     */
    CompletableFuture<String> connectNetwork(Network network);
    CompletableFuture<Void> disconnectNetwork(Network network);
    /**
     * Starts the login flow for one more account on a network that may already have one; completes
     * with the bridge-bot chat id. Bridges that support several logins add a new one.
     */
    default CompletableFuture<String> addAccount(Network network) {
        return connectNetwork(network);
    }
    /** Logs out one specific account; the default logs out the whole network. */
    default CompletableFuture<Void> disconnectAccount(Account account) {
        return disconnectNetwork(account.network());
    }
    /** Asks a bridge to list your chats again (Telegram: chats beyond the login-time limit); the bridge's answer. */
    default CompletableFuture<String> syncChats(Network network) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("syncChats"));
    }
    /** Aborts an unfinished login started with {@link #addAccount}/{@link #connectNetwork}. */
    default CompletableFuture<Void> cancelLogin(Network network, String botChatId) {
        return CompletableFuture.completedFuture(null);
    }
}

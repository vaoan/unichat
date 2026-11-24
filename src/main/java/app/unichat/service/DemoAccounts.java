package app.unichat.service;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import app.unichat.support.I18n;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The demo's accounts and its pretend bridge bots: signing in (a QR "scanned" a few seconds later), out, and the bot's
 * answers to the person's commands. The accounts themselves are {@link MockChatService#accounts}, under its lock.
 */
final class DemoAccounts {
    private final MockChatService svc;

    DemoAccounts(MockChatService svc) {
        this.svc = svc;
    }

    List<Account> list() {
        List<Account> out;
        synchronized (svc.accounts) {
            out = new ArrayList<>(svc.accounts.values());
        }
        // Snapshots of the "not working" strip: -Dunichat.demoAccounts=WHATSAPP:NEEDS_LOGIN,DISCORD:CONNECTING
        String broken = System.getProperty("unichat.demoAccounts", "");
        for (String part : broken.split(",")) {
            String[] nv = part.split(":");
            if (nv.length != 2) continue;
            Network n = Network.valueOf(nv[0].strip());
            AccountStatus st = AccountStatus.valueOf(nv[1].strip());
            out.replaceAll(acc -> acc.network() == n ? new Account(acc.id(), n, acc.label(), st, null) : acc);
        }
        return out;
    }

    CompletableFuture<String> connect(Network network) {
        boolean hasAccount;
        synchronized (svc.accounts) {
            hasAccount = svc.accounts.values().stream().anyMatch(a -> a.network() == network);
        }
        return hasAccount ? CompletableFuture.completedFuture(botChat(network).id()) : add(network);
    }

    CompletableFuture<String> add(Network network) {
        Chat chat = botChat(network);
        synchronized (svc.accounts) {
            svc.accounts.put(pendingId(network), new Account(pendingId(network), network, I18n.t("demo.newAccount"), AccountStatus.NEEDS_LOGIN, null));
        }
        answer(chat, "login");
        svc.fireAccounts();
        return CompletableFuture.completedFuture(chat.id());
    }

    CompletableFuture<Void> disconnect(Network network) {
        synchronized (svc.accounts) {
            svc.accounts.values().removeIf(a -> a.network() == network);
        }
        svc.chats.values().removeIf(c -> c.network() == network);
        svc.fireAccounts();
        svc.fireChats();
        return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> disconnect(Account account) {
        boolean last;
        synchronized (svc.accounts) {
            svc.accounts.remove(account.id());
            last = svc.accounts.values().stream().noneMatch(a -> a.network() == account.network());
        }
        svc.chats.values().removeIf(c -> account.id().equals(c.accountId()) || (last && c.network() == account.network() && !c.bridgeBot()));
        svc.fireAccounts();
        svc.fireChats();
        return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> cancelLogin(Network network, String botChatId) {
        synchronized (svc.accounts) {
            svc.accounts.remove(pendingId(network));
        }
        Chat chat = svc.chats.get(botChatId);
        if (chat != null) svc.incoming(chat, botMember(network), "Login cancelled.", false);
        svc.fireAccounts();
        return CompletableFuture.completedFuture(null);
    }

    private static String pendingId(Network n) {
        return n.protocolId() + ":pending";
    }

    Chat botChat(Network network) {
        String id = "!bot-" + network.name().toLowerCase(java.util.Locale.ROOT) + ":demo";
        return svc.chats.computeIfAbsent(id, k -> {
            Chat bot = new Chat(k);
            bot.setNetwork(network);
            bot.setTitle(network.displayName() + " bridge bot");
            bot.setBridgeBot(true);
            bot.setMembers(List.of(botMember(network)));
            svc.adopt(bot);
            svc.messages.put(k, new ArrayList<>());
            return bot;
        });
    }

    Member botMember(Network n) {
        return new Member("@" + n.botLocalpart() + ":demo.unichat", n.displayName() + " bridge bot", null);
    }

    @SuppressFBWarnings(value = "IMPROPER_UNICODE", justification = "the demo's pretend bridge bot, answering the person's own commands")
    void answer(Chat chat, String command) {
        Network n = chat.network();
        Member bot = botMember(n);
        var _ = svc.scheduler().schedule(() -> {
            if (command.equalsIgnoreCase("login")) {
                Message qr = new Message(UUID.randomUUID().toString(), chat.id(), bot.id(), bot.name(), false, Instant.now());
                qr.setType(MessageType.IMAGE);
                qr.setMediaUrl("demo://" + I18n.t("demo.image.qr") + "-" + n.protocolId());
                qr.setFileName("qr.png");
                qr.setSize(640, 420);
                svc.append(chat, qr);
                svc.incoming(chat, bot, "Scan the QR code with the " + n.displayName() + " mobile app to log in.", false);
                var _ = svc.scheduler().schedule(() -> answer(chat, "ok"), 6, TimeUnit.SECONDS); // demo: simulated scan
            } else if (command.equalsIgnoreCase("ok")) {
                svc.incoming(chat, bot, "Successfully logged in as demo_" + n.protocolId(), false);
                synchronized (svc.accounts) {
                    svc.accounts.remove(pendingId(n));
                    long k = svc.accounts.values().stream().filter(a -> a.network() == n).count() + 1;
                    String id = n.protocolId() + ":demo" + k;
                    svc.accounts.put(id, new Account(id, n, "demo_" + n.protocolId() + "_" + k, AccountStatus.CONNECTED, null));
                }
                svc.fireAccounts();
            } else if (command.equalsIgnoreCase("logout")) {
                svc.incoming(chat, bot, I18n.t("demo.loggedOut"), false);
                synchronized (svc.accounts) {
                    svc.accounts.values().removeIf(a -> a.network() == n);
                }
                svc.fireAccounts();
            } else {
                svc.incoming(chat, bot, I18n.t("demo.unknownCommand"), false);
            }
        }, 900, TimeUnit.MILLISECONDS);
    }
}

package app.unichat.matrix;

import app.unichat.login.BridgeContacts;
import app.unichat.model.Account;
import app.unichat.model.Contact;
import app.unichat.service.ChatService.ContactSupport;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static app.unichat.matrix.BridgeProtocol.*;

/** Contacts and new chats through the bridges' own API. */
final class ContactActions {
    private final MatrixCore core;
    private final String userId;

    ContactActions(MatrixCore core) {
        this.core = core;
        this.userId = core.userId;
    }

    /** Accounts whose bridge answered that it has no user search. */
    private final Set<String> noSearch = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The bridge's contacts API for {@code a}, or null when there's no bridge API (external server). */
    private BridgeContacts bridgeContacts(Account a) {
        BridgeProvisioner p = BridgeAccounts.provisioner;
        String url = p == null ? null : p.provisioningUrl(a.network());
        return url == null ? null : new BridgeContacts(url, core.accessToken, userId, loginIdOf(a));
    }

    CompletableFuture<ContactSupport> contactSupport(Account a) {
        return core.call(() -> {
            BridgeContacts b = bridgeContacts(a);
            if (b == null) return new ContactSupport(false, false);
            // Search support is learnt from the user's own first search (never probed with a made-up query).
            return new ContactSupport(b.list().isPresent(), !noSearch.contains(a.id()));
        });
    }

    CompletableFuture<List<Contact>> contacts(Account a) {
        return core.call(() -> {
            BridgeContacts b = bridgeContacts(a);
            return b == null ? List.of() : b.list().orElse(List.of());
        });
    }

    CompletableFuture<List<Contact>> searchContacts(Account a, String query) {
        return core.call(() -> {
            BridgeContacts b = bridgeContacts(a);
            if (b == null) throw new UnsupportedOperationException("search");
            java.util.Optional<List<Contact>> r = b.search(query);
            if (r.isEmpty()) {
                noSearch.add(a.id());
                throw new UnsupportedOperationException("search");
            }
            return r.get();
        });
    }

    CompletableFuture<Contact> resolveContact(Account a, String identifier) {
        return core.call(() -> {
            BridgeContacts b = bridgeContacts(a);
            return b == null ? null : b.resolve(identifier);
        });
    }

    CompletableFuture<String> openDm(Account a, Contact contact) {
        if (contact.dmRoomId() != null) return CompletableFuture.completedFuture(contact.dmRoomId());
        return core.call(() -> {
            BridgeContacts b = bridgeContacts(a);
            if (b == null) throw new IllegalStateException("Esta red no permite abrir chats nuevos desde Unichat");
            return b.createDm(contact.id());
        });
    }
}

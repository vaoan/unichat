package app.unichat.service;

import app.unichat.model.Account;
import app.unichat.model.Contact;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Finding people and opening new chats with them. */
public interface ContactDirectory {
    default CompletableFuture<ChatService.ContactSupport> contactSupport(Account account) {
        return CompletableFuture.completedFuture(new ChatService.ContactSupport(false, false));
    }
    default CompletableFuture<List<Contact>> contacts(Account account) {
        return CompletableFuture.completedFuture(List.of());
    }
    /** Fails with UnsupportedOperationException when the network has no user search. */
    default CompletableFuture<List<Contact>> searchContacts(Account account, String query) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("search"));
    }
    /** A phone number, @username…; completes with null when nobody matches. */
    default CompletableFuture<Contact> resolveContact(Account account, String identifier) {
        return CompletableFuture.completedFuture(null);
    }
    /** The room of the DM with {@code contact}, creating it when there's none yet. */
    default CompletableFuture<String> openDm(Account account, Contact contact) {
        return contact.dmRoomId() != null ? CompletableFuture.completedFuture(contact.dmRoomId())
                : CompletableFuture.failedFuture(new UnsupportedOperationException("openDm"));
    }
}

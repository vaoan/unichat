package app.unichat.service;

import app.unichat.model.Account;
import app.unichat.model.Contact;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Finding people and opening new chats with them. */
public interface ContactDirectory {
    CompletableFuture<ChatService.ContactSupport> contactSupport(Account account);
    CompletableFuture<List<Contact>> contacts(Account account);
    /** Fails with UnsupportedOperationException when the network has no user search. */
    CompletableFuture<List<Contact>> searchContacts(Account account, String query);
    /** A phone number, @username…; completes with null when nobody matches. */
    CompletableFuture<Contact> resolveContact(Account account, String identifier);
    /** The room of the DM with {@code contact}, creating it when there's none yet. */
    CompletableFuture<String> openDm(Account account, Contact contact);
}

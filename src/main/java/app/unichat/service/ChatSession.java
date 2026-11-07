package app.unichat.service;

import app.unichat.model.ConnectionState;
import java.util.concurrent.CompletableFuture;

/** Starting and stopping, who you are, and hearing about changes. */
public interface ChatSession {
    void addListener(ChatService.Listener listener);
    void removeListener(ChatService.Listener listener);
    /** Starts syncing. Safe to call once; data appears through listener callbacks. */
    void start();
    /** Stops background work and saves what's pending, waiting for the disk (app exit, end of a session). Does not log out. */
    void stop();
    /** Ends the session on the server and forgets stored credentials. */
    CompletableFuture<Void> logout();
    ConnectionState connectionState();
    String myUserId();
    String myDisplayName();
}

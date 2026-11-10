package app.unichat.matrix;

import app.unichat.service.ChatService.Listener;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;

/** Names and pictures: mine, and those of chat counterparts the sync left out (lazy-loaded members). */
final class Profiles {
    private final MatrixCore core;
    /** Users whose profile was already requested (lazy-loaded members have no display name yet). */
    private final Set<String> requested = ConcurrentHashMap.newKeySet();
    /** A few at a time: a first sync can name hundreds, and the server takes a burst as abuse. */
    private final Semaphore slots = new Semaphore(6);
    private volatile String myName;

    Profiles(MatrixCore core) {
        this.core = core;
        this.myName = SyncEvents.localpart(core.userId);
    }

    /** My display name; my localpart until the server says otherwise. */
    String myName() {
        return myName;
    }

    /** Asks the server for my display name, keeping the localpart when it has none or doesn't answer. */
    void loadMine() {
        try {
            String dn = core.client.get(CLIENT + "/profile/" + enc(core.userId) + "/displayname").path("displayname").asText(null);
            if (dn != null && !dn.isBlank()) {
                myName = dn;
                core.fire(Listener::accountsChanged);
            }
        } catch (Exception ignored) {
            // keep localpart
        }
    }

    /** Fetches display names/avatars of chat counterparts the sync didn't include, each once. */
    void fetchMissing() {
        Set<String> users;
        synchronized (core.proc) {
            users = core.proc.unnamedUsers();
        }
        for (String u : users) {
            if (requested.add(u)) core.io.execute(() -> fetch(u));
        }
    }

    private void fetch(String user) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            requested.remove(user);
            return;
        }
        try {
            JsonNode p = core.client.get(CLIENT + "/profile/" + enc(user));
            String name = p.path("displayname").asText(null);
            String avatar = p.path("avatar_url").asText(null);
            if (name == null && avatar == null) return;
            List<String> changed;
            synchronized (core.proc) {
                changed = core.proc.applyProfile(user, name, avatar);
            }
            if (!changed.isEmpty()) core.fire(Listener::chatsChanged);
        } catch (Exception ignored) {
            // keep the fallback name
        } finally {
            slots.release();
        }
    }
}

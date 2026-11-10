package app.unichat.cloud;

import app.unichat.support.I18n;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * What signing in through the browser needs on this computer, for any service: a server on 127.0.0.1 only, at the
 * fixed port the services have registered ({@link #PORT}), a random {@code state} that ties the answer to the request,
 * and reading and answering the browser.
 */
final class Loopback {
    /** Fixed: a service only sends the browser back to the address registered with its app. */
    static final int PORT = 43117;
    /** How long a sign-in waits for the browser: one left there gives the port back by itself. */
    static final Duration DEADLINE = Duration.ofMinutes(10);

    private Loopback() {}

    static HttpServer server(int port) throws IOException {
        return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    }

    /**
     * Listens until {@code result} ends (answered, declined, cancelled), or until {@code deadline} if the person leaves the
     * browser: then it fails, and the port is free again before anyone hears it (the next "Connect" found it busy until
     * a restart).
     */
    static void until(HttpServer server, CompletableFuture<?> result, Duration deadline) {
        result.whenComplete((a, e) -> server.stop(0));
        CompletableFuture.delayedExecutor(deadline.toNanos(), TimeUnit.NANOSECONDS).execute(() -> {
            if (result.isDone()) return;
            server.stop(0);
            result.completeExceptionally(new IOException(I18n.t("cloud.signIn.expired")));
        });
    }

    /** Whether the {@code state} that came back is ours, in constant time: how long it takes says nothing of ours. */
    static boolean ours(String state, String back) {
        return back != null && MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), back.getBytes(StandardCharsets.UTF_8));
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    static void reply(HttpExchange x, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(code, b.length);
        try (var out = x.getResponseBody()) {
            out.write(b);
        }
    }

    /** A page the browser shows when it's over: plain words, nothing else. */
    static String page(String text) {
        return "<!doctype html><meta charset=\"utf-8\"><title>Unichat</title>"
                + "<body style=\"font-family: system-ui, sans-serif; padding: 3em; text-align: center\"><p>"
                + text.replace("&", "&amp;").replace("<", "&lt;") + "</p>";
    }
}

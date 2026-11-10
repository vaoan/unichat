package app.unichat.cloud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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

    private Loopback() {}

    static HttpServer server(int port) throws IOException {
        return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
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

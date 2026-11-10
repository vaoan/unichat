package app.unichat.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** "Does the process on this port answer?", asked of its {@code /_matrix/client/versions} (tuwunel and every bridge serve it). */
final class HttpProbe {
    /** What counts as up. */
    enum Up {
        /** Ready to be used: only a 200 (tuwunel answers other codes while it starts). */
        READY,
        /** Alive: any answer at all (a hung process gives none). */
        ALIVE
    }

    private HttpProbe() {}

    /**
     * Whether the process on {@code port} answers within {@code wait} the way {@code up} asks. No answer, a refused
     * connection or any other failure is "no"; an interrupted wait is neither, and is thrown.
     */
    static boolean answers(HttpClient http, int port, Duration wait, Up up) throws InterruptedException {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/_matrix/client/versions"))
                    .timeout(wait).build(), HttpResponse.BodyHandlers.discarding());
            return up == Up.ALIVE ? r.statusCode() > 0 : r.statusCode() == 200;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}

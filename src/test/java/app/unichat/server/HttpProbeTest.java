package app.unichat.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpProbeTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration NO_LIMIT = Duration.ofDays(1);

    /** A server whose {@code /_matrix/client/versions} answers {@code status}. */
    private static HttpServer answering(int status) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/_matrix/client/versions", x -> {
            x.sendResponseHeaders(status, -1);
            x.close();
        });
        s.start();
        return s;
    }

    @Test
    void readyNeedsA200AliveAnyAnswer() throws Exception {
        HttpServer ok = answering(200), starting = answering(503);
        try {
            assertTrue(HttpProbe.answers(HTTP, ok.getAddress().getPort(), NO_LIMIT, HttpProbe.Up.READY));
            assertTrue(HttpProbe.answers(HTTP, ok.getAddress().getPort(), NO_LIMIT, HttpProbe.Up.ALIVE));
            assertFalse(HttpProbe.answers(HTTP, starting.getAddress().getPort(), NO_LIMIT, HttpProbe.Up.READY), "still starting");
            assertTrue(HttpProbe.answers(HTTP, starting.getAddress().getPort(), NO_LIMIT, HttpProbe.Up.ALIVE), "but alive");
        } finally {
            ok.stop(0);
            starting.stop(0);
        }
    }

    @Test
    void nothingListeningIsDown() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        assertFalse(HttpProbe.answers(HTTP, port, NO_LIMIT, HttpProbe.Up.ALIVE));
    }

    @Test
    void aHungProcessIsDown() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            assertFalse(HttpProbe.answers(HTTP, silent.getLocalPort(), Duration.ofNanos(1), HttpProbe.Up.ALIVE));
        }
    }

    @Test
    void anInterruptedProbeIsNeitherUpNorDown() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedException.class,
                        () -> HttpProbe.answers(HTTP, silent.getLocalPort(), NO_LIMIT, HttpProbe.Up.ALIVE));
            } finally {
                Thread.interrupted();
            }
        }
    }
}

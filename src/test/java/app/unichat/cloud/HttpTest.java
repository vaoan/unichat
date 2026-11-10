package app.unichat.cloud;

import app.unichat.support.I18n;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void aServerThatNeverAnswersInTimeSaysSo() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/"))
                    .timeout(Duration.ofNanos(1)).build();
            IOException e = assertThrows(IOException.class, () -> Http.send(CLIENT, request, HttpResponse.BodyHandlers.discarding()));
            assertEquals(I18n.t("cloud.error.noAnswer"), e.getMessage());
        }
    }

    @Test
    void anInterruptedWaitFailsAndKeepsTheInterrupt() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/")).build();
            Thread.currentThread().interrupt();
            try {
                IOException e = assertThrows(IOException.class, () -> Http.send(CLIENT, request, HttpResponse.BodyHandlers.discarding()));
                assertInstanceOf(InterruptedException.class, e.getCause());
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept for whoever runs this thread");
            } finally {
                Thread.interrupted();
            }
        }
    }
}

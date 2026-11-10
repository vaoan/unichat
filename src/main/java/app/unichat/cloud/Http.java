package app.unichat.cloud;

import app.unichat.support.I18n;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

/** Sending a request to a storage service, its failures said the same way whichever service it is. */
final class Http {
    private Http() {}

    /**
     * {@code client.send}, where a server that never answered in time is {@code cloud.error.noAnswer} and an interrupted
     * wait an {@link IOException} that keeps the thread's interrupt.
     */
    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return client.send(request, handler);
        } catch (HttpTimeoutException e) {
            throw new IOException(I18n.t("cloud.error.noAnswer"), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}

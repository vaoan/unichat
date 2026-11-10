package app.unichat.cloud;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import java.util.function.LongConsumer;

/** An upload's body that tells how many bytes have gone out so far, for its speed and how far it got. */
final class CountingBody implements HttpRequest.BodyPublisher {
    private final HttpRequest.BodyPublisher inner;
    private final LongConsumer sent;

    private CountingBody(HttpRequest.BodyPublisher inner, LongConsumer sent) {
        this.inner = inner;
        this.sent = sent;
    }

    /** {@code inner}, telling {@code sent} the running total of bytes handed to the network. */
    static HttpRequest.BodyPublisher of(HttpRequest.BodyPublisher inner, LongConsumer sent) {
        return sent == null ? inner : new CountingBody(inner, sent);
    }

    @Override public long contentLength() {
        return inner.contentLength();
    }

    @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> s) {
        long[] total = {0};
        inner.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription sub) { s.onSubscribe(sub); }

            @Override public void onNext(ByteBuffer b) {
                total[0] += b.remaining();
                s.onNext(b);
                sent.accept(total[0]);
            }

            @Override public void onError(Throwable t) { s.onError(t); }

            @Override public void onComplete() { s.onComplete(); }
        });
    }
}

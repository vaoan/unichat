package app.unichat.matrix;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import java.util.function.DoubleConsumer;

/** A request body that says how much of itself has gone out, for the upload bar. */
final class UploadProgress {
    private UploadProgress() {
    }

    /** {@code inner} as it is, telling {@code progress} the share handed to the connection (0 to 1) as it goes. */
    static HttpRequest.BodyPublisher counting(HttpRequest.BodyPublisher inner, DoubleConsumer progress) {
        long total = inner.contentLength();
        return new HttpRequest.BodyPublisher() {
            @Override public long contentLength() {
                return total;
            }

            @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> s) {
                inner.subscribe(new Flow.Subscriber<ByteBuffer>() {
                    private long sent; // per subscription: a retried request starts again from zero

                    @Override public void onSubscribe(Flow.Subscription sub) {
                        s.onSubscribe(sub);
                    }

                    @Override public void onNext(ByteBuffer b) {
                        sent += b.remaining(); // before passing it on: the connection consumes it
                        if (total > 0) progress.accept(Math.min(1, (double) sent / total));
                        s.onNext(b);
                    }

                    @Override public void onError(Throwable t) {
                        s.onError(t);
                    }

                    @Override public void onComplete() {
                        s.onComplete();
                    }
                });
            }
        };
    }
}

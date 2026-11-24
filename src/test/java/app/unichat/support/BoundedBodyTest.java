package app.unichat.support;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Each way the deadline can meet a read, made to happen in that order: the deadline's timer is one thread, held on a
 * first body's close while a second body's read starts, so the second's deadline comes only once its read is waiting.
 */
class BoundedBodyTest {
    private static final String TOO_SLOW = "Tardó demasiado en llegar";
    private static final Duration DAY = Duration.ofDays(1);

    @Test
    void aBodyWithinItsLimitsReadsWhole() throws IOException {
        try (BoundedBody body = new BoundedBody(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, DAY)) {
            assertEquals(1, body.read());
            assertEquals(1, body.skip(1));
            assertEquals(0, body.skip(0));
            assertArrayEquals(new byte[] {3}, body.readAllBytes());
            assertEquals(-1, body.read());
        }
    }

    @Test
    void oneByteOverTheLimitFails() throws IOException {
        try (BoundedBody body = new BoundedBody(new ByteArrayInputStream(new byte[4]), 3, DAY)) {
            assertEquals("Demasiado grande", assertThrows(IOException.class, body::readAllBytes).getMessage());
        }
    }

    @Test
    void aReadAfterTheDeadlineFailsAtOnce() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        Gate gate = new Gate(closed, null);
        try (BoundedBody body = new BoundedBody(gate, 10, Duration.ZERO)) {
            closed.await();
            assertEquals(TOO_SLOW, assertThrows(IOException.class, body::read).getMessage());
        }
        assertEquals(0, gate.reads, "the stream isn't read once the deadline has passed");
    }

    @Test
    void aReadWaitingWhenTheDeadlineClosesTheBodyFailsAsTooSlow() throws Exception {
        IOException e = whileReading(new IOException("closed"));
        assertEquals(TOO_SLOW, e.getMessage());
        assertInstanceOf(IOException.class, e.getCause(), "the stream's own error is its cause");
    }

    @Test
    void aReadThatEndsAsTheDeadlineComesFailsAsTooSlow() throws Exception {
        assertEquals(TOO_SLOW, whileReading(null).getMessage());
    }

    /** What a read gets when the deadline comes while it waits; the stream then throws {@code thrown} or, if null, gives a byte. */
    private static IOException whileReading(IOException thrown) throws IOException, InterruptedException {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch timerHeld = new CountDownLatch(1);
        // Its deadline at once: the timer thread runs its close and stays there until hold.
        try (BoundedBody _ = new BoundedBody(new InputStream() {
            @Override public int read() {
                return -1;
            }

            @Override public void close() throws IOException {
                timerHeld.countDown();
                try {
                    hold.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        }, 10, Duration.ZERO)) {
            timerHeld.await();
            Gate gate = new Gate(new CountDownLatch(1), thrown);
            gate.onRead = hold::countDown;
            try (BoundedBody second = new BoundedBody(gate, 10, Duration.ZERO)) {
                return assertThrows(IOException.class, second::read);
            }
        }
    }

    /** A stream whose read waits until it's closed, then throws {@code thrown} or gives a byte. */
    private static final class Gate extends InputStream {
        private final CountDownLatch closed;
        private final IOException thrown;
        private Runnable onRead = () -> { };
        private int reads;

        Gate(CountDownLatch closed, IOException thrown) {
            this.closed = closed;
            this.thrown = thrown;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0];
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            reads++;
            onRead.run();
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (thrown != null) throw thrown;
            b[off] = 7;
            return 1;
        }

        @Override public void close() {
            closed.countDown();
        }
    }
}

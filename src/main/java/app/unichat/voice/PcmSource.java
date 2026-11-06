package app.unichat.voice;

import java.io.IOException;

/** Where sound comes from: 16-bit mono samples. The microphone, or a test's own samples. */
public interface PcmSource extends AutoCloseable {
    int rate();

    /** Blocks until there are some; how many, or -1 at the end. */
    int read(short[] into) throws IOException;

    @Override
    void close();
}

package app.unichat.support;

import java.util.concurrent.ThreadFactory;

/** Background threads that never keep the app from exiting. */
public final class Threads {
    private Threads() {}

    public static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}

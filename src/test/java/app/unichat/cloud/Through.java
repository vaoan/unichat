package app.unichat.cloud;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** A storage passing everything on, for a test to override one call. */
class Through implements Storage {
    private final Storage to;

    Through(Storage to) {
        this.to = to;
    }

    @Override public void check() throws IOException { to.check(); }
    @Override public boolean exists(String path) throws IOException { return to.exists(path); }
    @Override public @Nullable String stamp(String path) throws IOException { return to.stamp(path); }
    @Override public void mkdirs(String dir) throws IOException { to.mkdirs(dir); }
    @Override public List<String> list(String dir) throws IOException { return to.list(dir); }
    @Override public void delete(String path) throws IOException { to.delete(path); }
    @Override public void move(String from, String target) throws IOException { this.to.move(from, target); }
    @Override public void put(String path, Path file) throws IOException { to.put(path, file); }
    @Override public void put(String path, byte[] data) throws IOException { to.put(path, data); }
    @Override public byte @Nullable [] get(String path) throws IOException { return to.get(path); }
    @Override public boolean get(String path, Path target) throws IOException { return to.get(path, target); }
}

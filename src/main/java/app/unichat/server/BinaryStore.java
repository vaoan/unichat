package app.unichat.server;

import app.unichat.support.Downloads;
import app.unichat.support.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Function;

/**
 * The {@code bin/} folder of the native server: one executable per bridge plus {@code <name>.version}. Bridges are
 * downloaded from their release and installed only if they match the SHA-256 the app ships pinned for them
 * ({@link Manifest#sha256}): a release's own sha256sums.txt would vouch for whatever replaced the binary there.
 */
public final class BinaryStore {
    private final Path bin;
    private final Platform platform;
    private final Function<Manifest.Component, String> releaseBase;
    private final Function<Manifest.Component, String> pinned;

    /** Downloads from {@code manifest}'s releases, checked against its pinned hashes. */
    public BinaryStore(Path bin, Platform platform, Manifest manifest) {
        this(bin, platform, c -> manifest.releaseUrl(c, platform), c -> manifest.sha256(c, platform));
    }

    /** {@code releaseBase}: where a component's files are; {@code pinned}: its SHA-256, null when not pinned (tests). */
    BinaryStore(Path bin, Platform platform, Function<Manifest.Component, String> releaseBase,
                Function<Manifest.Component, String> pinned) {
        this.bin = bin;
        this.platform = platform;
        this.releaseBase = releaseBase;
        this.pinned = pinned;
    }

    public Path path(String name) {
        return bin.resolve(name + platform.exeSuffix());
    }

    public boolean isInstalled(Manifest.Component c) {
        return Files.isExecutable(path(c.name())) && c.tag().equals(installedVersion(c.name()));
    }

    /** Downloads and installs {@code c} unless that version is already there. Blocking. */
    public Path install(Manifest.Component c) throws IOException, InterruptedException {
        Path target = path(c.name());
        if (isInstalled(c)) return target;
        Files.createDirectories(bin);
        String asset = c.asset(platform), want = pinned.apply(c);
        if (want == null) throw new IOException(asset + " " + c.tag() + " has no pinned SHA-256 (native/pin-hashes.sh)");

        Downloads.fetch(releaseBase.apply(c) + "/" + asset, 0, want, target, asset, p -> { }, part -> part.toFile().setExecutable(true, true));
        clearQuarantine(target);
        Files.writeString(bin.resolve(c.name() + ".version"), c.tag());
        return target;
    }

    /** Copies the homeserver shipped inside the app when it's missing or another version. */
    public Path installBundled(String name, Path source, String version) throws IOException {
        Path target = path(name);
        if (Files.isExecutable(target) && version.equals(installedVersion(name))) return target;
        Files.createDirectories(bin);
        Path part = bin.resolve(target.getFileName() + ".part");
        Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING);
        part.toFile().setExecutable(true, true);
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(bin.resolve(name + ".version"), version);
        return target;
    }

    private String installedVersion(String name) {
        try {
            return Files.readString(bin.resolve(name + ".version")).strip();
        } catch (IOException e) {
            return null;
        }
    }

    /** Downloaded files carry macOS's quarantine flag, which can stop them from running. */
    private void clearQuarantine(Path file) {
        if (platform != Platform.MAC_ARM64) return;
        try {
            Processes.quiet(List.of("xattr", "-c", file.toString()), null, 30);
        } catch (IOException e) {
            // Not fatal: the binary may still run.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

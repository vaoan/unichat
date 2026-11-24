package app.unichat.server;

import app.unichat.support.Downloads;
import app.unichat.support.Processes;
import app.unichat.support.SafeFile;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final Function<Manifest.Component, @Nullable String> releaseBase;
    private final Function<Manifest.Component, @Nullable String> pinned;
    private final Function<Manifest.Component, String> version;

    /** Downloads from {@code manifest}'s releases, checked against its pinned hashes. */
    public BinaryStore(Path bin, Platform platform, Manifest manifest) {
        this(bin, platform, c -> manifest.releaseUrl(c, platform), c -> manifest.sha256(c, platform),
                c -> manifest.version(c, platform));
    }

    /** {@code releaseBase}: where a component's files are; {@code pinned}: its SHA-256, null when not pinned (tests). */
    BinaryStore(Path bin, Platform platform, Function<Manifest.Component, @Nullable String> releaseBase,
                Function<Manifest.Component, @Nullable String> pinned) {
        this(bin, platform, releaseBase, pinned, Manifest.Component::tag);
    }

    /** {@code version}: what an installed component is recorded as ({@link Manifest#version}). */
    BinaryStore(Path bin, Platform platform, Function<Manifest.Component, @Nullable String> releaseBase,
                Function<Manifest.Component, @Nullable String> pinned, Function<Manifest.Component, String> version) {
        this.bin = bin;
        this.platform = platform;
        this.releaseBase = releaseBase;
        this.version = version;
        this.pinned = pinned;
    }

    public Path path(String name) {
        return bin.resolve(name + platform.exeSuffix());
    }

    public boolean isInstalled(Manifest.Component c) {
        return Files.isExecutable(path(c.name())) && version.apply(c).equals(installedVersion(c.name()));
    }

    /** Downloads and installs {@code c} unless that version is already there. Blocking. */
    public Path install(Manifest.Component c) throws IOException, InterruptedException {
        Path target = path(c.name());
        if (isInstalled(c)) return target;
        Files.createDirectories(bin);
        String asset = c.asset(platform);
        String want = pinned.apply(c);
        if (want == null) throw new IOException(asset + " " + c.tag() + " has no pinned SHA-256 (native/pin-hashes.sh)");

        Downloads.fetch(releaseBase.apply(c) + "/" + asset, 0, want, target, asset, p -> { }, part -> part.toFile().setExecutable(true, true));
        clearQuarantine(target);
        Files.writeString(bin.resolve(c.name() + ".version"), version.apply(c));
        return target;
    }

    /** Copies the homeserver shipped inside the app when it's missing or another version. */
    public Path installBundled(String name, Path source, String bundledVersion) throws IOException {
        Path target = path(name);
        if (Files.isExecutable(target) && bundledVersion.equals(installedVersion(name))) return target;
        Files.createDirectories(bin);
        try (var in = Files.newInputStream(source)) {
            SafeFile.at(target, ".part").beforeMove(part -> part.toFile().setExecutable(true, true)).copy(in);
        }
        Files.writeString(bin.resolve(name + ".version"), bundledVersion);
        return target;
    }

    private @Nullable String installedVersion(String name) {
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

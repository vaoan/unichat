package app.unichat.server;

import app.unichat.support.Processes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * The {@code bin/} folder of the native server: one executable per bridge plus {@code <name>.version}. Bridges are
 * downloaded from their release and installed only if they match the release's sha256sums.txt.
 */
public final class BinaryStore {
    private final Path bin;
    private final Platform platform;
    private final Function<Manifest.Component, String> releaseBase;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public BinaryStore(Path bin, Platform platform) {
        this(bin, platform, Manifest.Component::releaseUrl);
    }

    /** {@code releaseBase}: where a component's release files are (tests point it at a local server). */
    BinaryStore(Path bin, Platform platform, Function<Manifest.Component, String> releaseBase) {
        this.bin = bin;
        this.platform = platform;
        this.releaseBase = releaseBase;
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
        String base = releaseBase.apply(c), asset = c.asset(platform);
        String want = Manifest.parseSha256Sums(get(base + "/sha256sums.txt")).get(asset);
        if (want == null) throw new IOException(asset + " is not in the release's sha256sums.txt");

        Path part = bin.resolve(target.getFileName() + ".part");
        try {
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(base + "/" + asset)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " downloading " + asset);
            MessageDigest md = sha256();
            try (InputStream in = new DigestInputStream(res.body(), md)) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            String got = HexFormat.of().formatHex(md.digest());
            if (!got.equals(want)) throw new IOException("Bad checksum for " + asset + ": " + got + " != " + want);
            part.toFile().setExecutable(true, true);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
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

    private String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " for " + url);
        return res.body();
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

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

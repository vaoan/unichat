package app.unichat.login;

import app.unichat.support.Processes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reads the user's own Chrome/Edge/Brave session cookies on macOS. Only runs on an explicit user
 * action and only for the network being connected. The decryption key comes from the system
 * keychain, which shows its own native permission prompt. Cookie values, the keychain password and
 * the derived key are never logged or put in exception messages.
 */
public final class ChromiumCookies {
    public enum Support { SUPPORTED, NEEDS_IN_APP_LOGIN }

    private ChromiumCookies() {
    }

    /** A Chromium-family browser on macOS: display name, profile root and keychain service. */
    private record Browser(String name, String relativeRoot, String keychainService) {
    }

    private static final List<Browser> BROWSERS = List.of(
            new Browser("Chrome", "Google/Chrome", "Chrome Safe Storage"),
            new Browser("Edge", "Microsoft Edge", "Microsoft Edge Safe Storage"),
            new Browser("Brave", "BraveSoftware/Brave-Browser", "Brave Safe Storage"));

    /** Windows and app-bound encryption ({@code v20}) cannot be read; the UI falls back to in-app login. */
    public static Support classify(String osName, byte[] encryptedValuePrefix) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return Support.NEEDS_IN_APP_LOGIN;
        if (hasPrefix(encryptedValuePrefix, "v20")) return Support.NEEDS_IN_APP_LOGIN;
        return Support.SUPPORTED;
    }

    private static boolean hasPrefix(byte[] data, String prefix) {
        if (data == null || data.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (data[i] != (byte) prefix.charAt(i)) return false;
        }
        return true;
    }

    /**
     * Decrypts a Chromium {@code v10} value: strips the 3-byte prefix, AES-128-CBC with an IV of 16
     * spaces and PKCS5 padding. Recent Chrome prepends a 32-byte SHA-256 domain hash to the
     * plaintext; it is stripped when those bytes are not plain printable ASCII (a real cookie value
     * is, the hash is effectively random).
     */
    public static String decryptValue(byte[] encrypted, byte[] key) {
        if (!hasPrefix(encrypted, "v10")) throw new IllegalArgumentException("Not a v10 value");
        try {
            byte[] iv = new byte[16];
            Arrays.fill(iv, (byte) 0x20);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(encrypted, 3, encrypted.length - 3);
            int start = plain.length > 32 && !printableAscii(plain, 32) ? 32 : 0;
            return new String(plain, start, plain.length - start, StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            // Deliberately no data in the message.
            throw new IllegalStateException("Could not decrypt cookie value");
        }
    }

    private static boolean printableAscii(byte[] b, int len) {
        for (int i = 0; i < len; i++) {
            if (b[i] < 0x20 || b[i] > 0x7e) return false;
        }
        return true;
    }

    /** PBKDF2-HMAC-SHA1(password, "saltysalt", 1003 iterations, 16 bytes). */
    static byte[] deriveKey(String keychainPassword) {
        try {
            PBEKeySpec spec = new PBEKeySpec(keychainPassword.toCharArray(),
                    "saltysalt".getBytes(StandardCharsets.US_ASCII), 1003, 16 * 8);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Key derivation unavailable");
        }
    }

    /** Sessions per Chromium profile on macOS; empty elsewhere or when the keychain key is unavailable. */
    public static List<BrowserSession> findSessions(CookieSpec spec) {
        String os = System.getProperty("os.name", "");
        List<BrowserSession> out = new ArrayList<>();
        if (classify(os, null) != Support.SUPPORTED || !os.toLowerCase(Locale.ROOT).contains("mac")) {
            return out;
        }
        List<Path> overrides = overrideRoots();
        for (Browser b : BROWSERS) {
            List<Path> roots = overrides != null ? overrides : List.of(defaultRoot(b));
            byte[] key = null; // fetched lazily: only prompt the keychain if a profile has a Cookies DB
            boolean keyTried = false;
            for (Path root : roots) {
                for (Path profile : profiles(root)) {
                    Path db = cookiesFile(profile);
                    if (db == null) continue;
                    if (!keyTried) {
                        keyTried = true;
                        key = keychainKey(b.keychainService());
                    }
                    if (key == null) break;
                    byte[] k = key;
                    SessionCookies cookies = CookieDbCopy.read(db, copy -> read(copy, spec, k));
                    if (cookies != null) {
                        out.add(new BrowserSession(b.name(), profile.getFileName().toString(), cookies));
                    }
                }
            }
        }
        return out;
    }

    private static Path defaultRoot(Browser b) {
        return Paths.get(System.getProperty("user.home", "."), "Library", "Application Support")
                .resolve(b.relativeRoot());
    }

    private static List<Path> overrideRoots() {
        return FirefoxCookies.rootsOverride();
    }

    private static List<Path> profiles(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.equals("Default") || n.startsWith("Profile ");
                    })
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Newer Chromium keeps the DB in {@code Network/Cookies}, older versions directly in the profile. */
    private static Path cookiesFile(Path profile) {
        Path network = profile.resolve("Network").resolve("Cookies");
        if (Files.isRegularFile(network)) return network;
        Path legacy = profile.resolve("Cookies");
        return Files.isRegularFile(legacy) ? legacy : null;
    }

    /** Asks the macOS keychain (native prompt) for the Safe Storage password; null if denied/absent. */
    private static byte[] keychainKey(String service) {
        try {
            // The person may need time to answer the native prompt, or to find their password.
            Processes.Result r = Processes.capture(List.of("security", "find-generic-password", "-wa", service), 600);
            if (!r.ok()) return null;
            String password = r.out().strip();
            return password.isEmpty() ? null : deriveKey(password);
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static SessionCookies read(Path dbCopy, CookieSpec spec, byte[] key) {
        Map<String, String> all = new HashMap<>();
        // expires_utc is microseconds since 1601-01-01; 0 means a session cookie.
        long nowChromium = (System.currentTimeMillis() + 11_644_473_600_000L) * 1000L;
        String sql = "SELECT host_key, name, encrypted_value, expires_utc FROM cookies";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbCopy.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!spec.isForNetwork(rs.getString(1))) continue;
                long exp = rs.getLong(4);
                if (exp > 0 && exp < nowChromium) continue;
                byte[] enc = rs.getBytes(3);
                String name = rs.getString(2);
                if (name == null || enc == null || enc.length == 0) continue;
                if (classify("mac", enc) != Support.SUPPORTED) continue;
                try {
                    all.put(name, decryptValue(enc, key));
                } catch (RuntimeException e) {
                    // skip undecryptable cookie
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read Chromium cookies database");
        }
        return spec.select(all);
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }
}

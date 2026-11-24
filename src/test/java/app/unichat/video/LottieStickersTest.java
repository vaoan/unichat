package app.unichat.video;

import app.unichat.support.PathParts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Telegram's animated stickers (Lottie, a .tgs is gzipped JSON): told by their bytes, checked, made an animated WebP. */
public class LottieStickersTest { // NOPMD: JUnitJupiterTestShouldBePackagePrivate, its fixtures serve ui.media's tests too
    @TempDir Path dir;

    /** The one built for development (native/build-lottie.sh), when this machine has it: CI has none and skips. */
    public static @Nullable Path tool() {
        Path built = Path.of(System.getProperty("user.home"), "Library/Caches/unichat-build/lottieconverter");
        if (System.getProperty("unichat.lottieconverter") == null && Files.isExecutable(built)) {
            System.setProperty("unichat.lottieconverter", built.toString());
        }
        return LottieStickers.tool();
    }

    /** A red square crossing a 64 px canvas in a second, 30 frames a second. */
    public static byte[] json() throws IOException {
        try (InputStream in = LottieStickersTest.class.getResourceAsStream("/app/unichat/video/moving.json")) {
            return in.readAllBytes();
        }
    }

    public static Path tgs(Path dir, byte[] json) throws IOException {
        Path f = dir.resolve("sticker.tgs");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(f))) {
            out.write(json);
        }
        return f;
    }

    @Test
    void aTgsAndPlainLottieAreToldByTheirBytesWhateverTheirLabel() throws Exception {
        assertTrue(LottieStickers.isLottie(tgs(dir, json())), "gzipped, as Telegram sends it");
        assertTrue(LottieStickers.isLottie(Files.write(dir.resolve("plain"), json())), "its JSON, as Discord's");
        assertFalse(LottieStickers.isLottie(Files.writeString(dir.resolve("gif"), "GIF89a…")));
        assertFalse(LottieStickers.isLottie(Files.writeString(dir.resolve("text"), "{\"not\": \"an animation\"}")));
    }

    @Test
    void itsJsonComesOutWhetherItCameGzippedOrNot() throws Exception {
        assertArrayEquals(json(), LottieStickers.json(tgs(dir, json())));
        assertArrayEquals(json(), LottieStickers.json(Files.write(dir.resolve("plain"), json())));
    }

    /**
     * A stranger's file: what it says of itself decides how many frames the tool writes to disk, and a .tgs of a few KB
     * can unpack to gigabytes. Neither gets past the checks, and the tool never runs on it.
     */
    @Test
    void onePromisingEndlessFramesOrUnpackingToTooMuchIsRefusedBeforeAnyToolRuns() throws Exception {
        String endless = new String(json(), StandardCharsets.UTF_8).replace("\"op\":30,\"w\"", "\"op\":999999999,\"w\"");
        assertThrows(IOException.class, () -> LottieStickers.check(endless.getBytes(StandardCharsets.UTF_8)));
        String noRate = new String(json(), StandardCharsets.UTF_8).replace("\"fr\":30", "\"fr\":0");
        assertThrows(IOException.class, () -> LottieStickers.check(noRate.getBytes(StandardCharsets.UTF_8)));
        byte[] bomb = new byte[LottieStickers.MAX_JSON_BYTES + 1];
        java.util.Arrays.fill(bomb, (byte) ' ');
        assertThrows(IOException.class, () -> LottieStickers.json(tgs(dir, bomb)));
        assertEquals(1.0, LottieStickers.check(json()), 0.05, "a second long");
    }

    private static byte[] with(String from, String to) throws IOException {
        String s = new String(json(), StandardCharsets.UTF_8);
        assertTrue(s.contains(from), from);
        return s.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Found by the security reviewer: the frames are counted as rlottie counts them (first and last frame rounded, both
     * kept), not as the file's seconds. "fr":0.000001 with "op":0.00001 read as 10 s, and rlottie, seeing one frame at
     * that rate, wrote 30 million frames at 30 a second (~30 MB/s, until the timeout).
     */
    @Test
    void framesAreCountedAsTheToolCountsThem() throws Exception {
        assertThrows(IOException.class, () -> LottieStickers.check(with("\"fr\":30,\"ip\":0,\"op\":30", "\"fr\":0.000001,\"ip\":0,\"op\":0.00001")));
        assertThrows(IOException.class, () -> LottieStickers.check(with("\"fr\":30,\"ip\":0,\"op\":30", "\"fr\":0.001,\"ip\":0,\"op\":0.01")));
        assertThrows(IOException.class, () -> LottieStickers.check(with("\"fr\":30", "\"fr\":1000")), "no sticker runs at 1000 fps");
        LottieStickers.check(with("\"fr\":30,\"ip\":0,\"op\":30", "\"fr\":60,\"ip\":0,\"op\":180")); // Telegram's longest: 3 s at 60
    }

    /**
     * Found by the security reviewer: rlottie decodes a picture inside the animation (a "data:" asset) whole, whatever
     * size it says: a 2 KB .tgs took 637 MB. Stickers are drawn, never pictures: one with a picture isn't made.
     */
    @Test
    void oneCarryingAPictureIsRefused() throws Exception {
        assertThrows(IOException.class, () -> LottieStickers.check(with("\"assets\":[]",
                "\"assets\":[{\"id\":\"i\",\"w\":10000,\"h\":10000,\"e\":1,\"u\":\"\",\"p\":\"data:image/png;base64,AAAA\"}]")));
        LottieStickers.check(with("\"assets\":[]", "\"assets\":[{\"id\":\"c\",\"layers\":[]}]")); // a group of layers is fine
    }

    /**
     * Found by the security reviewer: a sticker is shown twice at once (its still picture and its animation), and both
     * made the same WebP in the same frames folder, each deleting the other's frames; one that couldn't be made was made
     * again at every look. One conversion per file, and a failure is remembered.
     */
    @Test
    void oneFileIsMadeOnceAtATimeAndAFailureIsntTriedAgain() throws Exception {
        LottieStickers.Once once = new LottieStickers.Once();
        Path f = dir.resolve("s.tgs");
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Callable<Path> make = () -> {
            runs.incrementAndGet();
            started.countDown();
            release.await();
            return f;
        };
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> call(once, f, make));
        started.await();
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> call(once, f, make));
        release.countDown();
        assertEquals(f, first.get());
        assertEquals(f, second.get());
        Path broken = dir.resolve("broken.tgs");
        java.util.concurrent.Callable<Path> fails = () -> {
            runs.incrementAndGet();
            throw new IOException("can't");
        };
        assertThrows(IOException.class, () -> once.of(broken, fails));
        assertThrows(IOException.class, () -> once.of(broken, fails));
        assertEquals(2, runs.get(), "the first file made once, the broken one tried once");
    }

    private static Path call(LottieStickers.Once once, Path f, java.util.concurrent.Callable<Path> make) {
        try {
            return once.of(f, make);
        } catch (Exception e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    @Test
    void itBecomesAnAnimatedWebpWithItsTransparency() throws Exception {
        assumeTrue(tool() != null, "no lottieconverter");
        assumeTrue(AnimatedWebp.img2webp() != null, "no img2webp");
        Path webp = LottieStickers.toWebp(tgs(dir, json()), dir.resolve("out.webp"), p -> { });
        byte[] bytes = Files.readAllBytes(webp);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("RIFF") && text.startsWith("WEBP", 8), "a WebP");
        assertTrue(text.contains("ANIM"), "animated");
        assertTrue(text.contains("ALPH") || (bytes[20] & 0x10) != 0, "with its transparency");
        try (var left = Files.list(dir)) {
            assertTrue(left.noneMatch(f -> PathParts.name(f).contains(".part")), "its frames gone");
        }
    }

    /** Shown again and again, it's made once, beside the sticker in its cache (so the cache trims both together). */
    @Test
    void shownItsMadeOnceBesideTheSticker() throws Exception {
        assumeTrue(tool() != null, "no lottieconverter");
        assumeTrue(AnimatedWebp.img2webp() != null, "no img2webp");
        Path tgs = tgs(dir, json());
        Path webp = LottieStickers.asWebp(tgs);
        assertEquals(dir.resolve("sticker.tgs.webp"), webp);
        long made = Files.getLastModifiedTime(webp).toMillis();
        Files.setLastModifiedTime(webp, java.nio.file.attribute.FileTime.fromMillis(made - 60_000));
        assertEquals(webp, LottieStickers.asWebp(tgs));
        assertEquals(made - 60_000, Files.getLastModifiedTime(webp).toMillis(), "not made again");
    }
}

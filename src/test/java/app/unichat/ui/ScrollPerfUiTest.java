package app.unichat.ui;

import app.unichat.Present;
import app.unichat.ui.desktop.Notifier;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MockChatService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.jspecify.annotations.Nullable;

/**
 * Not a check but a measurement, for before and after a change: a giant chat of stickers, GIFs, videos, photos and embeds
 * in a real window. Needs a display, ffmpeg and -Dunichat.perfTests=true; prints lines starting with PERF.
 */
@EnabledIfSystemProperty(named = "unichat.perfTests", matches = "true")
class ScrollPerfUiTest {
    private static final String CHAT = "!family:demo";
    private static final int MESSAGES = 5000;
    private static final int SENDERS = 400;
    private static final String[] WORDS = ("jajaja dale qué más parce mira ese gol increíble no puedo creerlo alguien vio el partido ayer "
            + "estaba buenísimo voy saliendo ahora nos vemos luego").split(" ");
    private final Random random = seeded();
    private final List<Path> stickers = new ArrayList<>();
    private final List<Path> gifs = new ArrayList<>();
    private final List<Path> photos = new ArrayList<>();
    private final List<Path> videos = new ArrayList<>();

    @SuppressFBWarnings(value = "PREDICTABLE_RANDOM", justification = "seeded test data, the same on every run on purpose; nothing secret")
    private static Random seeded() {
        return new Random(42);
    }

    private static <T> T fx(Callable<T> c) throws InterruptedException, ExecutionException {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get();
    }

    @SuppressFBWarnings(value = {"DMI_RANDOM_USED_ONLY_ONCE", "PREDICTABLE_RANDOM"}, justification = "seeded test data, the same on every run on purpose; nothing secret")
    private static void paint(Graphics2D g, int w, int h, int frame, int seed) {
        Random r = new Random(seed);
        g.setPaint(new GradientPaint(0, 0, new Color(r.nextInt(0xffffff)), w, h, new Color(r.nextInt(0xffffff))));
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < 40; i++) {
            g.setColor(new Color(r.nextInt(0xffffff)));
            int size = 10 + r.nextInt(w / 4);
            g.fillOval((r.nextInt(w) + frame * (3 + i % 5)) % w, (r.nextInt(h) + frame * (2 + i % 7)) % h, size, size);
        }
    }

    private static Path gif(Path dir, String name, int w, int h, int frames, int seed) throws IOException {
        return app.unichat.TestPictures.gif(dir.resolve(name), w, h, frames, (g, f) -> paint(g, w, h, f, seed));
    }

    private static Path photo(Path dir, String name, int seed) throws IOException {
        BufferedImage b = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        paint(g, 1600, 1200, 0, seed);
        g.dispose();
        Path p = dir.resolve(name);
        ImageIO.write(b, "jpg", p.toFile());
        return p;
    }

    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "a test runs its own tool (ffmpeg, mkfifo, sh, java) with its own fixed arguments; nothing from outside")
    private static Path video(Path gif) throws IOException, InterruptedException {
        Path out = Path.of(gif + ".mp4");
        new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-i", gif.toString(), "-pix_fmt", "yuv420p", "-c:v", "libx264", // lgtm[java/relative-path-command] the person's ffmpeg on PATH: a perf test only they run
                "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", out.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        return out;
    }

    private String text() {
        StringBuilder s = new StringBuilder();
        int words = 3 + random.nextInt(random.nextInt(5) == 0 ? 60 : 12);
        for (int i = 0; i < words; i++) s.append(WORDS[random.nextInt(WORDS.length)]).append(' ');
        return s.toString().strip();
    }

    private String pick(List<Path> files) {
        return files.get(random.nextInt(files.size())).toUri().toString();
    }

    private Message message(int i, Instant at) {
        int who = random.nextInt(SENDERS);
        Message m = new Message("perf-" + i, CHAT, "@u" + who + ":x", "Usuario " + who, false, at);
        int kind = random.nextInt(100);
        if (kind < 40) {
            m.setBody(text());
        } else if (kind < 60) {
            m.setType(MessageType.STICKER);
            m.setMediaUrl(pick(stickers));
            m.setMimeType("image/gif");
            m.setSize(320, 320);
        } else if (kind < 70) {
            m.setType(MessageType.IMAGE);
            m.setMediaUrl(pick(gifs));
            m.setMimeType("image/gif");
            m.setSize(480, 270);
            m.setFileName("a.gif");
        } else if (kind < 82) {
            m.setType(MessageType.IMAGE);
            m.setMediaUrl(pick(photos));
            m.setMimeType("image/jpeg");
            m.setSize(1600, 1200);
            m.setFileName("f.jpg");
        } else if (kind < 88) {
            m.setType(MessageType.VIDEO);
            m.setMediaUrl(pick(videos));
            m.setMimeType("video/mp4");
            m.setSize(480, 270);
            m.setFileName("v.mp4");
            m.setFileSize(800_000);
            ObjectNode info = JsonNodeFactory.instance.objectNode();
            info.put("fi.mau.gif", true);
            m.setMediaInfo(info);
        } else if (kind < 95) {
            m.setBody(text());
            m.setHtml("https://x.com/a/" + i + "\n<blockquote class=\"discord-embed\"><p class=\"discord-embed-title\"><strong>Título " + i
                    + "</strong></p><p class=\"discord-embed-description\"><p>" + text() + " <a href=\"https://x.com/h/" + i
                    + "\">#tag</a></p></p><p class=\"discord-embed-image\"><img src=\"mxc://demo/embed-" + i % 40 + "\" alt=\"\"></p></blockquote>");
        } else {
            m.setBody(text());
            if (i > 10) m.setReplyToId("perf-" + (i - 1 - random.nextInt(10)));
        }
        return m;
    }

    /** Where the UI thread is, every 5 ms. */
    private static final class Sampler implements AutoCloseable {
        final Map<String, Integer> ours = new ConcurrentHashMap<>();
        final AtomicInteger samples = new AtomicInteger();
        final AtomicInteger busy = new AtomicInteger();
        volatile boolean on = true;

        Sampler(Thread ui) {
            Thread t = new Thread(() -> {
                while (on) {
                    StackTraceElement[] stack = ui.getStackTrace();
                    samples.incrementAndGet();
                    if (stack.length > 0 && !stack[0].isNativeMethod()) {
                        busy.incrementAndGet();
                        for (StackTraceElement e : stack) {
                            if (e.getClassName().startsWith("app.unichat") && !e.getClassName().contains("ScrollPerf")) {
                                ours.merge(e.getClassName().replace("app.unichat.", "") + "." + e.getMethodName(), 1, Integer::sum);
                                break;
                            }
                        }
                    }
                    try {
                        // The sampling rate of this profiler, not a wait for the code: sampling the UI thread's stack
                        // every 5 ms is the measurement itself (the one timed pause the testing rules allow).
                        Thread.sleep(5); // lesson-ok: test-sleep
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        String top() {
            StringBuilder s = new StringBuilder();
            ours.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
                    .forEach(e -> s.append(' ').append(e.getValue() * 100 / Math.max(1, samples.get())).append("%:").append(e.getKey()));
            return s.toString();
        }

        @Override public void close() {
            on = false;
        }
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }

    @SuppressFBWarnings(value = "DM_GC", justification = "it measures what is kept after a collection (as its NOPMD says)")
    private static long liveMegabytes() {
        System.gc(); // NOPMD: DoNotCallGarbageCollectionExplicitly, it measures what is kept after a collection
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
    }

    /**
     * Scrolls one step per frame for {@code seconds} worth of frames (at 60 a second) and, with {@code live}, brings a
     * message every 6 frames: the load is counted in frames, not in time, so a slower machine does the same work.
     */
    private static void run(String name, Node target, double delta, int seconds, Thread ui, @Nullable Runnable live) throws InterruptedException, ExecutionException {
        List<Long> gaps = Collections.synchronizedList(new ArrayList<>());
        long[] last = {0};
        int[] direction = {-1};
        int[] frames = {0};
        int frameCount = seconds * 60;
        java.util.concurrent.Semaphore everySixth = new java.util.concurrent.Semaphore(0);
        CountDownLatch over = new CountDownLatch(1);
        AnimationTimer timer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (last[0] != 0) gaps.add(now - last[0]);
                last[0] = now;
                if (gaps.size() % 6 == 0) everySixth.release();
                if (gaps.size() >= frameCount) over.countDown();
                if (delta == 0) return;
                if (++frames[0] % 240 == 0) direction[0] = -direction[0];
                double d = direction[0] * delta;
                Event.fireEvent(target, new ScrollEvent(ScrollEvent.SCROLL, 400, 300, 400, 300, false, false, false, false, false, false,
                        0, d, 0, d, ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            }
        };
        long gcBefore = gcMillis();
        try (Sampler sampler = new Sampler(ui)) {
            FxWait.onFx(() -> {
                timer.start();
            });
            if (live == null) {
                over.await();
            } else {
                while (over.getCount() > 0) {
                    everySixth.acquire();
                    live.run();
                }
            }
            FxWait.onFx(() -> {
                timer.stop();
            });
            List<Long> s = new ArrayList<>(gaps);
            s.sort(java.util.Comparator.naturalOrder());
            long total = s.stream().mapToLong(x -> x).sum();
            System.out.printf("PERF %s: fps=%.0f p50=%.1f p95=%.1f p99=%.1f max=%.0f over25ms=%d%% over100ms=%d gc=%dms busy=%d%% live=%dMB%n   %s%n",
                    name, s.size() * 1e9 / Math.max(1, total), s.get(s.size() / 2) / 1e6, s.get(s.size() * 95 / 100) / 1e6,
                    s.get(s.size() * 99 / 100) / 1e6, s.get(s.size() - 1) / 1e6, s.stream().filter(x -> x > 25_000_000L).count() * 100 / s.size(),
                    s.stream().filter(x -> x > 100_000_000L).count(), gcMillis() - gcBefore,
                    sampler.busy.get() * 100 / Math.max(1, sampler.samples.get()), liveMegabytes(), sampler.top());
        }
    }

    private static void histogram() throws IOException {
        Process p = new ProcessBuilder("jcmd", Long.toString(ProcessHandle.current().pid()), "GC.class_histogram").redirectErrorStream(true).start(); // lgtm[java/relative-path-command] the JDK's jcmd on PATH: a perf test only the person runs
        List<String> lines = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().skip(2).limit(14).toList();
        lines.forEach(l -> System.out.println("PERF heap " + l));
    }

    @Test
    void measure() throws Exception {
        Path dir = Files.createTempDirectory("perf");
        for (int i = 0; i < 30; i++) stickers.add(gif(dir, "s" + i + ".gif", 320, 320, 30, i));
        for (int i = 0; i < 15; i++) gifs.add(gif(dir, "g" + i + ".gif", 480, 270, 40, 100 + i));
        for (int i = 0; i < 40; i++) photos.add(photo(dir, "p" + i + ".jpg", 200 + i));
        for (int i = 0; i < 8; i++) videos.add(video(gifs.get(i)));
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        Field field = MockChatService.class.getDeclaredField("messages");
        field.setAccessible(true); // NOPMD: AvoidAccessibilityAlteration, the test reaches a private part to check it
        @SuppressWarnings("unchecked") List<Message> list = Present.of(((Map<String, List<Message>>) field.get(svc)).get(CHAT)); // the field is that map
        Method fire = MockChatService.class.getDeclaredMethod("fireMessages", String.class);
        fire.setAccessible(true); // NOPMD: AvoidAccessibilityAlteration, the test reaches a private part to check it
        Instant start = Instant.now().minusSeconds(MESSAGES * 30L);
        synchronized (list) {
            for (int i = 0; i < MESSAGES; i++) list.add(message(i, start.plusSeconds(i * 30L)));
        }
        Scene[] scene = new Scene[1];
        MainView[] view = new MainView[1];
        Thread ui = fx(Thread::currentThread);
        FxWait.onFx(() -> {
            Stage stage = new Stage();
            view[0] = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, u -> { });
            scene[0] = new Scene(view[0], 1280, 860);
            Theme.register(scene[0]);
            stage.setScene(scene[0]);
            stage.show();
        });
        FxWait.settled();
        long opening = System.nanoTime();
        FxWait.onFx(() -> {
            view[0].openChatId(CHAT);
        });
        fx(() -> null);
        System.out.printf("PERF open the giant chat: %.0f ms%n", (System.nanoTime() - opening) / 1e6);
        FxWait.until("the giant chat's rows", () -> scene[0].getRoot().lookup(".row-cell") != null);
        FxWait.settled();
        Node timeline = fx(() -> scene[0].getRoot().lookupAll(".list-view").stream().filter(n -> n.lookup(".row-cell") != null).findFirst().orElseThrow());
        Node flow = fx(() -> timeline.lookup(".virtual-flow"));
        run("idle on animations", flow, 0, 8, ui, null);
        run("slow scroll", flow, 20, 20, ui, null);
        run("fast scroll", flow, 150, 20, ui, null);
        AtomicInteger next = new AtomicInteger(MESSAGES);
        Runnable live = () -> {
            try {
                Message m = message(next.incrementAndGet(), Instant.now());
                synchronized (list) {
                    list.add(m);
                }
                fire.invoke(svc, CHAT);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        };
        run("scroll + 10 msgs/s", flow, 40, 20, ui, live);
        run("at rest + 10 msgs/s", flow, 0, 15, ui, live);
        histogram();
        Node chats = fx(() -> scene[0].getRoot().lookupAll(".list-view").stream().filter(n -> n.lookup(".chat-cell") != null).findFirst().orElseThrow());
        run("chat list scroll", fx(() -> chats.lookup(".virtual-flow")), 60, 8, ui, null);
        for (String id : List.of("!mom:demo", CHAT, "!mom:demo", CHAT)) {
            long at = System.nanoTime();
            FxWait.onFx(() -> {
                view[0].openChatId(id);
            });
            fx(() -> null);
            System.out.printf("PERF switch to %s: %.0f ms%n", id.equals(CHAT) ? "giant" : "small", (System.nanoTime() - at) / 1e6);
            FxWait.settled();
        }
        FxWait.onFx(() -> {
            scene[0].getWindow().hide();
        });
    }
}

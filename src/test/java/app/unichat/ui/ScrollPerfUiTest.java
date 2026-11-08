package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.MockChatService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Not a check but a measurement, for before and after a change: a giant chat of stickers, GIFs, videos, photos and embeds
 * in a real window. Needs a display, ffmpeg and -Dunichat.perfTests=true; prints lines starting with PERF.
 */
@EnabledIfSystemProperty(named = "unichat.perfTests", matches = "true")
class ScrollPerfUiTest {
    private static final String CHAT = "!familia:demo";
    private static final int MESSAGES = 5000;
    private static final int SENDERS = 400;
    private static final String[] WORDS = ("jajaja dale qué más parce mira ese gol increíble no puedo creerlo alguien vio el partido ayer "
            + "estaba buenísimo voy saliendo ahora nos vemos luego").split(" ");
    private final Random random = new Random(42);
    private final List<Path> stickers = new ArrayList<>();
    private final List<Path> gifs = new ArrayList<>();
    private final List<Path> photos = new ArrayList<>();
    private final List<Path> videos = new ArrayList<>();

    private static <T> T fx(Callable<T> c) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(120, TimeUnit.SECONDS);
    }

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

    private static Path gif(Path dir, String name, int w, int h, int frames, int seed) throws Exception {
        Path p = dir.resolve(name);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(p.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int f = 0; f < frames; f++) {
                BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED);
                Graphics2D g = b.createGraphics();
                paint(g, w, h, f, seed);
                g.dispose();
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(b), param);
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                ((IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0)).setAttribute("delayTime", "4");
                meta.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(b, null, meta), param);
            }
            writer.endWriteSequence();
        }
        return p;
    }

    private static Path photo(Path dir, String name, int seed) throws Exception {
        BufferedImage b = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        paint(g, 1600, 1200, 0, seed);
        g.dispose();
        Path p = dir.resolve(name);
        ImageIO.write(b, "jpg", p.toFile());
        return p;
    }

    private static Path video(Path gif) throws Exception {
        Path out = Path.of(gif + ".mp4");
        new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-i", gif.toString(), "-pix_fmt", "yuv420p", "-c:v", "libx264",
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
                        Thread.sleep(5);
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

    private static long liveMegabytes() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
    }

    private static void run(String name, Node target, double delta, int seconds, Thread ui, Runnable live) throws Exception {
        List<Long> gaps = Collections.synchronizedList(new ArrayList<>());
        long[] last = {0};
        int[] direction = {-1};
        int[] frames = {0};
        AnimationTimer timer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (last[0] != 0) gaps.add(now - last[0]);
                last[0] = now;
                if (delta == 0) return;
                if (++frames[0] % 240 == 0) direction[0] = -direction[0];
                double d = direction[0] * delta;
                Event.fireEvent(target, new ScrollEvent(ScrollEvent.SCROLL, 400, 300, 400, 300, false, false, false, false, false, false,
                        0, d, 0, d, ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            }
        };
        long gcBefore = gcMillis();
        try (Sampler sampler = new Sampler(ui)) {
            fx(() -> {
                timer.start();
                return null;
            });
            long end = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < end) {
                if (live != null) live.run();
                Thread.sleep(live != null ? 100 : 200);
            }
            fx(() -> {
                timer.stop();
                return null;
            });
            List<Long> s = new ArrayList<>(gaps);
            Collections.sort(s);
            long total = s.stream().mapToLong(x -> x).sum();
            System.out.printf("PERF %s: fps=%.0f p50=%.1f p95=%.1f p99=%.1f max=%.0f over25ms=%d%% over100ms=%d gc=%dms busy=%d%% live=%dMB%n   %s%n",
                    name, s.size() * 1e9 / Math.max(1, total), s.get(s.size() / 2) / 1e6, s.get(s.size() * 95 / 100) / 1e6,
                    s.get(s.size() * 99 / 100) / 1e6, s.get(s.size() - 1) / 1e6, s.stream().filter(x -> x > 25_000_000L).count() * 100 / s.size(),
                    s.stream().filter(x -> x > 100_000_000L).count(), gcMillis() - gcBefore,
                    sampler.busy.get() * 100 / Math.max(1, sampler.samples.get()), liveMegabytes(), sampler.top());
        }
    }

    private static void histogram() throws Exception {
        Process p = new ProcessBuilder("jcmd", Long.toString(ProcessHandle.current().pid()), "GC.class_histogram").redirectErrorStream(true).start();
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
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        Field field = MockChatService.class.getDeclaredField("messages");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") List<Message> list = ((Map<String, List<Message>>) field.get(svc)).get(CHAT);
        Method fire = MockChatService.class.getDeclaredMethod("fireMessages", String.class);
        fire.setAccessible(true);
        Instant start = Instant.now().minusSeconds(MESSAGES * 30L);
        synchronized (list) {
            for (int i = 0; i < MESSAGES; i++) list.add(message(i, start.plusSeconds(i * 30L)));
        }
        Scene[] scene = new Scene[1];
        MainView[] view = new MainView[1];
        Thread ui = fx(Thread::currentThread);
        fx(() -> {
            Stage stage = new Stage();
            view[0] = new MainView(svc, stage, new Notifier(() -> { }, () -> { }, id -> { }, () -> { }), () -> { }, u -> { });
            scene[0] = new Scene(view[0], 1280, 860);
            Theme.register(scene[0]);
            stage.setScene(scene[0]);
            stage.show();
            return null;
        });
        Thread.sleep(1000);
        long opening = System.nanoTime();
        fx(() -> {
            view[0].openChatId(CHAT);
            return null;
        });
        fx(() -> null);
        System.out.printf("PERF open the giant chat: %.0f ms%n", (System.nanoTime() - opening) / 1e6);
        Thread.sleep(4000);
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
        for (String id : List.of("!mama:demo", CHAT, "!mama:demo", CHAT)) {
            long at = System.nanoTime();
            fx(() -> {
                view[0].openChatId(id);
                return null;
            });
            fx(() -> null);
            System.out.printf("PERF switch to %s: %.0f ms%n", id.equals(CHAT) ? "giant" : "small", (System.nanoTime() - at) / 1e6);
            Thread.sleep(1500);
        }
        fx(() -> {
            scene[0].getWindow().hide();
            return null;
        });
    }
}

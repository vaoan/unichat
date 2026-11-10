package app.unichat.autosave;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.ChatPreferences;
import app.unichat.service.ChatService;
import app.unichat.service.ChatSession;
import app.unichat.service.MediaSource;
import app.unichat.support.Urls;
import app.unichat.video.Ffmpeg;
import app.unichat.video.YtDlp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saves everything others send in a chat that has it on (its profile, {@link ChatPreferences#setAutoSave}): photos,
 * videos, audio and voice notes, documents and GIFs (not stickers), and the video behind each link ({@link YtDlp}).
 * Done as it arrives, so what disappears later (view once, timers) is kept too. Into
 * {@code <Downloads>/Unichat/<chat>/}, one file at a time on a thread of its own; nothing saved is overwritten.
 */
public final class AutoSave implements ChatService.Listener {
    /** What saving does, apart so tests don't need a server or yt-dlp. */
    interface Saver {
        /** The file behind {@code mediaUrl}, downloaded. */
        Path media(String mediaUrl) throws IOException, InterruptedException;

        /** The video behind {@code url} saved into {@code dir}; false when there's none. */
        boolean link(String url, Path dir) throws IOException, InterruptedException;
    }

    private static final Map<ChatSession, AutoSave> BY_SERVICE = new WeakHashMap<>();

    private final ChatPreferences prefs;
    private final Path root;
    private final Saver saver;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "autosave");
        t.setDaemon(true);
        return t;
    });

    AutoSave(ChatPreferences prefs, Path root, Saver saver) {
        this.prefs = prefs;
        this.root = root;
        this.saver = saver;
    }

    /** The one for {@code svc}, listening from the first call (the window is rebuilt on a language change). */
    public static synchronized AutoSave of(ChatService svc) {
        return BY_SERVICE.computeIfAbsent(svc, s -> {
            AutoSave a = new AutoSave(svc, defaultRoot(), downloading(svc));
            svc.addListener(a);
            return a;
        });
    }

    /** Where a chat's things go. */
    public Path folder(Chat chat) {
        return root.resolve(FileNames.folder(chat.title()));
    }

    /**
     * {@code <home>/Downloads/Unichat}: Windows and macOS both call the folder Downloads on disk.
     * {@code -Dunichat.autosaveDir} puts it elsewhere (tests never write to the person's folders).
     */
    static Path defaultRoot() {
        String chosen = System.getProperty("unichat.autosaveDir");
        return chosen != null ? Path.of(chosen) : Path.of(System.getProperty("user.home"), "Downloads", "Unichat");
    }

    /** {@code folder} as a person reads it: under their home, "~/…". */
    public static String shown(Path folder) {
        Path home = Path.of(System.getProperty("user.home"));
        return folder.startsWith(home) ? "~" + java.io.File.separator + home.relativize(folder) : folder.toString();
    }

    @Override
    public void received(Chat chat, Message m) {
        if (m.outgoing() || m.deleted() || !prefs.autoSaves(chat.id())) return;
        Path dir = folder(chat);
        if (wanted(m)) worker.execute(() -> saveMedia(m, dir));
        for (String url : links(m)) worker.execute(() -> saveLink(url, dir));
    }

    /** Media that's saved: all of it but stickers (a GIF is a video or picture with a flag, so it's in). */
    static boolean wanted(Message m) {
        return m.isMedia() && m.type() != MessageType.STICKER && m.mediaUrl() != null;
    }

    /** The links in what it says, and the page a reel shown as a picture points to. */
    static Set<String> links(Message m) {
        Set<String> out = new LinkedHashSet<>(Urls.in(m.body()));
        if (m.externalUrl() != null && m.externalUrl().startsWith("http")) out.add(m.externalUrl());
        return out;
    }

    private void saveMedia(Message m, Path dir) {
        try {
            Path file = saver.media(m.mediaUrl());
            Files.createDirectories(dir);
            Files.copy(file, FileNames.free(dir, FileNames.forMessage(m, ZoneId.systemDefault())), StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException | RuntimeException e) {
            System.err.println("Auto-save: a file from " + m.chatId() + " couldn't be saved: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void saveLink(String url, Path dir) {
        try {
            if (!saver.link(url, dir)) System.err.println("Auto-save: no video saved from a link in " + dir.getFileName());
        } catch (IOException | RuntimeException e) {
            System.err.println("Auto-save: a link's video couldn't be saved: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** For tests: runs {@code then} once everything asked so far is done. */
    void afterPending(Runnable then) {
        worker.execute(then);
    }

    private static Saver downloading(MediaSource media) {
        return new Saver() {
            @Override
            public Path media(String mediaUrl) throws IOException, InterruptedException {
                try {
                    return media.media(mediaUrl, 0, 0).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IOException(e.getCause());
                }
            }

            @Override
            public boolean link(String url, Path dir) throws IOException, InterruptedException {
                Path ytDlp = YtDlp.whenNeeded(p -> { });
                if (ytDlp == null) return false;
                return YtDlp.download(ytDlp, Ffmpeg.whenNeeded(p -> { }), dir, url);
            }
        };
    }
}

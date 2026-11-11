package app.unichat.ui;

import app.unichat.TestPictures;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.ScriptedChatService;
import app.unichat.settings.Settings;
import app.unichat.video.Ffmpeg;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static app.unichat.ui.TimelineWindow.CHAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pictures, stickers, GIFs, videos, voice notes and files, frame by frame: arriving, then their download finishing
 * (or failing) later, as on a slow connection. At the newest message it stays at the bottom edge; reading older ones,
 * what's read stays on its pixel. A bubble taking its real size when its file arrives is the commonest jump in a chat.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class TimelineMediaUiTest {
    private TimelineWindow w;
    private Path dir;

    @BeforeEach
    void files() throws Exception {
        Files.createDirectories(Settings.dataDir());
        dir = Files.createTempDirectory(Settings.dataDir(), "scroll-media");
    }

    @AfterEach
    void close() throws Exception {
        if (w != null) w.close();
    }

    // ---------------------------------------------------------------- files to send

    /** A still picture, its own file each time (pictures are cached by address). */
    private String still(String format, int width, int height) throws Exception {
        return TestPictures.still(Files.createTempFile(dir, "foto", "." + format), format, width, height).toUri().toString();
    }

    private String gif(int width, int height) throws Exception {
        return TestPictures.gif(Files.createTempFile(dir, "anim", ".gif"), width, height, 12).toUri().toString();
    }

    private static Consumer<Message> media(MessageType type, String url, String mime, int width, int height) {
        return m -> {
            m.setType(type);
            m.setMediaUrl(url);
            m.setMimeType(mime);
            m.setSize(width, height);
            m.setBody(type == MessageType.STICKER ? "sticker" : "");
        };
    }

    private static Consumer<Message> file(MessageType type, String url, String mime, String name, long size, ObjectNode info) {
        return m -> {
            m.setType(type);
            m.setMediaUrl(url);
            m.setMimeType(mime);
            m.setFileName(name);
            m.setBody(name);
            m.setFileSize(size);
            m.setMediaInfo(info);
        };
    }

    /** Pictures drawn in the list so far. */
    private int picturesShown() {
        return (int) w.list().lookupAll("ImageView").stream()
                .filter(n -> n instanceof ImageView v && v.getImage() != null && !v.getImage().isError()).count();
    }

    // ---------------------------------------------------------------- at the newest message

    /**
     * Opens the chat at its newest message, holds downloads, and watches every frame while {@code arrive} brings messages
     * and their files then come in (or fail): the newest message never leaves the bottom edge.
     */
    private void arrivesAtTheNewest(String what, boolean fails, Runnable arrive) throws Exception {
        if (w == null) w = TimelineWindow.open(20);
        double gap = w.untilAtBottom();
        int before = FxWait.fx(this::picturesShown);
        ScriptedChatService.Gate downloads = w.svc.holdDownloads();
        TimelineWindow.Frames frames = w.watch(w.atBottom(gap));
        arrive.run();
        FxWait.until("it's listed", w::showsNewest);
        FxWait.settled();
        assertEquals(before, FxWait.fx(this::picturesShown), "precondition: its picture waits for the download");
        if (fails) {
            downloads.fail();
        } else {
            downloads.release();
        }
        if (pictures && !fails) FxWait.until("its picture shows", () -> picturesShown() > before);
        FxWait.settled();
        frames.stop(what);
    }

    private void arrives(String what, Consumer<Message> message) throws Exception {
        arrivesAtTheNewest(what, false, () -> w.svc.receive(CHAT, message));
    }

    /** A message with no picture to wait for (a voice note, a document). */
    private void arrivesWithoutPicture(String what, Consumer<Message> message) throws Exception {
        pictures = false;
        arrives(what, message);
    }

    /** Whether what arrives brings a picture, which the test waits to see drawn. */
    private boolean pictures = true;

    @Test
    void aPhotoWhoseSizeIsKnown() throws Exception {
        String url = still("png", 800, 600);
        arrives("a photo of known size arriving, then its file", media(MessageType.IMAGE, url, "image/png", 800, 600));
    }

    @Test
    void aPhotoWhoseSizeIsntKnown() throws Exception {
        String url = still("png", 800, 600);
        arrives("a photo of unknown size arriving, then its file", media(MessageType.IMAGE, url, "image/png", 0, 0));
    }

    @Test
    void aTallPhoto() throws Exception {
        String url = still("jpg", 600, 1800);
        arrives("a tall photo arriving, then its file", media(MessageType.IMAGE, url, "image/jpeg", 600, 1800));
    }

    @Test
    void aPhotoThatNeverLoads() throws Exception {
        String url = still("png", 800, 600);
        arrivesAtTheNewest("a photo whose download fails", true, () -> w.svc.receive(CHAT, media(MessageType.IMAGE, url, "image/png", 800, 600)));
    }

    @Test
    void aStickerWhoseSizeIsntKnown() throws Exception {
        String url = still("png", 512, 512);
        arrives("a sticker of unknown size arriving, then its file", media(MessageType.STICKER, url, "image/png", 0, 0));
    }

    @Test
    void anAnimatedSticker() throws Exception {
        String url = gif(320, 320);
        arrives("an animated sticker arriving, then its frames", media(MessageType.STICKER, url, "image/gif", 320, 320));
    }

    @Test
    void anAnimatedGif() throws Exception {
        String url = gif(480, 270);
        arrives("a GIF arriving, then its frames", media(MessageType.IMAGE, url, "image/gif", 480, 270));
    }

    @Test
    void anAnimatedGifWhoseSizeIsntKnown() throws Exception {
        String url = gif(480, 270);
        arrives("a GIF of unknown size arriving, then its frames", media(MessageType.IMAGE, url, "image/gif", 0, 0));
    }

    @Test
    void severalPhotosAtOnce() throws Exception {
        List<String> urls = List.of(still("png", 800, 600), still("jpg", 600, 900), still("png", 1200, 400), still("jpg", 500, 500));
        arrivesAtTheNewest("four photos at once, their files after", false, () -> {
            for (int i = 0; i < urls.size(); i++) w.svc.receive(CHAT, media(MessageType.IMAGE, urls.get(i), "image/png", i % 2 == 0 ? 0 : 800, i % 2 == 0 ? 0 : 600));
        });
    }

    @Test
    void anEmbedsPictureOfUnknownShape() throws Exception {
        String name = "embed-" + System.nanoTime();
        arrives("an embed whose picture arrives later", m -> {
            m.setBody("mira este link");
            m.setHtml("<p>mira este link</p><img src=\"mxc://demo/" + name + "\">");
        });
    }

    @Test
    void aLinkPreviewAddedAfterTheMessageShows() throws Exception {
        String name = "preview-" + System.nanoTime();
        arrivesAtTheNewest("a link preview added to the message after it showed", false, () -> {
            String id = w.svc.receive(CHAT, m -> m.setBody("https://example.org/algo"));
            w.svc.rewrite(CHAT, id, m -> m.setHtml("<p>https://example.org/algo</p><blockquote><b>Algo</b><br>Una página"
                    + "<img src=\"mxc://demo/" + name + "\"></blockquote>"));
        });
    }

    @Test
    void aVoiceNote() throws Exception {
        ObjectNode info = JsonNodeFactory.instance.objectNode().put("duration", 2000);
        arrivesWithoutPicture("a voice note arriving", file(MessageType.AUDIO, "demo://nota-de-voz.ogg", "audio/ogg", "nota.ogg", 9000, info));
    }

    @Test
    void aDocument() throws Exception {
        Path doc = Files.writeString(Files.createTempFile(dir, "informe", ".txt"), "hola");
        arrivesWithoutPicture("a document arriving", file(MessageType.FILE, doc.toUri().toString(), "text/plain", "informe.txt", 4, null));
    }

    /**
     * In a short list (Linux's taller fonts leave 416 px) a file arriving was never drawn: the list's estimate of its
     * rows' heights said it already ended above it, and moving to it by that estimate went nowhere.
     */
    @Test
    void aDocumentInAShortWindow() throws Exception {
        w = TimelineWindow.open(20, "Mensaje largo %d\nsegunda línea\ntercera línea\ncuarta línea", svc -> { }, TimelineWindow.SHORT);
        Path doc = Files.writeString(Files.createTempFile(dir, "informe", ".txt"), "hola");
        arrivesWithoutPicture("a document arriving in a short window",
                file(MessageType.FILE, doc.toUri().toString(), "text/plain", "informe.txt", 4, null));
    }

    @Test
    void aPictureSentAsADocumentInAShortWindow() throws Exception {
        w = TimelineWindow.open(20, "Mensaje largo %d\nsegunda línea\ntercera línea\ncuarta línea", svc -> { }, TimelineWindow.SHORT);
        String url = still("png", 1200, 900);
        // Its own name: previews are kept by name and size, and another test's would show at once.
        arrives("a picture sent as a document in a short window", file(MessageType.FILE, url, "image/png", "plano-corto.png", 50_000, null));
    }

    @Test
    void aPictureSentAsADocument() throws Exception {
        String url = still("png", 1200, 900);
        arrives("a picture sent as a document, its preview after", file(MessageType.FILE, url, "image/png", "plano.png", 50_000, null));
    }

    @Test
    void aLongVideoWithItsThumbnail() throws Exception {
        ObjectNode info = JsonNodeFactory.instance.objectNode().put("duration", 600_000).put("thumbnail_url", "mxc://demo/video-thumb-" + System.nanoTime()); // from the server, as bridges give it
        arrives("a long video arriving (a file with its thumbnail)",
                file(MessageType.VIDEO, "mxc://demo/no-such-video", "video/mp4", "viaje.mp4", 80_000_000, info));
    }

    @Test
    void aShortVideoThatPlaysInTheChat() throws Exception {
        Path ffmpeg = Ffmpeg.find();
        assumeTrue(ffmpeg != null, "no ffmpeg to make a video");
        Path gif = Path.of(java.net.URI.create(gif(320, 240)));
        Path mp4 = dir.resolve("corto.mp4");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-y", "-loglevel", "error", "-i", gif.toString(), "-pix_fmt", "yuv420p",
                "-c:v", "libx264", mp4.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(p.waitFor() == 0, "this ffmpeg can't make an mp4");
        ObjectNode info = JsonNodeFactory.instance.objectNode().put("duration", 500).put("fi.mau.gif", true).put("w", 320).put("h", 240);
        arrivesWithoutPicture("a short looping video arriving (a player, not a picture)", m -> {
            file(MessageType.VIDEO, mp4.toUri().toString(), "video/mp4", "corto.mp4", 20_000, info).accept(m);
            m.setSize(320, 240);
        });
    }

    @Test
    void sendingAPhoto() throws Exception {
        w = TimelineWindow.open(20);
        double gap = w.untilAtBottom();
        Path photo = Path.of(java.net.URI.create(still("png", 1000, 750)));
        int before = FxWait.fx(this::picturesShown);
        TimelineWindow.Frames frames = w.watch(w.atBottom(gap));
        FxWait.fx(() -> { w.composer().sendFiles(List.of(photo.toFile())); return null; });
        FxWait.until("it's sent and drawn", () -> w.showsNewest() && picturesShown() > before);
        frames.stop("sending a photo: its bar, its bubble, its picture");
    }

    @Test
    void openingAChatWhoseNewestPhotoIsStillDownloading() throws Exception {
        w = TimelineWindow.open(20);
        FxWait.fx(() -> { w.view.openChatId("!familia:demo"); return null; });
        FxWait.settled();
        ScriptedChatService.Gate downloads = w.svc.holdDownloads();
        w.svc.receive(CHAT, media(MessageType.IMAGE, still("png", 900, 600), "image/png", 0, 0));
        w.openChat();
        double gap = w.untilAtBottom();
        TimelineWindow.Frames frames = w.watch(w.atBottom(gap));
        downloads.release();
        FxWait.settled();
        frames.stop("the newest photo's file arriving just after the chat opened");
    }

    // ---------------------------------------------------------------- reading older messages

    /** A chat with a photo every few messages, none downloaded yet, read from further up. */
    private ScriptedChatService.Gate readingAmongPhotos(boolean sizesKnown) throws Exception {
        List<String> urls = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) urls.add(i % 2 == 0 ? still("png", 800, 600) : gif(300, 300));
        ScriptedChatService.Gate[] downloads = new ScriptedChatService.Gate[1];
        w = TimelineWindow.open(0, "", svc -> {
            downloads[0] = svc.holdDownloads();
            for (int i = 0; i < 40; i++) {
                if (i % 5 == 2) {
                    String url = urls.get(i / 5);
                    boolean gif = url.endsWith(".gif");
                    svc.receive(CHAT, media(gif ? MessageType.STICKER : MessageType.IMAGE, url, gif ? "image/gif" : "image/png",
                            sizesKnown ? (gif ? 300 : 800) : 0, sizesKnown ? (gif ? 300 : 600) : 0));
                } else {
                    svc.receive(CHAT, "Mensaje " + i + "\ncon otra línea");
                }
            }
        });
        return downloads[0];
    }

    @Test
    void picturesAroundTheMessageReadLoad() throws Exception {
        ScriptedChatService.Gate downloads = readingAmongPhotos(false);
        TimelineWindow.Frames frames = w.readingOlder();
        downloads.release();
        FxWait.settled();
        frames.stop("pictures of unknown size above and below the message read, their files arriving");
    }

    @Test
    void picturesOfKnownSizeAroundTheMessageReadLoad() throws Exception {
        ScriptedChatService.Gate downloads = readingAmongPhotos(true);
        TimelineWindow.Frames frames = w.readingOlder();
        downloads.release();
        FxWait.settled();
        frames.stop("pictures of known size around the message read, their files arriving");
    }

    @Test
    void picturesAroundTheMessageReadFailToLoad() throws Exception {
        ScriptedChatService.Gate downloads = readingAmongPhotos(false);
        TimelineWindow.Frames frames = w.readingOlder();
        downloads.fail();
        FxWait.settled();
        frames.stop("pictures around the message read failing to download");
    }

    @Test
    void aPhotoArrivesWhileReadingOlderOnes() throws Exception {
        w = TimelineWindow.open(30);
        TimelineWindow.Frames frames = w.readingOlder();
        ScriptedChatService.Gate downloads = w.svc.holdDownloads();
        w.svc.receive(CHAT, media(MessageType.IMAGE, still("png", 800, 1200), "image/png", 0, 0));
        FxWait.until("it's listed", w::showsNewest);
        downloads.release();
        FxWait.settled();
        frames.stop("a photo arriving below while reading older ones");
        assertTrue(FxWait.fx(w::jumpDownShown), "still reading older ones");
    }

    @Test
    void anEmbedAboveTheMessageReadTakesItsShape() throws Exception {
        String name = "embed-above-" + System.nanoTime();
        ScriptedChatService.Gate[] downloads = new ScriptedChatService.Gate[1];
        w = TimelineWindow.open(0, "", svc -> {
            downloads[0] = svc.holdDownloads();
            for (int i = 0; i < 30; i++) {
                int n = i;
                if (i % 6 == 3) svc.receive(CHAT, m -> {
                    m.setBody("link " + n);
                    m.setHtml("<p>link " + n + "</p><img src=\"mxc://demo/" + name + "-" + n + "\">");
                });
                else svc.receive(CHAT, "Mensaje " + i + "\ncon otra línea");
            }
        });
        TimelineWindow.Frames frames = w.readingOlder();
        downloads[0].release();
        FxWait.settled();
        frames.stop("embeds' pictures around the message read taking their shape");
    }
}

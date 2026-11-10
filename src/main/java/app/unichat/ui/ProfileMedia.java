package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.ChatService;
import app.unichat.support.I18n;
import app.unichat.support.Urls;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The profile's "Multimedia": what was shared in the chat, by kind in tabs (photos, videos, links, files), newest
 * first. Photos keep their shape (shown whole in their square, never stretched). A chat can hide it (its options).
 */
final class ProfileMedia {
    enum Shelf { PHOTOS, VIDEOS, LINKS, FILES }

    /** A link written in a message. */
    record Link(String url, Message from) {}

    /** The chat's shared things by kind, newest first, at most {@link #MAX} of each. Pure. */
    record Contents(List<Message> photos, List<Message> videos, List<Link> links, List<Message> files) {
        static final int MAX = 60;

        static Contents of(List<Message> messages) {
            List<Message> photos = new ArrayList<>(), videos = new ArrayList<>(), files = new ArrayList<>();
            List<Link> links = new ArrayList<>();
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if (m.deleted()) continue;
                if (m.mediaUrl() != null) {
                    switch (m.type()) {
                        case IMAGE -> add(photos, m);
                        case VIDEO -> add(videos, m);
                        case FILE, AUDIO -> add(files, m);
                        default -> { } // stickers aren't shared things
                    }
                }
                for (String url : Urls.in(m.body())) if (links.size() < MAX) links.add(new Link(url, m));
            }
            return new Contents(photos, videos, links, files);
        }

        private static void add(List<Message> to, Message m) {
            if (to.size() < MAX) to.add(m);
        }

        int count(Shelf s) {
            return switch (s) {
                case PHOTOS -> photos.size();
                case VIDEOS -> videos.size();
                case LINKS -> links.size();
                case FILES -> files.size();
            };
        }

        boolean isEmpty() {
            return photos.isEmpty() && videos.isEmpty() && links.isEmpty() && files.isEmpty();
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final int TILE = 84;

    private final ChatService svc;
    private final Supplier<Window> window;
    private final Consumer<String> toast;
    /** The tab open, kept while the profile is drawn again (it is on every message). */
    private Shelf open = Shelf.PHOTOS;

    ProfileMedia(ChatService svc, Supplier<Window> window, Consumer<String> toast) {
        this.svc = svc;
        this.window = window;
        this.toast = toast;
    }

    /** The section for {@code c}; nothing when nothing was shared or the chat hides it. */
    List<Node> section(Chat c) {
        Contents contents = Contents.of(svc.messages(c.id()));
        if (contents.isEmpty() || svc.mediaHidden(c.id())) return List.of();
        if (contents.count(open) == 0) {
            for (Shelf s : Shelf.values()) {
                if (contents.count(s) > 0) {
                    open = s;
                    break;
                }
            }
        }
        VBox body = new VBox(6);
        ToggleGroup group = new ToggleGroup();
        FlowPane tabs = new FlowPane(6, 6);
        tabs.getStyleClass().add("side-panel-tabs");
        for (Shelf s : Shelf.values()) {
            if (contents.count(s) == 0) continue;
            tabs.getChildren().add(Chips.sticky(I18n.t("info.shelf." + s.name(), contents.count(s)), group, s == open, () -> {
                open = s;
                body.getChildren().setAll(shelf(s, contents));
            }));
        }
        body.getChildren().setAll(shelf(open, contents));
        return List.of(tabs, body);
    }

    /** What the profile compares to know it must be drawn again: what each tab would show. */
    String shown(Chat c) {
        if (svc.mediaHidden(c.id())) return "hidden";
        Contents x = Contents.of(svc.messages(c.id()));
        StringBuilder b = new StringBuilder();
        for (Shelf s : Shelf.values()) b.append(s).append(x.count(s)).append(' ');
        x.photos().stream().limit(9).forEach(m -> b.append(m.id()));
        return b.toString();
    }

    private Node shelf(Shelf s, Contents c) {
        return switch (s) {
            case PHOTOS -> grid(c.photos(), this::photo);
            case VIDEOS -> grid(c.videos(), this::video);
            case LINKS -> rows(c.links(), this::link);
            case FILES -> rows(c.files(), this::file);
        };
    }

    private static <T> Node grid(List<T> items, java.util.function.Function<T, Node> tile) {
        FlowPane grid = new FlowPane(4, 4);
        for (T i : items) grid.getChildren().add(tile.apply(i));
        return grid;
    }

    private static <T> Node rows(List<T> items, java.util.function.Function<T, Node> row) {
        VBox list = new VBox(6);
        for (T i : items) list.getChildren().add(row.apply(i));
        return list;
    }

    /** Whole, in its square: its own shape kept. */
    private Node photo(Message m) {
        ImageView v = new ImageView();
        v.getStyleClass().add("media-thumb");
        load(v, m.mediaUrl());
        return MediaTile.of(v, TILE, TILE, "media-thumb-tile", I18n.t("media.imageOpen"), true, () -> {
            if (window.get() != null) Gallery.open(window.get(), svc, m, toast);
        });
    }

    /** The picture the sender attached, else a play sign; opens the player. */
    private Node video(Message m) {
        ImageView v = new ImageView();
        v.getStyleClass().add("media-thumb");
        String thumb = m.mediaInfo() == null ? "" : m.mediaInfo().path("thumbnail_url").asText("");
        if (!thumb.isEmpty()) load(v, thumb);
        javafx.scene.layout.StackPane tile = MediaTile.of(v, TILE, TILE, "media-thumb-tile", I18n.t("media.videoOpen"), true, () -> {
            if (window.get() == null) return;
            VideoViewer.show(window.get(), svc.media(m.mediaUrl(), 0, 0), I18n.t("video.title"),
                    () -> MediaSaver.save(window.get(), svc, m, toast), Desktop::open);
        });
        tile.getChildren().add(Icons.icon(Icons.PLAY, 22));
        return tile;
    }

    private Node link(Link l) {
        Hyperlink h = RichText.link(l.url(), l.url(), Browser::open, 300);
        Label meta = new Label(byWhom(l.from()));
        meta.getStyleClass().add("settings-help");
        return new VBox(0, h, meta);
    }

    /** Name, size, who and when; opens with the system's app. */
    private Node file(Message m) {
        String name = m.fileName() == null || m.fileName().isBlank() ? MediaSaver.suggestedName(m, ZoneId.systemDefault()) : m.fileName();
        Button b = new Button(name, Icons.icon(m.type() == MessageType.AUDIO ? Icons.PLAY : Icons.FILE, 18));
        b.getStyleClass().add("profile-file");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> svc.media(m.mediaUrl(), 0, 0).whenComplete((p, err) -> {
            if (err != null) Platform.runLater(() -> toast.accept(I18n.t("media.downloadFailed")));
            else Desktop.open(p);
        }));
        Label meta = new Label((m.fileSize() > 0 ? Formats.fileSize(m.fileSize()) + " · " : "") + byWhom(m));
        meta.getStyleClass().add("settings-help");
        return new VBox(0, b, meta);
    }

    private String byWhom(Message m) {
        String who = m.outgoing() ? I18n.t("common.you") : m.senderName();
        return m.timestamp() == null ? who : who + " · " + DAY.withLocale(I18n.locale()).format(m.timestamp().atZone(ZoneId.systemDefault()));
    }

    private void load(ImageView v, String url) {
        svc.media(url, TILE * 2, TILE * 2).thenAcceptAsync(p -> {
            try {
                Image img = AnimatedImage.still(p, TILE * 2, TILE * 2);
                Platform.runLater(() -> v.setImage(img));
            } catch (java.io.IOException e) {
                System.err.println("Could not decode a shared picture: " + e);
            }
        }, AnimatedImage.DECODER);
    }

    /** The system's app for a file, through AWT's own thread (mistakes.md). */
    private static final class Desktop {
        static void open(java.nio.file.Path p) {
            java.awt.EventQueue.invokeLater(() -> {
                try {
                    java.awt.Desktop.getDesktop().open(p.toFile());
                } catch (Exception ignored) {
                    // No app for it: nothing happens; it can still be saved from the message.
                }
            });
        }
    }
}

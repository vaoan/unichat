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
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The profile's "Multimedia": what was shared in the chat, by kind in tabs (photos, videos, audio, links, files), newest
 * first. Photos keep their shape (shown whole in their square, never stretched). A chat can hide it (its options).
 */
final class ProfileMedia {
    enum Shelf { PHOTOS, VIDEOS, AUDIOS, LINKS, FILES }

    /** A link written in a message. */
    record Link(String url, Message from) {}

    /** The chat's shared things by kind, newest first, at most {@link #MAX} of each. Pure. */
    record Contents(List<Message> photos, List<Message> videos, List<Message> audios, List<Link> links, List<Message> files) {
        static final int MAX = 60;

        static Contents of(List<Message> messages) {
            List<Message> photos = new ArrayList<>(), videos = new ArrayList<>(), audios = new ArrayList<>(), files = new ArrayList<>();
            List<Link> links = new ArrayList<>();
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if (m.deleted()) continue;
                if (m.mediaUrl() != null) {
                    switch (m.type()) {
                        case IMAGE -> add(photos, m);
                        case VIDEO -> add(videos, m);
                        case AUDIO -> add(audios, m); // voice notes and music: heard here, not opened elsewhere
                        case FILE -> add(files, m);
                        default -> { } // stickers aren't shared things
                    }
                }
                for (String url : Urls.in(m.body())) if (links.size() < MAX) links.add(new Link(url, m));
            }
            return new Contents(photos, videos, audios, links, files);
        }

        private static void add(List<Message> to, Message m) {
            if (to.size() < MAX) to.add(m);
        }

        int count(Shelf s) {
            return switch (s) {
                case PHOTOS -> photos.size();
                case VIDEOS -> videos.size();
                case AUDIOS -> audios.size();
                case LINKS -> links.size();
                case FILES -> files.size();
            };
        }

        boolean isEmpty() {
            return photos.isEmpty() && videos.isEmpty() && audios.isEmpty() && links.isEmpty() && files.isEmpty();
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final int TILE = 84;

    private final ChatService svc;
    private final Supplier<Window> window;
    private final Consumer<String> toast;
    /** The tab open, kept while the profile is drawn again (it is on every message). */
    private Shelf open = Shelf.PHOTOS;
    private Consumer<Message> jump = m -> { };
    /** The window's player (one at a time); null until given: an audio then opens like a file. */
    private AudioPlayback audio;
    private Chat shownChat;

    ProfileMedia(ChatService svc, Supplier<Window> window, Consumer<String> toast) {
        this.svc = svc;
        this.window = window;
        this.toast = toast;
    }

    /** "Ir al mensaje": shows in the chat the message a thing was shared in. */
    void setOnJump(Consumer<Message> jump) {
        this.jump = jump;
    }

    /** The section for {@code c}; nothing when nothing was shared or the chat hides it. */
    List<Node> section(Chat c) {
        shownChat = c;
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
        VBox body = new VBox(8);
        Label name = new Label();
        name.getStyleClass().add("media-shelf-title");
        ToggleGroup group = new ToggleGroup();
        HBox tabs = new HBox();
        tabs.getStyleClass().add("media-tabs");
        Runnable show = () -> {
            name.setText(I18n.t("info.shelf." + open.name(), contents.count(open)));
            body.getChildren().setAll(name, shelf(open, contents));
        };
        for (Shelf s : Shelf.values()) {
            if (contents.count(s) == 0) continue;
            tabs.getChildren().add(tab(s, contents.count(s), group, () -> {
                open = s;
                show.run();
            }));
        }
        show.run();
        return List.of(tabs, body);
    }

    /**
     * One segment of the bar: the kind's icon and how many, filling its share of the width. The name is in the tooltip,
     * for screen readers and above what's shown: at 160 % text four names don't fit the side panel side by side.
     */
    private ToggleButton tab(Shelf s, int count, ToggleGroup group, Runnable action) {
        String label = I18n.t("info.shelf." + s.name(), count);
        ToggleButton b = new ToggleButton(Integer.toString(count), Icons.icon(switch (s) {
            case PHOTOS -> Icons.IMAGE;
            case VIDEOS -> Icons.PLAY;
            case AUDIOS -> Icons.MIC;
            case LINKS -> Icons.EXTERNAL;
            case FILES -> Icons.FILE;
        }, 16));
        b.getStyleClass().add("media-tab");
        b.setToggleGroup(group);
        b.setSelected(s == open);
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, javafx.scene.layout.Priority.ALWAYS);
        b.setTooltip(new javafx.scene.control.Tooltip(label));
        b.setAccessibleText(label);
        b.setOnAction(e -> {
            if (!b.isSelected()) b.setSelected(true); // a tab stays chosen: clicking it again changes nothing
            action.run();
        });
        return b;
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
            case AUDIOS -> rows(c.audios(), this::audio);
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
        return goesToItsMessage(MediaTile.of(v, TILE, TILE, "media-thumb-tile", I18n.t("media.imageOpen"), true, () -> {
            if (window.get() != null) Gallery.open(window.get(), svc, m, toast);
        }), m);
    }

    /** A tile opens what it shows; its menu (right click, the menu key) goes to the message instead. */
    private Node goesToItsMessage(javafx.scene.layout.StackPane tile, Message m) {
        javafx.scene.control.Tooltip.install(tile, new javafx.scene.control.Tooltip(I18n.t("info.mediaTileHelp")));
        tile.setAccessibleHelp(I18n.t("info.mediaTileHelp"));
        tile.setOnContextMenuRequested(e -> {
            javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(
                    ChatMenus.item(I18n.t("info.goToMessage"), () -> jump.accept(m)));
            menu.show(tile, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return tile;
    }

    /** The row's own "Ir al mensaje" button, at its end. */
    private Node withJump(Node row, Message m) {
        Button go = Icons.iconButton(Icons.CHAT, I18n.t("info.goToMessage"), () -> jump.accept(m));
        HBox.setHgrow(row, javafx.scene.layout.Priority.ALWAYS);
        if (row instanceof javafx.scene.layout.Region r) r.setMaxWidth(Double.MAX_VALUE);
        HBox line = new HBox(4, row, go);
        line.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return line;
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
        return goesToItsMessage(tile, m);
    }

    private Node link(Link l) {
        Hyperlink h = RichText.link(l.url(), l.url(), Browser::open, 300);
        Label meta = new Label(byWhom(l.from()));
        meta.getStyleClass().add("settings-help");
        VBox row = new VBox(1, h, meta);
        row.getStyleClass().add("profile-link");
        return withJump(row, l.from());
    }

    /** The window's player, so an audio plays right here (and stops whatever else was playing). */
    void setAudio(AudioPlayback audio) {
        this.audio = audio;
    }

    /** Who and when (and the name, for music), over its own player. */
    private Node audio(Message m) {
        if (audio == null || shownChat == null) return file(m);
        boolean voiceNote = m.mimeType() != null && m.mimeType().startsWith("audio/ogg");
        String what = voiceNote || m.fileName() == null ? I18n.t("info.voiceNote") : m.fileName();
        Label meta = new Label(what + " · " + byWhom(m));
        meta.getStyleClass().add("settings-help");
        Chat chat = shownChat;
        AudioControls controls = new AudioControls(audio, () -> m, () -> audio.toggle(m, chat));
        controls.setMaxWidth(Double.MAX_VALUE);
        VBox row = new VBox(2, meta, controls);
        row.getStyleClass().add("profile-audio");
        return withJump(row, m);
    }

    /** Name, size, who and when; opens with the system's app. */
    private Node file(Message m) {
        String name = m.fileName() == null || m.fileName().isBlank() ? MediaSaver.suggestedName(m, ZoneId.systemDefault()) : m.fileName();
        Label meta = new Label((m.fileSize() > 0 ? Formats.fileSize(m.fileSize()) + " · " : "") + byWhom(m));
        meta.getStyleClass().add("settings-help");
        Label title = new Label(name);
        title.getStyleClass().add("profile-file-name");
        Button b = new Button(null, new HBox(10, Icons.icon(m.type() == MessageType.AUDIO ? Icons.MIC : Icons.FILE, 18), new VBox(1, title, meta)));
        b.getStyleClass().add("profile-file");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAccessibleText(name + ", " + meta.getText());
        b.setOnAction(e -> svc.media(m.mediaUrl(), 0, 0).whenComplete((p, err) -> {
            if (err != null) Platform.runLater(() -> toast.accept(I18n.t("media.downloadFailed")));
            else Desktop.open(p);
        }));
        return withJump(b, m);

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

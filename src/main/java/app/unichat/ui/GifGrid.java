package app.unichat.ui;

import app.unichat.gif.Gif;
import app.unichat.gif.GifFiles;
import app.unichat.gif.GifProviders;
import app.unichat.gif.GifSearch;
import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.RoomFeatures;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * GIFs tab: search across the providers turned on in Ajustes → GIFs, plus favourite and recent GIFs (shared by every
 * chat). A chosen GIF is downloaded and sent flagged as a GIF; recents keep it for any other chat or network.
 */
final class GifGrid extends VBox {
    private static final int TILE_W = 132, TILE_H = 100, LIMIT = 24;

    private final Supplier<String> takeReply;
    private final ChatService svc;
    private final MediaLibrary library;
    private final Supplier<Chat> chat;
    private final Consumer<String> toast;
    private final TextField search = new TextField();
    private final VBox content = new VBox(10);
    /** Favourites and recents: rebuilt alone when the library changes (no new provider search). */
    private final VBox libraryBox = new VBox(10);
    private final PauseTransition debounce = new PauseTransition(Duration.millis(450));
    private int generation;
    private final TileCache libraryTiles = new TileCache();
    /** What the results on screen were searched for, and when; trending changes, so not for ever. */
    private String resultsFor;
    private long resultsAt;
    private static final long RESULTS_LAST_MS = 10 * 60_000;

    GifGrid(ChatService svc, MediaLibrary library, Supplier<Chat> chat, Supplier<String> takeReply, Consumer<String> toast) {
        super(8);
        this.takeReply = takeReply;
        this.svc = svc;
        this.library = library;
        this.chat = chat;
        this.toast = toast;
        getStyleClass().add("gif-grid");
        search.setPromptText("Buscar GIFs");
        search.getStyleClass().addAll("search-field", "gif-search");
        search.setAccessibleText("Buscar GIFs");
        search.setOnAction(e -> refresh());
        debounce.setOnFinished(e -> refresh());
        library.addListener(() -> javafx.application.Platform.runLater(() -> {
            if (getScene() != null) refreshLibrary();
        }));
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("side-panel-scroll");
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(search, scroll);
    }

    /** Rebuilds for the open chat: notices, favourites/recents, then search results (or trending). */
    void refresh() {
        String q0 = search.getText().strip();
        String shownFor = q0 + "|" + accepts() + "|" + GifProviders.active(k -> Settings.get().getString(k, null)).size();
        // Changing chats shouldn't search the web again or reload what's on screen.
        if (shownFor.equals(resultsFor) && System.currentTimeMillis() - resultsAt < RESULTS_LAST_MS) {
            refreshLibrary();
            return;
        }
        resultsFor = null;
        int gen = ++generation;
        content.getChildren().clear();
        boolean accepted = accepts();
        if (!accepted) content.getChildren().add(Labels.help("Esta red no acepta GIFs."));
        GifSearch providers = new GifSearch(GifProviders.active(k -> Settings.get().getString(k, null)));
        String q = search.getText().strip();
        content.getChildren().add(libraryBox);
        refreshLibrary();
        if (providers.isEmpty()) {
            content.getChildren().add(Labels.help("Para buscar GIFs, activa un proveedor en Ajustes → GIFs (Giphy o Klipy, con una clave gratis)."));
            return;
        }
        Label title = section(q.isEmpty() ? "Tendencias" : "Resultados");
        FlowPane grid = new FlowPane(6, 6);
        Label loading = Labels.help("Buscando…");
        content.getChildren().addAll(title, loading, grid);
        CompletableFuture.supplyAsync(() -> q.isEmpty() ? providers.trending(LIMIT) : providers.search(q, LIMIT))
                .thenAccept(gifs -> Platform.runLater(() -> {
                    if (gen != generation) return; // a newer search replaced this one
                    content.getChildren().remove(loading);
                    if (!gifs.isEmpty()) {
                        resultsFor = shownFor;
                        resultsAt = System.currentTimeMillis();
                    }
                    if (gifs.isEmpty()) content.getChildren().add(Labels.help("No se encontraron GIFs."));
                    for (Gif g : gifs) {
                        Node tile = tile(new Image(g.previewUrl(), TILE_W * 2, 0, true, true, true),
                                g.title().isBlank() ? "GIF" : g.title(), accepted, () -> sendFromProvider(g));
                        // Favourites keep the provider's address; sending one later downloads it again.
                        LibraryMenu.attach(tile, library, GifFiles.item(g), accepted ? () -> sendFromProvider(g) : null, toast);
                        grid.getChildren().add(tile);
                    }
                }));
    }

    boolean accepts() {
        Chat c = chat.get();
        return c == null || c.features().accepts(RoomFeatures.GIF);
    }

    private void refreshLibrary() {
        libraryTiles.next();
        libraryBox.getChildren().clear();
        if (!search.getText().strip().isEmpty()) return;
        Chat c = chat.get();
        boolean accepted = c == null || c.features().accepts(RoomFeatures.GIF);
        libraryRow("Favoritos", library.favorites(MediaLibrary.Kind.GIF), accepted);
        libraryRow("Recientes", library.recents(MediaLibrary.Kind.GIF), accepted);
    }

    private void libraryRow(String title, List<MediaLibrary.Item> items, boolean accepted) {
        if (items.isEmpty()) return;
        FlowPane grid = new FlowPane(6, 6);
        for (MediaLibrary.Item i : items) {
            String what = title.equals("Favoritos") ? "GIF favorito" : "GIF reciente";
            grid.getChildren().add(libraryTiles.get(what + accepted + i.url(), () -> libraryTile(i, what, accepted)));
        }
        libraryBox.getChildren().addAll(section(title), grid);
    }

    /** A saved GIF's tile for the open chat (also used by the Favoritos tab). */
    Node libraryTile(MediaLibrary.Item i, String what) {
        Chat c = chat.get();
        return libraryTile(i, what, c == null || c.features().accepts(RoomFeatures.GIF));
    }

    private Node libraryTile(MediaLibrary.Item i, String what, boolean accepted) {
        ImageView probe = new ImageView();
        String preview = GifFiles.preview(i);
        // A provider's preview, through the media service too, so it's on disk from then on.
        java.util.concurrent.CompletableFuture<java.nio.file.Path> loading;
        if (preview != null && preview.startsWith("http")) {
            loading = MessageMedia.loadMedia(svc, probe, MediaItems.message(new MediaLibrary.Item(MediaLibrary.Kind.STICKER, preview, "image/gif", i.width(), i.height(), null)),
                    TILE_W * 2, TILE_H * 2);
        } else loading = MessageMedia.loadMedia(svc, probe, MediaItems.message(i), TILE_W * 2, TILE_H * 2);
        Node tile = tile(probe, what, accepted, () -> sendFromLibrary(i));
        LibraryMenu.attach(tile, library, i, accepted ? () -> sendFromLibrary(i) : null, toast);
        if (tile instanceof javafx.scene.layout.StackPane box) MediaItems.markIfLost(loading, box);
        return tile;
    }

    private Node tile(Image img, String what, boolean accepted, Runnable send) {
        return tile(new ImageView(img), what, accepted, send);
    }

    private Node tile(ImageView view, String what, boolean accepted, Runnable send) {
        Runnable act = () -> {
            if (!accepted) toast.accept("Esta red no acepta GIFs");
            else send.run();
        };
        return MediaTile.of(view, TILE_W, TILE_H, "gif-tile", what + (accepted ? ", enviar" : ", esta red no acepta GIFs"), accepted, act);
    }

    /** Downloads the provider's file (mp4 when available, smaller and accepted by every bridge) and sends it. */
    private void sendFromProvider(Gif g) {
        MediaLibrary.Item item = GifFiles.item(g);
        send(item, CompletableFuture.supplyAsync(() -> download(item.url(), "video/mp4".equals(item.mime()) ? ".mp4" : ".gif")));
    }

    private void sendFromLibrary(MediaLibrary.Item i) {
        CompletableFuture<Path> file = i.url().startsWith("mxc://") ? svc.media(i.url(), 0, 0)
                : CompletableFuture.supplyAsync(() -> download(i.url(), "video/mp4".equals(i.mime()) ? ".mp4" : ".gif"));
        send(i, file);
    }

    private void send(MediaLibrary.Item item, CompletableFuture<Path> file) {
        Chat c = chat.get();
        if (c == null) return;
        toast.accept("Enviando GIF…");
        String reply = takeReply.get();
        file.thenCompose(p -> svc.sendGif(c.id(), p, item.mime() == null ? "image/gif" : item.mime(), item.width(), item.height(), reply))
                .whenComplete((v, err) -> Platform.runLater(() -> {
                    if (err != null) {
                        toast.accept("No se pudo enviar el GIF");
                        return;
                    }
                    library.addRecent(item);
                }));
    }

    private static Path download(String url, String ext) {
        try {
            return GifFiles.download(url, ext, GifFiles.TIMEOUT);
        } catch (Exception e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("emoji-category");
        return l;
    }
}

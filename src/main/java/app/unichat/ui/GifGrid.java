package app.unichat.ui;

import app.unichat.gif.Gif;
import app.unichat.gif.GifProviders;
import app.unichat.gif.GifSearch;
import app.unichat.model.Chat;
import app.unichat.model.MediaLibrary;
import app.unichat.model.RoomFeatures;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private final ChatService svc;
    private final MediaLibrary library;
    private final Supplier<Chat> chat;
    private final Consumer<String> toast;
    private final TextField search = new TextField();
    private final VBox content = new VBox(10);
    private final PauseTransition debounce = new PauseTransition(Duration.millis(450));
    private int generation;

    GifGrid(ChatService svc, MediaLibrary library, Supplier<Chat> chat, Consumer<String> toast) {
        super(8);
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
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("side-panel-scroll");
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(search, scroll);
    }

    /** Rebuilds for the open chat: notices, favourites/recents, then search results (or trending). */
    void refresh() {
        int gen = ++generation;
        content.getChildren().clear();
        Chat c = chat.get();
        boolean accepted = c == null || c.features().accepts(RoomFeatures.GIF);
        if (!accepted) content.getChildren().add(help("Esta red no acepta GIFs."));
        GifSearch providers = new GifSearch(GifProviders.active(k -> Settings.get().getString(k, null)));
        String q = search.getText().strip();
        if (q.isEmpty()) {
            libraryRow("Favoritos", library.favorites(MediaLibrary.Kind.GIF), accepted);
            libraryRow("Recientes", library.recents(MediaLibrary.Kind.GIF), accepted);
        }
        if (providers.isEmpty()) {
            content.getChildren().add(help("Para buscar GIFs, activa un proveedor en Ajustes → GIFs (Giphy o Klipy, con una clave gratis)."));
            return;
        }
        Label title = section(q.isEmpty() ? "Tendencias" : "Resultados");
        FlowPane grid = new FlowPane(6, 6);
        Label loading = help("Buscando…");
        content.getChildren().addAll(title, loading, grid);
        CompletableFuture.supplyAsync(() -> q.isEmpty() ? providers.trending(LIMIT) : providers.search(q, LIMIT))
                .thenAccept(gifs -> Platform.runLater(() -> {
                    if (gen != generation) return; // a newer search replaced this one
                    content.getChildren().remove(loading);
                    if (gifs.isEmpty()) content.getChildren().add(help("No se encontraron GIFs."));
                    for (Gif g : gifs) grid.getChildren().add(tile(new Image(g.previewUrl(), TILE_W * 2, 0, true, true, true),
                            g.title().isBlank() ? "GIF" : g.title(), accepted, () -> sendFromProvider(g)));
                }));
    }

    private void libraryRow(String title, List<MediaLibrary.Item> items, boolean accepted) {
        if (items.isEmpty()) return;
        FlowPane grid = new FlowPane(6, 6);
        for (MediaLibrary.Item i : items) {
            ImageView probe = new ImageView();
            ThreadPane.loadMedia(svc, probe, MediaItems.message(i), TILE_W * 2, TILE_H * 2);
            grid.getChildren().add(tile(probe, title.equals("Favoritos") ? "GIF favorito" : "GIF reciente", accepted, () -> sendFromLibrary(i)));
        }
        content.getChildren().addAll(section(title), grid);
    }

    private Node tile(Image img, String what, boolean accepted, Runnable send) {
        return tile(new ImageView(img), what, accepted, send);
    }

    private Node tile(ImageView view, String what, boolean accepted, Runnable send) {
        view.setFitWidth(TILE_W);
        view.setFitHeight(TILE_H);
        view.setPreserveRatio(true);
        StackPane tile = new StackPane(view);
        tile.getStyleClass().add("gif-tile");
        tile.setMinSize(TILE_W, TILE_H);
        tile.setMaxSize(TILE_W, TILE_H);
        tile.setFocusTraversable(true);
        tile.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        tile.setAccessibleText(what + (accepted ? ", enviar" : ", esta red no acepta GIFs"));
        tile.setOpacity(accepted ? 1 : 0.4);
        tile.setCursor(accepted ? Cursor.HAND : Cursor.DEFAULT);
        Runnable act = () -> {
            if (!accepted) toast.accept("Esta red no acepta GIFs");
            else send.run();
        };
        tile.setOnMouseClicked(e -> act.run());
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                e.consume();
                act.run();
            }
        });
        return tile;
    }

    /** Downloads the provider's file (mp4 when available, smaller and accepted by every bridge) and sends it. */
    private void sendFromProvider(Gif g) {
        boolean mp4 = g.mp4Url() != null;
        MediaLibrary.Item item = new MediaLibrary.Item(MediaLibrary.Kind.GIF, mp4 ? g.mp4Url() : g.gifUrl(),
                mp4 ? "video/mp4" : "image/gif", g.width(), g.height(), null);
        send(item, CompletableFuture.supplyAsync(() -> download(item.url(), mp4 ? ".mp4" : ".gif")));
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
        file.thenCompose(p -> svc.sendGif(c.id(), p, item.mime() == null ? "image/gif" : item.mime(), item.width(), item.height()))
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
            HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new java.io.IOException("HTTP " + r.statusCode());
            Path f = Files.createTempFile("unichat-gif", ext);
            f.toFile().deleteOnExit();
            try (InputStream in = r.body()) {
                Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
            }
            return f;
        } catch (Exception e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("emoji-category");
        return l;
    }

    private static Label help(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("settings-help");
        return l;
    }
}

package app.unichat.ui;

import app.unichat.model.Network;
import app.unichat.service.MediaSource;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

/** Round avatar: image if available, otherwise colored initials; optional network badge bottom-right. */
public final class Avatar extends StackPane {
    private static final String[] COLORS = {
            "#E5484D", "#F76B15", "#FFB224", "#46A758", "#12A594", "#0090FF", "#3E63DD", "#8E4EC6", "#D6409F", "#978365"
    };

    private final double size;
    private final Label initials = new Label();
    private final ImageView image = new ImageView();
    private final Label badge = new Label();
    private String loadedUrl;

    public Avatar(double size) {
        this.size = size;
        getStyleClass().add("avatar");
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        initials.getStyleClass().add("avatar-initials");
        initials.setStyle("-fx-font-size: " + Math.round(size * 0.38) + "px;");
        initials.setMinSize(size, size);
        initials.setMaxSize(size, size);
        initials.setAlignment(Pos.CENTER);
        image.setFitWidth(size);
        image.setFitHeight(size);
        image.setPreserveRatio(false);
        image.setClip(new Circle(size / 2, size / 2, size / 2));
        image.setVisible(false);
        double b = Math.max(14, Math.round(size * 0.38));
        badge.getStyleClass().add("network-badge");
        badge.setMinSize(b, b);
        badge.setMaxSize(b, b);
        badge.setAlignment(Pos.CENTER);
        badge.setStyle("-fx-font-size: " + Math.round(b * 0.55) + "px;");
        badge.setVisible(false);
        StackPane.setAlignment(badge, Pos.BOTTOM_RIGHT);
        badge.setTranslateX(b * 0.2);
        badge.setTranslateY(b * 0.2);
        getChildren().addAll(initials, image, badge);
    }

    /** The color used for {@code key}'s initials (also used for your accounts' tiny avatars). */
    static String colorFor(String key) {
        return COLORS[Math.floorMod(key.hashCode(), COLORS.length)];
    }

    /** Shows {@code name}'s initials and loads {@code url} through the service (may be null). */
    public void set(String name, String key, String url, MediaSource service) {
        initials.setText(Formats.initials(name));
        String color = COLORS[Math.floorMod((key == null ? name : key).hashCode(), COLORS.length)];
        initials.setStyle("-fx-font-size: " + Math.round(size * 0.38) + "px; -fx-background-color: " + color + ";");
        if (url == null) {
            loadedUrl = null;
            image.setImage(null);
            image.setVisible(false);
            return;
        }
        if (url.equals(loadedUrl)) return;
        loadedUrl = url;
        Image cached = ImageCache.get(url);
        if (cached != null) {
            // Same photo in any size (list, header, messages, info): show it at once, no initials flash.
            image.setImage(cached);
            image.setVisible(true);
            return;
        }
        image.setVisible(false);
        ImageCache.load(url, service).thenAccept(img -> {
            if (img == null) return; // no photo available: keep the initials
            Platform.runLater(() -> {
                if (!url.equals(loadedUrl)) return;
                image.setImage(img);
                image.setVisible(true);
            });
        });
    }

    public void setNetwork(Network network, boolean visible) {
        if (network == null || !visible) {
            badge.setVisible(false);
            return;
        }
        double b = badge.getMinWidth();
        if (NetworkIcons.path(network) != null) {
            badge.setText("");
            badge.setGraphic(NetworkIcons.glyph(network, b));
        } else {
            badge.setGraphic(null);
            badge.setText(network.badgeLetter());
        }
        badge.setAccessibleText(network.displayName());
        badge.setStyle(badge.getStyle().replaceAll("-fx-background-color:[^;]*;", "")
                + "-fx-background-color: " + network.color() + ";");
        badge.setVisible(true);
    }
}

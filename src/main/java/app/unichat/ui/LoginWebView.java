package app.unichat.ui;

import app.unichat.login.CookieSpec;
import app.unichat.login.SessionCookies;
import app.unichat.model.Network;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Modal window showing a network's real login page. The user signs in as on the website
 * (password, 2FA, checkpoints); once every required session cookie exists the window closes
 * itself and hands the cookies back. Cookie values are never logged.
 */
public final class LoginWebView {
    private LoginWebView() {}

    /** The single open login window; only one may swap the JVM-wide cookie handler at a time. */
    private static Stage openStage;

    /** True while a login window is open. */
    public static boolean isOpen() { return openStage != null; }

    public static void open(Window owner, Network network, CookieSpec spec,
                            Consumer<SessionCookies> onDone, Runnable onCancel) {
        if (openStage != null) { // never swap the global handler twice
            openStage.toFront();
            openStage.requestFocus();
            return;
        }
        CookieHandler previous = CookieHandler.getDefault();
        CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        WebView web = new WebView();
        web.setContextMenuEnabled(false);
        WebEngine engine = web.getEngine();
        engine.setUserAgent(LoginUrls.userAgent());
        web.setAccessibleText("Página de inicio de sesión de " + network.displayName());

        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Iniciar sesión en " + network.displayName());

        Label hint = new Label("Inicia sesión como en la web de " + network.displayName()
                + ". La ventana se cerrará sola al terminar.");
        hint.getStyleClass().add("settings-help");
        hint.setWrapText(true);
        Button cancel = new Button("Cancelar");
        cancel.setAccessibleText("Cancelar el inicio de sesión en " + network.displayName());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, hint, spacer, cancel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 14, 10, 14));
        bar.getStyleClass().add("settings-row");

        BorderPane root = new BorderPane(web);
        root.setTop(bar);
        root.getStyleClass().add("settings-root");
        Scene scene = new Scene(root, 520, 760);
        Theme.register(scene);
        stage.setScene(scene);

        boolean[] finished = {false};
        Timeline poll = new Timeline();

        Runnable check = () -> {
            if (finished[0]) return;
            Map<String, String> byName = new HashMap<>();
            for (HttpCookie c : manager.getCookieStore().getCookies()) {
                if (c.hasExpired() || !spec.isForNetwork(c.getDomain())) continue;
                byName.put(c.getName(), c.getValue());
            }
            SessionCookies s = spec.select(byName);
            if (s.complete()) {
                finished[0] = true;
                stage.close();
                onDone.accept(s);
            }
        };

        poll.getKeyFrames().add(new KeyFrame(Duration.seconds(1.5), e -> check.run()));
        poll.setCycleCount(Animation.INDEFINITE);
        engine.getLoadWorker().stateProperty().addListener((o, a, s) -> {
            if (s == Worker.State.SUCCEEDED) check.run();
        });

        cancel.setOnAction(e -> {
            if (!finished[0]) {
                finished[0] = true;
                stage.close();
                onCancel.run();
            }
        });
        stage.setOnHidden(e -> {
            poll.stop();
            engine.load("about:blank");
            // Restore the JVM-wide handler only if nobody replaced ours meanwhile.
            if (CookieHandler.getDefault() == manager) CookieHandler.setDefault(previous);
            openStage = null;
            if (!finished[0]) {
                finished[0] = true; // closed with the window button
                Platform.runLater(onCancel);
            }
        });

        // Install the global handler only after all risky setup succeeded; undo on failure.
        // WebView reads the JVM-wide default handler: give it a fresh, empty store while open.
        CookieHandler.setDefault(manager);
        openStage = stage;
        try {
            stage.show();
            poll.play();
            engine.load(LoginUrls.startUrl(network));
        } catch (RuntimeException ex) {
            poll.stop();
            if (CookieHandler.getDefault() == manager) CookieHandler.setDefault(previous);
            openStage = null;
            throw ex;
        }
    }
}

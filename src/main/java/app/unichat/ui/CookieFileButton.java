package app.unichat.ui;

import app.unichat.login.CookieLogin;
import app.unichat.login.SessionCookies;
import app.unichat.model.Network;
import javafx.scene.control.Button;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/** "Choose a cookies.txt": the session from a file the person exported from their browser. */
final class CookieFileButton {
    private CookieFileButton() {}

    /**
     * @param read      reads the file for the cookies this login needs
     * @param onCookies gets the session when the file has it
     * @param onProblem gets what to tell the person when it hasn't
     */
    static Button create(Network network, Function<Path, CookieLogin.TxtResult> read, Consumer<SessionCookies> onCookies, Consumer<String> onProblem) {
        Button file = new Button("Elegir archivo cookies.txt…");
        file.getStyleClass().add("secondary-button");
        file.setMaxWidth(Double.MAX_VALUE);
        file.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Elige tu archivo cookies.txt");
            fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("cookies.txt", "*.txt"),
                    new FileChooser.ExtensionFilter("Todos los archivos", "*.*"));
            File f = fc.showOpenDialog(file.getScene().getWindow());
            if (f == null) return;
            CookieLogin.TxtResult r = read.apply(f.toPath());
            switch (r.kind()) {
                case OK -> onCookies.accept(r.cookies());
                case MISSING -> onProblem.accept(LoginTexts.missingMessage(network, r.missing()));
                default -> onProblem.accept(r.message());
            }
        });
        return file;
    }
}

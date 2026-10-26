package app.unichat;

/**
 * Plain main class for the fat jar / jpackage: JavaFX refuses to start from a main class that
 * extends Application when javafx modules are on the classpath instead of the module path.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        UnichatApp.main(args);
    }
}

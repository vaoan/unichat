package app.unichat;

/**
 * Plain main class for the fat jar / jpackage: JavaFX refuses to start from a main class that
 * extends Application when javafx modules are on the classpath instead of the module path.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        Recovery.Start start = Recovery.Start.of(args);
        start.waitForThePrevious(); // started again by Recovery: the run before must be gone first
        // Tests and snapshot runs keep their output on the console only, and never start again by themselves.
        if (System.getProperty("unichat.snapshot") == null) {
            AppLog.install();
            Recovery.install(start);
        }
        UnichatApp.main(start.args());
    }
}

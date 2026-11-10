package app.unichat.ui;

import app.unichat.cloak.CloakJob;
import app.unichat.cloak.CloakLevel;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.DoubleConsumer;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The protection bar shows its texts whole, at the usual size and at 160 %. {@code -Dunichat.barShot=<prefix>} saves it. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class CloakBarUiTest {
    @Test
    void nothingIsCutAtAnyTextSize() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        CloakJob job = runningJob();
        assertTrue(job.start(Path.of("una foto de vacaciones.jpg"), Path.of("out.png"), CloakLevel.MEDIUM, p -> { }));
        FxWait.until("protecting", () -> job.status().phase() == CloakJob.Phase.PROTECTING && job.status().fraction() > 0);
        for (double px : List.of(13.0, 21.0)) {
            CloakBar bar = FxWait.fx(() -> {
                CloakBar b = new CloakBar(job);
                Stage stage = new Stage();
                VBox root = new VBox(b);
                root.getStyleClass().add("root");
                root.setStyle("-fx-font-size: " + px + "px;");
                Scene scene = new Scene(root, Double.parseDouble(System.getProperty("unichat.barWidth", "900")), 200);
                scene.getStylesheets().add(CloakBarUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                return b;
            });
            FxWait.settled();
            String shot = System.getProperty("unichat.barShot");
            if (shot != null) {
                var img = FxWait.fx(() -> bar.snapshot(null, null));
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(shot + (int) px + ".png"));
            }
            for (Node n : FxWait.fx(() -> List.copyOf(bar.lookupAll(".label")))) {
                Labeled l = (Labeled) n;
                if (!FxWait.fx(l::isVisible) || FxWait.fx(l::getText) == null || FxWait.fx(l::getText).isEmpty()) continue;
                Text drawn = (Text) FxWait.fx(() -> l.lookup(".text"));
                String full = FxWait.fx(l::getText);
                assertEquals(full.replace("\n", ""), FxWait.fx(drawn::getText).replace("\n", ""), "cut at " + px + "px");
            }
            FxWait.fx(() -> { bar.getScene().getWindow().hide(); return null; });
        }
        job.cancel();
    }

    /** A job whose protection reports 37 % and then waits to be cancelled; installing is instant. */
    private static CloakJob runningJob() throws Exception {
        Class<?> runner = Class.forName("app.unichat.cloak.CloakJob$Runner");
        Object fake = Proxy.newProxyInstance(runner.getClassLoader(), new Class<?>[] {runner}, (p, m, args) -> {
            if (m.getName().equals("protect")) {
                ((DoubleConsumer) args[3]).accept(0.37);
                new CountDownLatch(1).await(); // until cancelled (interrupted)
            }
            return null;
        });
        var c = CloakJob.class.getDeclaredConstructor(runner);
        c.setAccessible(true);
        return c.newInstance(fake);
    }
}

package app.unichat.ui.chats;

import app.unichat.ui.FxWait;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The profile's six actions: six in a row, or three and three; never a column, nor four and two. */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class InfoActionsLayoutUiTest {
    @Test
    void sixInARowOrThreeAndThreeAtAnyTextSizeAndWidth() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        Platform.setImplicitExit(false);
        MockChatService svc = new MockChatService();
        List<String> wrong = new ArrayList<>();
        for (int px : new int[] {13, 16, 21, 24}) {
            for (int width : new int[] {280, 320, 360, 420}) {
                Stage[] stage = new Stage[1];
                InfoPane pane = FxWait.fx(() -> {
                    InfoPane p = new InfoPane(svc, () -> { }, t -> { });
                    stage[0] = new Stage();
                    stage[0].setOpacity(0);
                    Scene scene = new Scene(p, width, 700);
                    scene.getStylesheets().add(InfoActionsLayoutUiTest.class.getResource("/app/unichat/css/app.css").toExternalForm());
                    scene.getRoot().setStyle("-fx-font-size: " + px + "px;");
                    stage[0].setScene(scene);
                    stage[0].show();
                    p.show(svc.chat("!familia:demo"));
                    return p;
                });
                FxWait.until("the actions show", () -> pane.lookupAll(".info-action").size() == 6);
                FxWait.settled();
                List<Integer> rows = FxWait.fx(() -> {
                    TreeMap<Long, Integer> byRow = new TreeMap<>();
                    for (Node n : pane.lookupAll(".info-action")) {
                        long y = Math.round(n.localToScene(n.getBoundsInLocal()).getMinY());
                        byRow.merge(y, 1, Integer::sum);
                    }
                    return new ArrayList<>(byRow.values());
                });
                if (!rows.equals(List.of(6)) && !rows.equals(List.of(3, 3))) wrong.add(px + "px in " + width + ": " + rows);
                // And the grid holds its rows: what comes after starts below the last one.
                boolean overlaps = FxWait.fx(() -> {
                    double lastIcon = pane.lookupAll(".info-action").stream()
                            .mapToDouble(n -> n.localToScene(n.getBoundsInLocal()).getMaxY()).max().orElse(0);
                    Node grid = pane.lookup(".info-action").getParent();
                    javafx.scene.Parent column = grid.getParent();
                    int i = column.getChildrenUnmodifiable().indexOf(grid);
                    if (i + 1 >= column.getChildrenUnmodifiable().size()) return false;
                    Node next = column.getChildrenUnmodifiable().get(i + 1);
                    return next.localToScene(next.getBoundsInLocal()).getMinY() < lastIcon - 0.5;
                });
                if (overlaps) wrong.add(px + "px in " + width + ": the next section overlaps the icons");
                FxWait.fx(() -> { stage[0].close(); return null; });
            }
        }
        svc.stop();
        assertTrue(wrong.isEmpty(), "rows per size and width: " + wrong);
    }
}

package app.unichat.matrix;

import app.unichat.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static app.unichat.matrix.RestServer.ME;
import static app.unichat.matrix.RestServer.channel;
import static app.unichat.matrix.RestServer.message;
import static app.unichat.matrix.RestServer.sync;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A small catch-up (not limited) brings events older than some already there: they go in their time's place. */
class TimelineOrderTest {
    @Test
    void anOlderEventGoesBeforeWhatsNewer() throws Exception {
        SyncProcessor proc = new SyncProcessor(ME);
        String a = message("$a", "@discord_1:example.org", "uno", 1000);
        String c = message("$c", "@discord_1:example.org", "tres", 3000);
        String b = message("$b", "@discord_1:example.org", "dos", 2000);
        synchronized (proc) {
            proc.process(MatrixClient.JSON.readTree(sync(channel("!c:x", "c")).replace("\"timeline\":{\"events\":[]}",
                    "\"timeline\":{\"events\":[" + a + "," + c + "]}")), false);
            proc.process(MatrixClient.JSON.readTree("{\"rooms\":{\"join\":{\"!c:x\":{\"timeline\":{\"events\":[" + b + "]}}}}}"), false);
            assertEquals(List.of("$a", "$b", "$c"), proc.room("!c:x").timeline.stream().map(Message::id).toList());
        }
    }
}

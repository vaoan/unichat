package app.unichat.ui.chats;

import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.service.OlderChats;
import app.unichat.support.I18n;
import javafx.scene.Node;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What the rail's network menu and the command bar ask of "Load older chats": is there a step to run for these
 * networks, is a run under way, and open the warning sheet ({@link OlderChatsSheet}) for them. Nothing loads from here.
 */
public final class OlderChatsLauncher {
    private final OlderChats older;
    private final OlderChatsSheet sheet;

    private OlderChatsLauncher(OlderChats older, OlderChatsSheet sheet) {
        this.older = older;
        this.sheet = sheet;
    }

    /** For a service that has older chats, with the window's overlay ({@code show} and {@code hide}); else none. */
    public static @Nullable OlderChatsLauncher of(ChatService svc, Consumer<Node> show, Runnable hide) {
        return svc instanceof OlderChats older ? new OlderChatsLauncher(older, new OlderChatsSheet(show, hide)) : null;
    }

    /** Some of {@code nets} has a step to run. */
    public boolean hasStep(Collection<Network> nets) {
        return !nets.isEmpty() && !older.plans(nets).isEmpty();
    }

    /** A run is under way. */
    public boolean running() {
        OlderChats.Loading l = older.loading();
        return l != null && l.stage() != OlderChats.Stage.DONE;
    }

    /**
     * The command bar's "Load older chats", for {@code nets} read as the bar opens: only while some of them has a step
     * and no run is under way (the bar has no disabled entries).
     */
    public List<CommandBar.Command> commands(Supplier<List<Network>> nets) {
        List<Network> now = nets.get();
        if (running() || !hasStep(now)) return List.of();
        return List.of(new CommandBar.Command(I18n.t("olderChats.menu"), "", () -> open(now)));
    }

    /** The warning sheet for {@code nets}; Load runs it. */
    public void open(Collection<Network> nets) {
        sheet.open(older.plans(nets), run -> { var _ = older.load(run); });
    }
}

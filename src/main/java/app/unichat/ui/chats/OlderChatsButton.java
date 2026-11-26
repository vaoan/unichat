package app.unichat.ui.chats;

import app.unichat.model.Network;
import app.unichat.service.OlderChats;
import app.unichat.service.OlderChats.Loading;
import app.unichat.support.I18n;
import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Nodes;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Load older chats", a plain row at the end of the inbox: shown while a network in view has a step to run ({@code plans}), disabled and
 * saying so while a run is under way. It only opens the warning sheet; nothing loads from here.
 */
final class OlderChatsButton extends Button {
    private final OlderChats svc;
    /** A run's progress comes from the service's thread: one redraw per frame. */
    private final FxCoalescer redraw = new FxCoalescer(this::redraw);
    private final Runnable listener = redraw::request;
    private Consumer<Collection<Network>> open = nets -> { };
    private List<Network> inView = List.of();
    private boolean inInbox;

    @SuppressWarnings("this-escape") // a JavaFX node styles and fills itself as it's built
    OlderChatsButton(OlderChats svc) {
        this.svc = svc;
        getStyleClass().add("older-chats-button");
        setMaxWidth(Double.MAX_VALUE);
        setOnAction(e -> {
            if (!inView.isEmpty() && !running()) open.accept(inView);
        });
        // Enter as well as Space and a click, on every system.
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                fire();
                e.consume();
            }
        });
        svc.addLoadingListener(listener);
        Nodes.show(this, false);
    }

    void setOnOpen(Consumer<Collection<Network>> action) { this.open = action; }

    /** {@code nets}: the networks in view; {@code inbox}: the inbox list is what shows (not a search, folder, server…). */
    void show(List<Network> nets, boolean inbox) {
        inView = List.copyOf(nets);
        inInbox = inbox;
        redraw();
    }

    void dispose() { svc.removeLoadingListener(listener); }

    private boolean running() {
        Loading l = svc.loading();
        return l != null && l.stage() != OlderChats.Stage.DONE;
    }

    /** Shown while what a click would do has a network to ask (or a run goes on), named for a screen reader. */
    private void redraw() {
        boolean running = running();
        List<OlderChats.Plan> plans = inInbox && !inView.isEmpty() ? svc.plans(inView) : List.of();
        Nodes.show(this, inInbox && !inView.isEmpty() && (running || !plans.isEmpty()));
        setDisable(running);
        setText(I18n.t(running ? "olderChats.button.loading" : "olderChats.button"));
        setAccessibleText(running ? getText() : I18n.t("olderChats.button.a11y", names(plans)));
    }

    /** The networks a click would ask, in the order they'd run. */
    private static String names(List<OlderChats.Plan> plans) {
        return String.join(", ", plans.stream().map(p -> p.network().displayName()).toList());
    }
}

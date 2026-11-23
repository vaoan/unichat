package app.unichat.ui.emoji;

import app.unichat.model.MediaLibrary;
import app.unichat.model.StickerPack;
import app.unichat.service.StickerSets;
import app.unichat.support.I18n;
import app.unichat.ui.Icons;
import app.unichat.ui.Labels;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The Stickers tab's sets (Telegram's, through its bridge): the person's own, then any opened from one of its stickers.
 * Each is a header that opens it; a set is fetched the first time it opens and kept while the window lives.
 */
final class StickerSetSection {
    /** How long an empty answer stands (no Telegram yet) before asking again on the next refresh. */
    private static final long ASK_AGAIN_MS = 60_000;

    private final StickerSets svc;
    private final Runnable refresh;
    private List<StickerPack.Ref> yours;
    private long askedAt;
    private boolean asking;
    /** Sets opened from a sticker that aren't among yours, newest first. */
    private final List<StickerPack.Ref> others = new ArrayList<>();
    private final Map<String, StickerPack> packs = new HashMap<>();
    private final Set<String> open = new HashSet<>(), loading = new HashSet<>();
    /** Sets that couldn't be brought, and when: searching tries one again after {@link #ASK_AGAIN_MS} (the bridge wasn't ready). */
    private final Map<String, Long> failed = new HashMap<>();
    /** The clock failures are timed by (a test moves it). */
    java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** Sets are brought for searching one after another: each is asked of Telegram sticker by sticker. */
    private boolean bringingAll;
    private String scrollTo;

    StickerSetSection(StickerSets svc, Runnable refresh) {
        this.svc = svc;
        this.refresh = refresh;
    }

    /**
     * The headers, and under each open one its stickers made by {@code tile} (item, what it is); the header to bring
     * into view goes to {@code show}.
     */
    List<Node> nodes(BiFunction<MediaLibrary.Item, String, Node> tile, java.util.function.Consumer<Node> show) {
        askForYours();
        List<StickerPack.Ref> shown = new ArrayList<>(others);
        if (yours != null) for (StickerPack.Ref r : yours) if (shown.stream().noneMatch(s -> s.url().equals(r.url()))) shown.add(r);
        List<Node> out = new ArrayList<>();
        if (shown.isEmpty()) return out;
        Label title = new Label(I18n.t("sticker.sets"));
        title.getStyleClass().add("emoji-category");
        out.add(title);
        for (StickerPack.Ref r : shown) {
            String name = packs.containsKey(r.url()) ? packs.get(r.url()).title() : r.title();
            boolean isOpen = open.contains(r.url());
            Button head = new Button(name, Icons.icon(isOpen ? Icons.CHEVRON_DOWN : Icons.CHEVRON_RIGHT, 14));
            head.setMnemonicParsing(false); // a set's name is its owner's: "otro_set" lost its underscore as a shortcut mark
            head.getStyleClass().add("sticker-set");
            head.setAccessibleText(I18n.t(isOpen ? "sticker.setOpenA11y" : "sticker.setClosedA11y", name));
            head.setOnAction(e -> {
                if (!open.remove(r.url())) open.add(r.url());
                failed.remove(r.url());
                refresh.run();
            });
            out.add(head);
            if (isOpen) out.add(body(r.url(), name, tile));
            if (r.url().equals(scrollTo)) {
                show.accept(head);
                if (packs.containsKey(r.url()) || failed.containsKey(r.url())) scrollTo = null;
            }
        }
        return out;
    }

    /**
     * Brings every set of yours (and any opened) not here yet, for searching them, one after another; each refreshes as
     * it comes. One that failed is tried again once a while has passed.
     */
    void loadAll() {
        askForYours();
        if (bringingAll) return;
        String next = missing();
        if (next == null) return;
        bringingAll = true;
        load(next, () -> {
            bringingAll = false;
            loadAll();
        });
    }

    /** The first set listed that isn't here, being brought, or failed a moment ago; null when none. */
    private String missing() {
        List<StickerPack.Ref> all = new ArrayList<>(others);
        if (yours != null) all.addAll(yours);
        long now = clock.getAsLong();
        for (StickerPack.Ref r : all) {
            Long at = failed.get(r.url());
            if (!packs.containsKey(r.url()) && !loading.contains(r.url()) && (at == null || now - at >= ASK_AGAIN_MS)) return r.url();
        }
        return null;
    }

    /** The sets brought so far: yours first, then others opened. */
    List<StickerPack> loaded() {
        List<StickerPack> out = new ArrayList<>();
        if (yours != null) for (StickerPack.Ref r : yours) if (packs.containsKey(r.url())) out.add(packs.get(r.url()));
        for (StickerPack.Ref r : others) if (packs.containsKey(r.url()) && !out.contains(packs.get(r.url()))) out.add(packs.get(r.url()));
        return out;
    }

    /** Opens {@code url}'s set and brings it into view, adding it if it isn't one of yours. */
    void show(String url) {
        if (!StickerPack.isLink(url)) return;
        open.add(url);
        failed.remove(url);
        scrollTo = url;
        boolean yoursHasIt = yours != null && yours.stream().anyMatch(r -> r.url().equals(url));
        if (!yoursHasIt && others.stream().noneMatch(r -> r.url().equals(url))) {
            others.add(0, new StickerPack.Ref(url.substring(url.lastIndexOf('/') + 1), url));
        }
        refresh.run();
    }

    private Node body(String url, String name, BiFunction<MediaLibrary.Item, String, Node> tile) {
        StickerPack p = packs.get(url);
        if (p == null) {
            if (failed.containsKey(url)) return Labels.help(I18n.t("sticker.setFailed"));
            load(url);
            return Labels.help(I18n.t("sticker.setLoading"));
        }
        FlowPane grid = new FlowPane(6, 6);
        for (MediaLibrary.Item i : p.stickers()) {
            grid.getChildren().add(tile.apply(i, inSet(i, name)));
        }
        return grid;
    }

    /** What a sticker of the set {@code name} is, for a screen reader: its emoji and its set. */
    static String inSet(MediaLibrary.Item i, String name) {
        return StickerFinder.says("library.stickerInSet", i, name);
    }

    private void load(String url) {
        load(url, () -> { });
    }

    /** Brings {@code url}'s set, then runs {@code then}, whether it came or not. */
    private void load(String url, Runnable then) {
        if (!loading.add(url)) {
            then.run();
            return;
        }
        var _ = svc.stickerPack(url).whenComplete((p, err) -> Platform.runLater(() -> {
            loading.remove(url);
            if (err != null) {
                System.err.println("Sticker set not fetched: " + err);
                failed.put(url, clock.getAsLong());
            } else {
                failed.remove(url);
                packs.put(url, p);
            }
            refresh.run();
            then.run();
        }));
    }

    private void askForYours() {
        if (asking || (yours != null && (!yours.isEmpty() || System.currentTimeMillis() - askedAt < ASK_AGAIN_MS))) return;
        asking = true;
        var _ = svc.stickerPacks().whenComplete((l, err) -> Platform.runLater(() -> {
            if (err != null) System.err.println("Sticker sets not listed: " + err);
            asking = false;
            askedAt = System.currentTimeMillis();
            boolean changed = l != null && !l.isEmpty();
            yours = l == null ? List.of() : l;
            if (changed) refresh.run();
        }));
    }
}

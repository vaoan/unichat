package app.unichat.server;

import java.util.Map;

/**
 * The environment a service is started with. The mautrix bridges are Go programs (downloaded or built by Unichat, see
 * {@code versions.env}); Go's collector lets the heap double before it runs (GOGC=100) and keeps what it freed, so they
 * sat at 19-113 MB each, ~330 MB for seven (measured 2026-11-26). GOGC=50 collects at +50 %, and GOMEMLIMIT is a soft
 * limit: past it the runtime collects harder and gives memory back, it never stops the bridge or fails an allocation.
 * GOMEMLIMIT counts the Go runtime's own memory, not RSS, and with GOGC=50 the heap goal is live x 1.5: below that the
 * collector would run back to back. So the limits are margins above the sizes measured, not tuned values: Discord's live
 * heap is ~60-90 MB, and Telegram and WhatsApp spike while syncing history.
 * tuwunel is Rust: Go's variables mean nothing to it, so it is given none.
 */
final class BridgeEnv {
    private static final String BIG = "256MiB";
    private static final String SMALL = "96MiB";

    private BridgeEnv() {}

    /** What {@code name} (a bridge, or {@code tuwunel}) runs with. */
    static Map<String, String> forService(String name) {
        if (name.equals("tuwunel")) return Map.of();
        // Discord was 113 MB RSS, the others at most 35: it keeps every guild's state.
        return Map.of("GOGC", "50", "GOMEMLIMIT", name.equals("discord") ? BIG : SMALL);
    }
}

package app.unichat.matrix;

import app.unichat.model.Network;
import app.unichat.bridge.BridgeStates;

/** Makes a network's bridge available before signing in to it (the local server downloads and starts it). */
@FunctionalInterface
public interface BridgeProvisioner {
    void ensure(Network network) throws Exception;

    /** Whether this provisioner can install {@code network}'s bridge (it's offered in Cuentas before it exists). */
    default boolean provides(Network network) { return true; }

    /** Base URL of the bridge's own login API ({@code .../_matrix/provision}), or null when unreachable. */
    default String provisioningUrl(Network network) { return null; }

    /** What the bridges report live about their accounts (the local server); null when unknown. */
    default BridgeStates states() { return null; }
}

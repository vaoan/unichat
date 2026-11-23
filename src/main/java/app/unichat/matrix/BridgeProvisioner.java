package app.unichat.matrix;

import app.unichat.model.Network;
import app.unichat.bridge.BridgeStates;

/** Makes a network's bridge available before signing in to it (the local server downloads and starts it). */
@FunctionalInterface
public interface BridgeProvisioner {
    void ensure(Network network) throws Exception; // NOPMD: SignatureDeclareThrowsException, a callback: the local server installs and starts a bridge, failing as it does

    /** Whether this provisioner can install {@code network}'s bridge (it's offered in Cuentas before it exists). */
    default boolean provides(Network network) { return true; }

    /** Base URL of the bridge's own login API ({@code .../_matrix/provision}), or null when unreachable. */
    default String provisioningUrl(Network network) { return null; }

    /** What the bridges report live about their accounts (the local server); null when unknown. */
    default BridgeStates states() { return null; }

    /** The person logged out of {@code network}'s last account and its bridge confirmed it: it needn't run. */
    default void loggedOut(Network network) { /* nothing by default */ }
}

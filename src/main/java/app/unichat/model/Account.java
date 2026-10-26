package app.unichat.model;

/**
 * A connected network account (one per bridge login). A network can have several.
 *
 * @param id     stable id of the login, unique across networks (e.g. {@code whatsapp:573001112233}).
 *               Chats point to it through {@link Chat#accountId()}.
 * @param label  what identifies it to the user, e.g. a phone number or username.
 * @param detail extra status text from the bridge, may be null.
 */
public record Account(String id, Network network, String label, AccountStatus status, String detail) {

    /** Single-account form: the id is the network itself (used when the bridge doesn't expose login ids). */
    public Account(Network network, String label, AccountStatus status, String detail) {
        this(network.protocolId(), network, label, status, detail);
    }
}

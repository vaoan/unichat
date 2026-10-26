package app.unichat.model;

/**
 * A connected network account (one per bridge login).
 * {@code label} is what identifies it to the user, e.g. a phone number or username.
 */
public record Account(Network network, String label, AccountStatus status, String detail) {}

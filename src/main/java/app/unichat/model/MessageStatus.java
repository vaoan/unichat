package app.unichat.model;

/** Delivery state of an outgoing message; incoming messages are always {@link #SENT}. */
public enum MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }

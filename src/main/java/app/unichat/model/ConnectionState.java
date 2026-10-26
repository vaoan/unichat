package app.unichat.model;

/** State of the link to the backend (Matrix homeserver or demo). */
public enum ConnectionState { CONNECTING, SYNCING, ONLINE, OFFLINE, LOGGED_OUT }

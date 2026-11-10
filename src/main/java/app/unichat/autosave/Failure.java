package app.unichat.autosave;

/**
 * Why something wasn't saved, in the person's words, and whether trying again can help: {@code permanent} when it's
 * gone (deleted, expired, a 404) or the site says it never will be; otherwise (no space, the network, a tool that
 * failed) saving as it arrives tries it again at the chat's next change.
 */
record Failure(String why, boolean permanent) {
    static Failure forGood(String why) {
        return new Failure(why, true);
    }

    static Failure forNow(String why) {
        return new Failure(why, false);
    }
}

package app.unichat.service;

/** How a file goes to a chat. */
public enum SendAs {
    /** As it is: the network decides (a big picture may arrive as a file). */
    AS_IS,
    /**
     * A picture as a photo: made into a JPEG the networks show as one (Telegram takes up to ~16 megapixels and 10 MB
     * as a photo, anything else as a file; WebP and HEIC are no photo anywhere). Videos and GIFs go as they are.
     */
    PHOTO,
    /** The original, never shown as a photo or video (WhatsApp's and Telegram's "document"). */
    DOCUMENT,
    /**
     * A video, GIF or animated sticker as a moving picture: an animated WebP sent as a picture, which plays by itself and
     * silently where a video would wait to be pressed (Discord, WhatsApp).
     */
    MOVING
}

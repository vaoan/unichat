package app.unichat.service;

import org.jspecify.annotations.Nullable;

/** The identity a chat keeps across installations: the same chat on a new server has a different room id. */
public final class ChatKeys {
    private ChatKeys() {}

    /** "&lt;protocol&gt;:&lt;channel&gt;" when both are known, else "matrix:&lt;roomId&gt;". */
    public static String of(@Nullable String protocol, @Nullable String channelId, String roomId) {
        boolean bridged = protocol != null && !protocol.isBlank() && channelId != null && !channelId.isBlank();
        return bridged ? protocol + ":" + channelId : "matrix:" + roomId;
    }
}

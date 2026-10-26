package app.unichat.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A conversation from any network. Mutable: services update it in place and then fire
 * {@link app.unichat.service.ChatService.Listener#chatsChanged}.
 */
public final class Chat {
    private final String id;
    private Network network = Network.MATRIX;
    private String title = "";
    private String avatarUrl;
    private boolean group;
    private int unreadCount;
    private int mentionCount;
    private boolean markedUnread;
    private boolean favorite;
    private boolean lowPriority;
    private boolean archived;
    private boolean muted;
    private boolean pinned;
    private Instant reminderAt;
    private Instant lastActivity = Instant.EPOCH;
    private String lastPreview = "";
    private String lastSenderName;
    private boolean lastOutgoing;
    private String topic;
    /** True for the DM with a bridge bot (account management), hidden from the inbox. */
    private boolean bridgeBot;
    private final List<Member> members = new ArrayList<>();
    private final Set<String> typing = new LinkedHashSet<>();

    public Chat(String id) { this.id = id; }

    public String id() { return id; }
    public Network network() { return network; }
    public void setNetwork(Network network) { this.network = network == null ? Network.MATRIX : network; }
    public String title() { return title; }
    public void setTitle(String title) { this.title = title == null ? "" : title; }
    public String avatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public boolean group() { return group; }
    public void setGroup(boolean group) { this.group = group; }
    public int unreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = Math.max(0, unreadCount); }
    public int mentionCount() { return mentionCount; }
    public void setMentionCount(int mentionCount) { this.mentionCount = Math.max(0, mentionCount); }
    public boolean markedUnread() { return markedUnread; }
    public void setMarkedUnread(boolean markedUnread) { this.markedUnread = markedUnread; }
    public boolean favorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    public boolean lowPriority() { return lowPriority; }
    public void setLowPriority(boolean lowPriority) { this.lowPriority = lowPriority; }
    public boolean archived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public boolean muted() { return muted; }
    public void setMuted(boolean muted) { this.muted = muted; }
    public boolean pinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public Instant reminderAt() { return reminderAt; }
    public void setReminderAt(Instant reminderAt) { this.reminderAt = reminderAt; }
    public Instant lastActivity() { return lastActivity; }
    public void setLastActivity(Instant lastActivity) { this.lastActivity = lastActivity == null ? Instant.EPOCH : lastActivity; }
    public String lastPreview() { return lastPreview; }
    public void setLastPreview(String lastPreview) { this.lastPreview = lastPreview == null ? "" : lastPreview; }
    public String lastSenderName() { return lastSenderName; }
    public void setLastSenderName(String lastSenderName) { this.lastSenderName = lastSenderName; }
    public boolean lastOutgoing() { return lastOutgoing; }
    public void setLastOutgoing(boolean lastOutgoing) { this.lastOutgoing = lastOutgoing; }
    public String topic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public boolean bridgeBot() { return bridgeBot; }
    public void setBridgeBot(boolean bridgeBot) { this.bridgeBot = bridgeBot; }
    public List<Member> members() { return members; }
    /** Display names of members currently typing. */
    public Set<String> typing() { return typing; }

    public boolean isUnread() { return unreadCount > 0 || markedUnread; }

    /** Updates the inbox preview fields from the newest message. */
    public void applyLastMessage(Message m) {
        lastActivity = m.timestamp();
        lastPreview = m.preview();
        lastSenderName = m.senderName();
        lastOutgoing = m.outgoing();
    }
}

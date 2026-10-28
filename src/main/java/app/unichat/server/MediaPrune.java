package app.unichat.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps Discord's attachments from filling the disk: mautrix-discord copies every image and video of every channel to
 * the server at full size, and nothing ever removes them. Old ones are dropped from the bridge's media cache here
 * (so it downloads them again if asked) and tuwunel deletes the files ({@link #command}). Avatars and emoji are kept:
 * they're uploaded once and never again.
 */
final class MediaPrune {
    /** Smaller files (stickers, small images) aren't worth it. */
    static final long MIN_SIZE = 100_000;
    /** At most this many per start, so the command line stays reasonable; the rest go next time. */
    static final int MAX_PER_RUN = 2000;

    private static final String OLD_ATTACHMENTS = """
            FROM discord_file WHERE emoji_name IS NULL AND size >= ? AND timestamp < ?
              AND mxc NOT IN (SELECT avatar_url FROM puppet WHERE avatar_url IS NOT NULL
                              UNION SELECT avatar_url FROM guild WHERE avatar_url IS NOT NULL
                              UNION SELECT avatar_url FROM portal WHERE avatar_url IS NOT NULL)""";

    private MediaPrune() {}

    /**
     * Takes the attachments older than {@code keep} out of the Discord bridge's cache ({@code discord.db}; the bridge
     * must not be running) and returns their mxc:// URIs for {@link #command}. Empty when there's no bridge database,
     * nothing old, or {@code keep} is null (keep them forever).
     */
    static List<String> takeOldDiscordAttachments(Path discordDb, Duration keep, Instant now) {
        List<String> out = new ArrayList<>();
        if (keep == null || !Files.isRegularFile(discordDb)) return out;
        long cutoff = now.minus(keep).toEpochMilli();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + discordDb.toAbsolutePath())) {
            c.setAutoCommit(false);
            try (PreparedStatement q = c.prepareStatement("SELECT DISTINCT mxc " + OLD_ATTACHMENTS + " LIMIT " + MAX_PER_RUN)) {
                q.setLong(1, MIN_SIZE);
                q.setLong(2, cutoff);
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            if (out.isEmpty()) return out;
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TEMP TABLE gone(mxc TEXT PRIMARY KEY)");
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT OR IGNORE INTO gone VALUES (?)")) {
                for (String m : out) {
                    ins.setString(1, m);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM discord_file WHERE mxc IN (SELECT mxc FROM gone)");
            }
            c.commit();
        } catch (SQLException e) {
            // An older bridge schema or a locked file: nothing is deleted this time.
            System.err.println("Discord media cleanup skipped: " + e.getMessage());
            out.clear();
        }
        return out;
    }

    /** tuwunel console command deleting {@code mxcs} (files and database entries; unknown ones are ignored). */
    static String command(List<String> mxcs) {
        return "media delete-list\n```\n" + String.join("\n", mxcs) + "\n```";
    }
}

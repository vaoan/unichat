package app.unichat.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What chats saved goes up to the account, each chat's folder as a folder of {@link CloudAccount#MEDIA}. Not kept in
 * sync: a file goes up once (remembered in {@code cloud-uploaded.txt}) and nothing there is ever replaced: the names
 * carry the message's id, so a name already there is that same file. Files being written (hidden, ".part") wait.
 */
final class MediaUpload {
    private final Path record;
    private final Supplier<List<Path>> folders;
    private final Set<String> done = new LinkedHashSet<>();
    private boolean loaded;

    /** @param folders where chats save, each one's name its folder in the account */
    MediaUpload(Path record, Supplier<List<Path>> folders) {
        this.record = record;
        this.folders = folders;
    }

    /** Uploads what's missing; how many went up. Stops at the first failure: what went up stays remembered. */
    int run(Storage dav) throws IOException {
        load();
        int sent = 0;
        for (Path folder : folders.get()) {
            for (Path file : waiting(folder)) {
                String dir = CloudAccount.MEDIA + "/" + folder.getFileName();
                String remote = dir + "/" + file.getFileName();
                if (!dav.exists(remote)) {
                    dav.mkdirs(dir);
                    dav.put(remote, file);
                    sent++;
                }
                remember(folder.getFileName() + "/" + file.getFileName());
            }
        }
        return sent;
    }

    /** How many saved files haven't gone up yet. */
    int waiting() throws IOException {
        load();
        int n = 0;
        for (Path folder : folders.get()) n += waiting(folder).size();
        return n;
    }

    private List<Path> waiting(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        List<Path> out = new java.util.ArrayList<>();
        try (DirectoryStream<Path> in = Files.newDirectoryStream(folder)) {
            for (Path p : in) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p) || name.startsWith(".") || name.endsWith(".part") || name.endsWith(".ytdl")) continue;
                if (!done.contains(folder.getFileName() + "/" + name)) out.add(p);
            }
        }
        out.sort(null);
        return out;
    }

    private void load() throws IOException {
        if (loaded) return;
        if (Files.exists(record)) done.addAll(Files.readAllLines(record, StandardCharsets.UTF_8));
        loaded = true;
    }

    private void remember(String entry) throws IOException {
        if (!done.add(entry)) return;
        Files.createDirectories(record.getParent());
        Files.writeString(record, entry + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}

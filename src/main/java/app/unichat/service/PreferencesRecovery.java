package app.unichat.service;

import app.unichat.support.SafeFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The way back for {@code preferences.json}: the {@code .bak} each save keeps of the version it replaces, and a damaged
 * file kept apart ({@code .bad-<time>}) before anything can write over it. Not thread-safe: {@link PreferencesFile}'s
 * lock guards it.
 */
final class PreferencesRecovery {
    private final Path file;
    private String setAside;
    private boolean recovered;
    /** The damaged file couldn't be kept apart yet: nothing may write over it. */
    private boolean blocked;
    /** The main file is still the damaged one, and memory holds what came from the backup: it must not become the backup. */
    private boolean damagedInPlace;

    PreferencesRecovery(Path file) { this.file = file; }

    static Path backupOf(Path target) { return target.resolveSibling(target.getFileName() + ".bak"); }

    /**
     * The main file couldn't be read: it's kept apart now if it can be.
     * @param fromBackup memory holds what the backup had
     */
    void damaged(boolean fromBackup) {
        recovered = fromBackup;
        blocked = true;
        setAsideBadFile();
    }

    /** Tries again to keep the damaged file apart, if that's still pending. */
    void retry() {
        if (blocked) setAsideBadFile();
    }

    /** True while the damaged file couldn't be kept apart: nothing is written over it. */
    boolean blocked() { return blocked; }

    boolean recovered() { return recovered; }

    /** The name the damaged file was kept under, or null. */
    String setAside() { return setAside; }

    /** Whether the next save keeps the file it replaces as the backup: not while that file is the damaged one. */
    boolean keepsPrevious() { return !damagedInPlace; }

    /** The main file was written: whatever was damaged is gone from it. */
    void replaced() { damagedInPlace = false; }

    /** A backup that can't be made (Windows may hold the file) only costs the backup: the save goes on. */
    static void keepPrevious(Path target) {
        try {
            if (Files.exists(target)) SafeFile.replace(backupOf(target), Files.readAllBytes(target), false);
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not back up " + target.getFileName() + ": " + e);
        }
    }

    /**
     * Keeps the unreadable file so the next write can't destroy it. With nothing recovered it's renamed. With a recovery
     * it's copied, and stays until the recovered content replaces it: if that never happens, the next start recovers again.
     */
    private void setAsideBadFile() {
        try {
            if (recovered) {
                setAside = copyBadFile();
                damagedInPlace = true;
            } else {
                String name = freeAsideName();
                Files.move(file, file.resolveSibling(name));
                setAside = name;
            }
            blocked = false;
        } catch (IOException e) {
            System.err.println("Could not set aside the unreadable preferences: " + e);
        }
    }

    /** The name of the copy on disk; repeated failed recoveries of the same damage share one. */
    private String copyBadFile() throws IOException {
        try (var copies = Files.newDirectoryStream(file.resolveSibling(""), file.getFileName() + ".bad-*")) {
            for (Path copy : copies) {
                if (Files.isRegularFile(copy) && Files.mismatch(file, copy) == -1) return copy.getFileName().toString();
            }
        }
        String name = freeAsideName();
        SafeFile.at(file.resolveSibling(name)).write(Files.readAllBytes(file));
        return name;
    }

    private String freeAsideName() {
        String stamp = file.getFileName() + ".bad-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now());
        String name = stamp;
        for (int n = 2; Files.exists(file.resolveSibling(name)); n++) name = stamp + "-" + n;
        return name;
    }
}

package app.unichat.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lets {@code verify} skip SpotBugs on a repeat of a run that passed, the one skip the build allows (BuildRulesTest):
 * SpotBugs has no cache of its own and took a minute on every build. Run by the pom with exec:java, on the tests'
 * classpath. {@code check} hashes all SpotBugs reads (the classes, the tests' classes, {@code pom.xml}, the filter, the
 * libraries and the JDK) and writes {@code spotbugs.skip}, true only when that hash is the one {@code passed} wrote
 * after SpotBugs last passed; {@code passed} runs after SpotBugs and marks only a run where it really analysed these
 * inputs and found nothing (its own report, newer than the check, with no finding and no error).
 */
public final class SpotBugsCache {
    // The hash of this run's inputs, and the one a pass then marks.
    static final String INPUTS = "inputs.sha256";
    static final String PASSED = "passed.sha256";

    private SpotBugsCache() {}

    /**
     * What Maven reads (properties-maven-plugin) in run {@code run}: one file per run, so a run whose check didn't run
     * (exec skipped) finds none and stops, instead of reading an earlier run's "skip".
     */
    static String decision(String run) {
        return "skip-" + run + ".properties";
    }

    /** {@code check <project dir> <run>} or {@code passed <project dir> <run>}; the run is the build's start time. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("check|passed <project dir> <run>");
        Path project = Path.of(args[1]);
        String run = args[2];
        Path cache = project.resolve("target/spotbugs-cache");
        switch (args[0]) {
            case "check" -> System.out.println("SpotBugs " + (check(project, cache, classpath(), run) ? "skipped: nothing it reads changed since"
                    + " it last passed" : "runs: what it reads changed, or it hasn't passed on it yet"));
            case "passed" -> passed(project, cache, run);
            default -> throw new IllegalArgumentException("check|passed, not " + args[0]);
        }
    }

    /** Whether SpotBugs may skip: the inputs' hash is the one marked after its last pass. Writes the hash and the decision. */
    static boolean check(Path project, Path cache, List<String> classpath, String run) throws IOException {
        String inputs = hash(project, classpath);
        Files.createDirectories(cache);
        Path passed = cache.resolve(PASSED);
        boolean skip = Files.isDirectory(project.resolve("target/classes")) && Files.isRegularFile(passed)
                && Files.readString(passed).equals(inputs);
        Files.writeString(cache.resolve(INPUTS), inputs);
        try (Stream<Path> old = Files.list(cache)) {
            for (Path p : old.filter(p -> app.unichat.support.PathParts.name(p).startsWith("skip-")).toList()) Files.delete(p);
        }
        Files.writeString(cache.resolve(decision(run)), "spotbugs.skip=" + skip + "\n");
        return skip;
    }

    /**
     * After SpotBugs, in verify: the inputs this run checked are the ones a repeat may skip on, only when SpotBugs really
     * analysed them here and found nothing. A run that skipped by hand ({@code -Dspotbugs.skip}), went on past findings
     * ({@code -Dspotbugs.failOnError=false}) or had analysis errors marks nothing; neither does one that skipped as
     * cached (its mark is already these inputs').
     */
    static void passed(Path project, Path cache, String run) throws IOException {
        Path inputs = cache.resolve(INPUTS);
        Path decision = cache.resolve(decision(run));
        if (!Files.isRegularFile(inputs) || !Files.isRegularFile(decision)) return;
        if (!Files.readString(decision).equals("spotbugs.skip=false\n")) return;
        Path report = project.resolve("target/spotbugsXml.xml");
        if (!Files.isRegularFile(report)) return;
        if (Files.getLastModifiedTime(report).compareTo(Files.getLastModifiedTime(inputs)) <= 0) return; // an earlier run's
        String xml = Files.readString(report);
        if (xml.contains("<BugInstance") || !NO_ERRORS.matcher(xml).find()) return;
        Files.copy(inputs, cache.resolve(PASSED), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** SpotBugs' report saying its analysis had no errors. */
    private static final java.util.regex.Pattern NO_ERRORS = java.util.regex.Pattern.compile("<Errors\\b[^>]*\\serrors=['\"]0['\"]");

    /** SHA-256 over every file SpotBugs reads (by its path and content), the libraries by name and the JDK's version. */
    static String hash(Path project, List<String> classpath) throws IOException {
        MessageDigest all = sha256();
        for (String dir : List.of("target/classes", "target/test-classes")) {
            Path root = project.resolve(dir);
            if (!Files.isDirectory(root)) continue;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path f : files) add(all, dir + "/" + root.relativize(f).toString().replace('\\', '/'), f);
        }
        for (String file : List.of("pom.xml", "spotbugs-exclude.xml")) add(all, file, project.resolve(file));
        for (String entry : classpath) text(all, "classpath " + entry);
        text(all, "jdk " + Runtime.version());
        return HexFormat.of().formatHex(all.digest());
    }

    private static void add(MessageDigest all, String name, Path file) throws IOException {
        text(all, name);
        if (!Files.isRegularFile(file)) {
            text(all, "missing");
            return;
        }
        MessageDigest one = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) one.update(buffer, 0, n);
        }
        all.update(one.digest());
    }

    private static void text(MessageDigest all, String s) {
        all.update(s.getBytes(StandardCharsets.UTF_8));
        all.update((byte) 0);
    }

    /** The libraries on the classpath exec:java gives (the tests' scope: every one SpotBugs is handed), but the classes. */
    private static List<String> classpath() {
        List<String> out = new ArrayList<>();
        if (Thread.currentThread().getContextClassLoader() instanceof URLClassLoader loader) { // NOPMD: CloseResource, exec:java owns the loader
            for (URL u : loader.getURLs()) {
                try {
                    Path p = Path.of(u.toURI());
                    if (!Files.isDirectory(p)) out.add(p.toString());
                } catch (URISyntaxException | IllegalArgumentException e) {
                    out.add(u.toString());
                }
            }
        } else {
            out.add(System.getProperty("java.class.path", ""));
        }
        return out;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }
}

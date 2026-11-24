package app.unichat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which code NullAway checks ({@code OnlyNullMarked}): every package, main and test, has its {@code package-info.java}
 * marked {@code @NullMarked} (a package with main code in {@code src/main/java}, a package only tests have in
 * {@code src/test/java}), so a new package is checked from its first class; one exception, {@link #UNMARKED}.
 * {@code BuildRulesTest} checks NullAway's options.
 */
class NullMarkedTest {
    /** The one package left unmarked, with why: it has no package-info at all, so nothing in it reaches for JSpecify. */
    private static final Map<String, String> UNMARKED = Map.of("app.unichat.claude",
            "run by Java's source launcher with no classpath (.claude/hooks/java.sh); JSpecify isn't on it");

    /** The mark, by its simple or its qualified name, however it's spaced. */
    private static final Pattern MARK = Pattern.compile("@\\s*(org\\s*\\.\\s*jspecify\\s*\\.\\s*annotations\\s*\\.\\s*)?NullMarked\\b");
    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.\\s]+?)\\s*;");
    private static final Pattern IMPORTED = Pattern.compile("\\bimport\\s+org\\s*\\.\\s*jspecify\\s*\\.\\s*annotations\\s*\\.\\s*(NullMarked|\\*)\\s*;");

    /** Whether a {@code package-info.java}'s package declaration carries the mark, in code (not a comment or a string). */
    static boolean marksItsPackage(String text) {
        String code = SuppressionsHaveReasonsTest.code(text);
        Matcher pkg = PACKAGE.matcher(code);
        if (!pkg.find()) return false;
        Matcher mark = MARK.matcher(code.substring(0, pkg.start()));
        if (!mark.find()) return false;
        return mark.group(1) != null || IMPORTED.matcher(code).find();
    }

    /** Whether a class's code (not its comments or strings) carries the mark. */
    static boolean marksAClass(String text) {
        return MARK.matcher(SuppressionsHaveReasonsTest.code(text)).find();
    }

    @Test
    void theMarkIsReadOnThePackageInAnyFormattingAndNeverInAComment() {
        assertTrue(marksItsPackage("/** Doc. */\n@NullMarked\npackage a.b;\n\nimport org.jspecify.annotations.NullMarked;\n"));
        assertTrue(marksItsPackage("@NullMarked package a.b;\nimport org.jspecify.annotations.NullMarked;"));
        assertTrue(marksItsPackage("@org.jspecify.annotations.NullMarked\npackage a.b;"));
        assertTrue(marksItsPackage("@ NullMarked // the whole package\n  package   a.b ;\nimport org.jspecify.annotations.*;"));
        assertTrue(marksItsPackage("@Deprecated @NullMarked\npackage a.b;\nimport org.jspecify.annotations.NullMarked;"));
        assertFalse(marksItsPackage("/* @NullMarked */\npackage a.b;\nimport org.jspecify.annotations.NullMarked;"), "in a comment");
        assertFalse(marksItsPackage("// @NullMarked\npackage a.b;\nimport org.jspecify.annotations.NullMarked;"), "in a line comment");
        assertFalse(marksItsPackage("@NullMarked\npackage a.b;\nimport other.NullMarked;"), "someone else's annotation");
        assertFalse(marksItsPackage("package a.b;\nimport org.jspecify.annotations.NullMarked;"), "imported, not used");
        assertFalse(marksItsPackage(""), "no package-info");
        assertTrue(marksAClass("@NullMarked\nfinal class A {}"));
        assertTrue(marksAClass("class A { @org.jspecify.annotations.NullMarked void f() {} }"));
        assertFalse(marksAClass("/** Like {@code @NullMarked} on its package. */\nclass A { String s = \"@NullMarked\"; }"));
    }

    @Test
    void everyPackageMainAndTestIsNullMarked() throws IOException {
        Set<String> main = BuildRulesTest.mainPackages();
        Set<String> all = new TreeSet<>(main);
        all.addAll(packagesUnder(Path.of("src/test/java")));
        List<String> unmarked = new ArrayList<>();
        for (String pkg : all) {
            if (UNMARKED.containsKey(pkg)) continue;
            Path info = Path.of(main.contains(pkg) ? "src/main/java" : "src/test/java", pkg.split("\\.")).resolve("package-info.java");
            if (!Files.exists(info) || !marksItsPackage(Files.readString(info))) unmarked.add(info.toString());
        }
        assertEquals(List.of(), unmarked, "each package's package-info.java is @NullMarked (each subpackage has its own;"
                + " one only tests have, under src/test/java): NullAway checks only what is marked");
        assertTrue(all.containsAll(List.of("app.unichat", "app.unichat.ui", "app.unichat.build")), "main and test packages are read");
    }

    @Test
    void theUnmarkedPackageHasNoPackageInfo() {
        for (Map.Entry<String, String> unmarked : UNMARKED.entrySet()) {
            String pkg = unmarked.getKey();
            Path folder = Path.of("src/test/java", pkg.split("\\."));
            assertFalse(Files.exists(folder.resolve("package-info.java")), pkg + " has no package-info.java: " + unmarked.getValue());
            assertTrue(Files.isDirectory(folder), pkg + " is still there; once gone, it leaves UNMARKED");
        }
    }

    @Test
    void aPackageIsMarkedOnlyOnce() throws IOException {
        List<String> twice = new ArrayList<>();
        for (String pkg : BuildRulesTest.mainPackages()) {
            Path inTests = Path.of("src/test/java", pkg.split("\\.")).resolve("package-info.java");
            if (Files.exists(inTests)) twice.add(inTests.toString());
        }
        assertEquals(List.of(), twice, "a package with main code is marked in src/main/java alone: its tests share that mark");
    }

    @Test
    void noClassMarksItselfItsPackageDoes() throws IOException {
        List<String> marked = new ArrayList<>();
        for (Path f : javaFiles()) {
            if (!isPackageInfo(f) && marksAClass(Files.readString(f))) marked.add(f.toString());
        }
        assertEquals(List.of(), marked, "a class marked @NullMarked by itself: its package's package-info.java says it for all");
    }

    @Test
    void noClassLeavesNullAwayOut() throws IOException {
        for (Path f : javaFiles()) {
            String text = Files.readString(f);
            assertFalse(text.contains("Null" + "Unmarked"), f + " leaves code out of NullAway: a finding is fixed, or"
                    + " suppressed on one member with its reason");
        }
    }

    private static boolean isPackageInfo(Path f) {
        return "package-info.java".equals(String.valueOf(f.getFileName()));
    }

    /** The packages with a class under {@code root}. */
    private static Set<String> packagesUnder(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java") && !isPackageInfo(f))
                    .map(f -> root.relativize(Objects.requireNonNull(f.getParent(), "a file found under root is in a folder"))
                            .toString().replace(f.getFileSystem().getSeparator(), "."))
                    .collect(TreeSet::new, Set::add, Set::addAll);
        }
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}

package app.unichat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which code NullAway checks ({@code OnlyNullMarked}): the packages done so far, each marked {@code @NullMarked} on its
 * {@code package-info.java}, and nothing marked or left out anywhere else. {@code BuildRulesTest} checks NullAway's options.
 */
class NullMarkedTest {
    /**
     * The packages NullAway checks so far, each with its subpackages: a package joins with its {@code package-info.java}
     * marked {@code @NullMarked}, and its name goes here. Once every package has, this is {@code app.unichat} alone.
     */
    private static final List<String> NULL_MARKED = List.of("app.unichat.support", "app.unichat.model", "app.unichat.settings",
            "app.unichat.service", "app.unichat.cloud", "app.unichat.matrix", "app.unichat.server", "app.unichat.assist",
            "app.unichat.autosave", "app.unichat.bridge", "app.unichat.cloak", "app.unichat.gif", "app.unichat.links", "app.unichat.login",
            "app.unichat.stt", "app.unichat.update", "app.unichat.video", "app.unichat.voice");

    /** The mark, by its simple or its qualified name, however it's spaced. */
    private static final Pattern MARK = Pattern.compile("@\\s*(org\\s*\\.\\s*jspecify\\s*\\.\\s*annotations\\s*\\.\\s*)?NullMarked\\b");
    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.\\s]+?)\\s*;");
    private static final Pattern IMPORTED = Pattern.compile("\\bimport\\s+org\\s*\\.\\s*jspecify\\s*\\.\\s*annotations\\s*\\.\\s*(NullMarked|\\*)\\s*;");

    private static boolean done(String pkg) {
        return NULL_MARKED.stream().anyMatch(d -> pkg.equals(d) || pkg.startsWith(d + "."));
    }

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
    void everyPackageDoneIsNullMarked() throws IOException {
        Set<String> marked = new TreeSet<>();
        for (String pkg : BuildRulesTest.mainPackages()) {
            Path info = Path.of("src/main/java", pkg.split("\\.")).resolve("package-info.java");
            boolean isMarked = Files.exists(info) && marksItsPackage(Files.readString(info));
            if (isMarked) marked.add(pkg);
            assertEquals(done(pkg), isMarked, pkg + (done(pkg) ? " is done, so its package-info.java is @NullMarked (each subpackage"
                    + " has its own)" : " is marked: add it to NULL_MARKED"));
        }
        assertFalse(marked.isEmpty(), "the packages done so far are marked");
    }

    @Test
    void noClassIsMarkedOutsideThePackagesDone() throws IOException {
        List<String> outside = new ArrayList<>();
        for (Path f : javaFiles()) {
            if (f.getFileName().toString().equals("package-info.java")) continue;
            String text = Files.readString(f);
            Matcher pkg = PACKAGE.matcher(SuppressionsHaveReasonsTest.code(text));
            String name = pkg.find() ? pkg.group(1).replaceAll("\\s", "") : "";
            if (!done(name) && marksAClass(text)) outside.add(f.toString());
        }
        assertEquals(List.of(), outside, "a class marked @NullMarked in a package not done: the package joins whole"
                + " (its package-info.java, and NULL_MARKED)");
    }

    @Test
    void noClassLeavesNullAwayOut() throws IOException {
        for (Path f : javaFiles()) {
            String text = Files.readString(f);
            assertFalse(text.contains("Null" + "Unmarked"), f + " leaves code out of NullAway: a finding is fixed, or"
                    + " suppressed on one member with its reason");
        }
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}

package app.unichat;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checks that fail the build stay on: {@code pom.xml} is read as it is, so turning one off quietly (an argument
 * taken out, a rule dropped, a {@code skip}) fails here.
 */
class BuildRulesTest {
    private static final Document POM = read("pom.xml");
    /** The lifecycle's phases up to verify: a check bound to one of them runs in {@code ./mvnw verify}. */
    private static final List<String> BEFORE_INSTALL = List.of("validate", "initialize", "generate-sources", "process-sources",
            "generate-resources", "process-resources", "compile", "process-classes", "generate-test-sources",
            "process-test-sources", "generate-test-resources", "process-test-resources", "test-compile", "process-test-classes",
            "test", "prepare-package", "package", "pre-integration-test", "integration-test", "post-integration-test", "verify");

    @Test
    void everyJavacLintIsAnError() {
        List<String> args = texts(child(configuration("maven-compiler-plugin"), "compilerArgs"), "arg");
        assertTrue(args.contains("-Xlint:all"), "javac reports every lint: " + args);
        assertTrue(args.contains("-Werror"), "javac's warnings fail the build: " + args);
        assertFalse(args.contains("-nowarn") || args.contains("-Xlint:none"), "no lint is silenced as a whole: " + args);
    }

    @Test
    void nothingElseInTheCompilersConfigTurnsThemOff() {
        Element config = configuration("maven-compiler-plugin");
        assertEquals(List.of("showWarnings", "compilerArgs", "annotationProcessorPaths"), childNames(config),
                "the compiler's configuration is the lint, Error Prone and nothing else (no failOnWarning, compilerArgument, ...)");
        assertEquals("true", text(config, "showWarnings"));
    }

    @Test
    void noCompilerExecutionHasAConfigurationOfItsOwn() {
        Element executions = elements(plugin("maven-compiler-plugin"), "executions").stream().findFirst().orElse(null);
        if (executions == null) return;
        for (Element e : elements(executions, "execution")) {
            assertTrue(elements(e, "configuration").isEmpty(), text(e, "id") + " has its own configuration: it would replace"
                    + " the lint and Error Prone for what it compiles (default-testCompile: the tests)");
        }
    }

    @Test
    void aLintTurnedOffSaysWhy() {
        for (Element arg : elements(child(configuration("maven-compiler-plugin"), "compilerArgs"), "arg")) {
            if (!arg.getTextContent().trim().startsWith("-Xlint:-")) continue;
            assertInstanceOf(Comment.class, previous(arg), arg.getTextContent() + " has its reason in a comment just before it");
        }
    }

    @Test
    void errorProneRunsInsideJavac() {
        List<String> args = texts(child(configuration("maven-compiler-plugin"), "compilerArgs"), "arg");
        assertTrue(args.contains("-XDcompilePolicy=simple"), "Error Prone's compile policy: " + args);
        assertTrue(args.contains("--should-stop=ifError=FLOW"), "Error Prone sees every class: " + args);
        assertEquals(1, args.stream().filter(a -> a.startsWith("-Xplugin:ErrorProne")).count(), "the plugin, once: " + args);
        Element path = elements(child(configuration("maven-compiler-plugin"), "annotationProcessorPaths"), "path").stream()
                .filter(p -> "com.google.errorprone".equals(text(p, "groupId")) && "error_prone_core".equals(text(p, "artifactId")))
                .findFirst().orElseThrow(() -> new AssertionError("error_prone_core is on javac's processor path"));
        assertTrue(text(path, "version").matches("\\d+\\.\\d+\\.\\d+"), "pinned, as the Enforcer wants plugins: " + text(path, "version"));
    }

    @Test
    void errorProneTurnsNothingOffQuietly() {
        List<String> flags = errorProneFlags();
        for (String flag : flags) {
            assertTrue(flag.matches("-Xep:\\w+:(ERROR|OFF)"), flag + ": only a check made an error or turned off, nothing that"
                    + " demotes, skips paths or patches (-XepAllErrorsAsWarnings, -XepExcludedPaths, ...)");
        }
        String why = comments(plugin("maven-compiler-plugin"));
        for (String flag : flags) {
            if (!flag.endsWith(":OFF")) continue;
            String check = flag.split(":")[1];
            assertTrue(why.contains(check + ":"), check + " is off with its reason in the comment before the compiler");
        }
    }

    @Test
    void theChecksOffByDefaultThatCatchDefectsStayOn() {
        List<String> flags = errorProneFlags();
        for (String check : List.of("NullableOptional", "FieldCanBeFinal", "UnnecessaryDefaultInEnumSwitch", "UnusedException",
                "UseEnumSwitch", "DefaultLocale", "EqualsBrokenForNull", "TimeUnitMismatch", "PrimitiveArrayPassedToVarargsMethod",
                "ComparisonContractViolated", "TransientMisuse", "VoidUsed", "AssertFalse", "TypeToString", "IterablePathParameter",
                "FunctionalInterfaceClash", "LongLiteralLowerCaseSuffix", "DepAnn")) {
            assertTrue(flags.contains("-Xep:" + check + ":ERROR"), check + " is on: " + flags);
        }
    }

    @Test
    void theEnforcerRulesRun() {
        Element execution = execution("maven-enforcer-plugin", "enforce");
        Element rules = child(child(execution, "configuration"), "rules");
        List<String> names = childNames(rules);
        assertEquals(List.of("requireJavaVersion", "requireMavenVersion", "dependencyConvergence",
                "banDuplicatePomDependencyVersions", "requirePluginVersions", "requireReleaseDeps", "banCircularDependencies"),
                names);
        for (Element rule : elements(rules, null)) {
            assertTrue(elements(rule, "level").isEmpty(), rule.getTagName() + " fails the build, not a warning (<level>)");
        }
        assertEquals("[25,)", child(child(rules, "requireJavaVersion"), "version").getTextContent().trim());
        assertNotEquals("false", text(child(execution, "configuration"), "fail"), "the enforcer fails the build");
    }

    @Test
    void modernizerFailsAtTheProjectsJava() {
        Element config = configuration("modernizer-maven-plugin");
        assertEquals(List.of("javaVersion", "failOnViolations", "includeTestClasses"), childNames(config),
                "Modernizer's configuration has nothing that leaves code out (exclusions, ignorePackages, ...)");
        assertEquals("${maven.compiler.release}", text(config, "javaVersion"));
        assertEquals("true", text(config, "failOnViolations"));
        assertEquals("true", text(config, "includeTestClasses"));
        execution("modernizer-maven-plugin", "modernizer");
    }

    @Test
    void noExecutionIsUnbound() {
        for (String plugin : List.of("maven-compiler-plugin", "maven-enforcer-plugin", "modernizer-maven-plugin", "maven-pmd-plugin",
                "spotbugs-maven-plugin")) {
            Element executions = elements(plugin(plugin), "executions").stream().findFirst().orElse(null);
            if (executions == null) continue;
            for (Element e : elements(executions, "execution")) {
                assertNotEquals("none", text(e, "phase"), plugin + "'s " + text(e, "id") + " runs in the build");
            }
        }
    }

    @Test
    void noProfileChangesTheChecks() {
        for (Element profile : elements(child(POM.getDocumentElement(), "profiles"), "profile")) {
            for (String plugin : List.of("maven-compiler-plugin", "maven-enforcer-plugin", "modernizer-maven-plugin", "maven-pmd-plugin",
                    "spotbugs-maven-plugin", "findsecbugs-plugin", "spotbugs-exclude.xml")) {
                assertFalse(profile.getTextContent().contains(plugin), text(profile, "id") + " leaves " + plugin + " as it is");
            }
        }
    }

    @Test
    void noCheckIsSkipped() {
        assertEquals(0, POM.getElementsByTagName("skip").getLength(), "no <skip> anywhere in pom.xml");
        for (String property : List.of("enforcer.skip", "modernizer.skip", "maven.compiler.failOnWarning", "pmd.skip", "cpd.skip",
                "pmd.failOnViolation", "pmd.failurePriority", "spotbugs.skip", "spotbugs.failOnError", "spotbugs.effort",
                "spotbugs.threshold", "spotbugs.includeTests", "spotbugs.onlyAnalyze", "spotbugs.omitVisitors",
                "spotbugs.maxAllowedViolations", "spotbugs.excludeFilterFile", "spotbugs.classFilesDirectory",
                "spotbugs.testClassFilesDirectory")) {
            assertEquals(0, POM.getElementsByTagName(property).getLength(), property + " isn't set in pom.xml");
        }
    }

    @Test
    void pmdChecksTheWholeRuleSetAndFailsOnAnyFinding() {
        Element execution = execution("maven-pmd-plugin", "check");
        assertTrue(BEFORE_INSTALL.contains(text(execution, "phase")), "PMD runs in verify or before: " + text(execution, "phase"));
        Element config = child(execution, "configuration");
        assertEquals(List.of("pmd-rules.xml"), texts(child(config, "rulesets"), "ruleset"), "the project's rule set, only it");
        assertEquals("true", text(config, "failOnViolation"), "a finding fails the build");
        assertEquals("5", text(config, "failurePriority"), "every priority fails, down to 5");
        assertEquals("true", text(config, "includeTests"), "the tests are checked too");
        assertEquals("false", text(config, "skipPmdError"), "a file PMD can't read fails the build (the plugin skips it by default)");
        assertTrue(elements(configuration("maven-pmd-plugin"), "skipPmdError").isEmpty(), "set once, on the check");
        for (Element c : List.of(config, configuration("maven-pmd-plugin"))) {
            for (String leftOut : List.of("excludes", "excludeRoots", "excludeFromFailureFile", "minimumPriority")) {
                assertTrue(elements(c, leftOut).isEmpty(), "no <" + leftOut + ">: nothing is left out of PMD's sight");
            }
        }
        execution("maven-pmd-plugin", "cpd-check");
    }

    @Test
    void everyPmdRuleLeftOutSaysWhy() {
        Document rules = read("pmd-rules.xml");
        assertEquals(0, rules.getElementsByTagName("exclude-pattern").getLength(), "no file is left out");
        NodeList excludes = rules.getElementsByTagName("exclude");
        assertTrue(excludes.getLength() > 0, "the rule set is read");
        for (int i = 0; i < excludes.getLength(); i++) {
            Element e = (Element) excludes.item(i);
            assertTrue(previous(e) instanceof Comment c && !c.getData().isBlank(),
                    e.getAttribute("name") + " is left out with its reason in a comment just before it");
        }
        NodeList properties = rules.getElementsByTagName("property");
        for (int i = 0; i < properties.getLength(); i++) {
            String name = ((Element) properties.item(i)).getAttribute("name");
            assertFalse(name.startsWith("violationSuppress"), name + ": a suppression is one line's NOPMD, with its reason");
        }
    }

    @Test
    void spotBugsChecksMainAndTestCodeAtFullStrengthWithFindSecBugs() {
        Element execution = execution("spotbugs-maven-plugin", "check");
        assertTrue(BEFORE_INSTALL.contains(text(execution, "phase")), "SpotBugs runs in verify or before: " + text(execution, "phase"));
        Element config = configuration("spotbugs-maven-plugin");
        assertEquals("Max", text(config, "effort"), "every detector at its deepest");
        assertEquals("Low", text(config, "threshold"), "every confidence is reported, the lowest included");
        assertNotEquals("false", text(config, "failOnError"), "a finding fails the build");
        assertEquals("true", text(config, "includeTests"), "the tests are checked too");
        assertEquals("spotbugs-exclude.xml", text(config, "excludeFilterFile"), "only the project's filter leaves anything out");
        Element findSecBugs = elements(child(config, "plugins"), "plugin").stream()
                .filter(p -> "com.h3xstream.findsecbugs".equals(text(p, "groupId")) && "findsecbugs-plugin".equals(text(p, "artifactId")))
                .findFirst().orElseThrow(() -> new AssertionError("FindSecBugs is one of SpotBugs' plugins"));
        assertTrue(text(findSecBugs, "version").matches("\\d+\\.\\d+\\.\\d+"), "pinned: " + text(findSecBugs, "version"));
        List<Element> configs = new ArrayList<>(List.of(config));
        for (Element e : elements(child(plugin("spotbugs-maven-plugin"), "executions"), "execution")) configs.addAll(elements(e, "configuration"));
        for (Element c : configs) {
            for (String weaker : List.of("skip", "onlyAnalyze", "omitVisitors", "visitors", "includeFilterFile", "excludeBugsFile",
                    "excludeBugsFiles", "maxRank", "failThreshold", "maxAllowedViolations", "skipEmptyReport", "classFilesDirectory",
                    "testClassFilesDirectory")) {
                assertTrue(elements(c, weaker).isEmpty(), "no <" + weaker + ">: it would leave code, detectors or findings out");
            }
        }
        for (Element e : elements(child(plugin("spotbugs-maven-plugin"), "executions"), "execution")) {
            for (Element c : elements(e, "configuration")) {
                for (String set : List.of("effort", "threshold", "failOnError", "includeTests", "excludeFilterFile", "plugins")) {
                    assertTrue(elements(c, set).isEmpty(), text(e, "id") + " sets <" + set + "> again: the plugin's own one is the rule");
                }
            }
        }
    }

    @Test
    void everySpotBugsPatternLeftOutSaysWhyAndIsAPatternNotAPlace() {
        Element filter = read("spotbugs-exclude.xml").getDocumentElement();
        List<Element> matches = elements(filter, "Match");
        assertFalse(matches.isEmpty(), "the filter is read");
        assertEquals(matches.size(), elements(filter, null).size(), "the filter holds only Match elements");
        for (Element m : matches) {
            List<Element> bugs = elements(m, "Bug");
            String what = bugs.isEmpty() ? "a Match" : bugs.getFirst().getAttribute("pattern");
            assertTrue(previous(m) instanceof Comment c && !c.getData().isBlank(), what + " is left out with its reason in a comment just before it");
            assertEquals(1, bugs.size(), "each Match names its bug patterns: " + what);
            Element bug = bugs.getFirst();
            assertFalse(bug.getAttribute("pattern").isBlank(), "matched by pattern, never by a whole category or code");
            assertTrue(bug.getAttribute("category").isEmpty() && bug.getAttribute("code").isEmpty(), "no category or code: " + what);
            for (Element e : elements(m, null)) {
                assertTrue(List.of("Bug", "Local").contains(e.getTagName()), "<" + e.getTagName() + "> in a Match: nothing is left out by"
                        + " class, package, file, method or field, only by pattern (and a local's name)");
            }
        }
    }

    /** Error Prone's own options: what follows {@code -Xplugin:ErrorProne} in its argument. */
    private static List<String> errorProneFlags() {
        String arg = texts(child(configuration("maven-compiler-plugin"), "compilerArgs"), "arg").stream()
                .filter(a -> a.startsWith("-Xplugin:ErrorProne")).findFirst().orElseThrow();
        List<String> words = List.of(arg.trim().split("\\s+"));
        return words.subList(1, words.size());
    }

    /** The comments just before {@code n}, together. */
    private static String comments(Node n) {
        StringBuilder out = new StringBuilder();
        for (Node p = previous(n); p instanceof Comment c; p = previous(p)) out.insert(0, c.getData()).insert(0, '\n');
        return out.toString();
    }

    /** The plugin's own configuration, under build/plugins (not pluginManagement, which only pins versions). */
    private static Element configuration(String artifactId) {
        return child(plugin(artifactId), "configuration");
    }

    /** The plugin's execution that runs {@code goal}, bound to a phase of the build. */
    private static Element execution(String artifactId, String goal) {
        for (Element e : elements(child(plugin(artifactId), "executions"), "execution")) {
            if (texts(child(e, "goals"), "goal").contains(goal)) return e;
        }
        throw new AssertionError(artifactId + " runs " + goal + " in the build");
    }

    private static Element plugin(String artifactId) {
        Element build = child(POM.getDocumentElement(), "build");
        for (Element p : elements(child(build, "plugins"), "plugin")) {
            if (artifactId.equals(text(p, "artifactId"))) return p;
        }
        throw new AssertionError(artifactId + " is one of the build's plugins");
    }

    private static Element child(Element parent, String tag) {
        List<Element> found = elements(parent, tag);
        assertEquals(1, found.size(), "<" + parent.getTagName() + "> has one <" + tag + ">");
        return found.getFirst();
    }

    private static String text(Element parent, String tag) {
        List<Element> found = elements(parent, tag);
        return found.isEmpty() ? null : found.getFirst().getTextContent().trim();
    }

    private static List<String> texts(Element parent, String tag) {
        return elements(parent, tag).stream().map(e -> e.getTextContent().trim()).toList();
    }

    /** The direct children named {@code tag} (any, when null). */
    private static List<Element> elements(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList all = parent.getChildNodes();
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element e && (tag == null || e.getTagName().equals(tag))) out.add(e);
        }
        return out;
    }

    private static List<String> childNames(Element parent) {
        return elements(parent, null).stream().map(Element::getTagName).toList();
    }

    /** The node before {@code n}, past blank text. */
    private static Node previous(Node n) {
        Node p = n.getPreviousSibling();
        while (p != null && p.getNodeType() == Node.TEXT_NODE && p.getTextContent().isBlank()) p = p.getPreviousSibling();
        return p;
    }

    private static Document read(String file) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(Path.of(file).toFile());
        } catch (Exception e) {
            throw new IllegalStateException(file + " can't be read", e);
        }
    }
}

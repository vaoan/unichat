package app.unichat;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            assertTrue(flag.matches("-Xep:\\w+:(ERROR|OFF)") || NULLAWAY_OPTIONS.contains(flag), flag + ": only a check made an"
                    + " error or turned off, or one of NullAway's options, nothing that demotes, skips paths or patches"
                    + " (-XepAllErrorsAsWarnings, -XepExcludedPaths, -XepOpt:NullAway:ExcludedClasses, ...)");
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

    /**
     * NullAway's options, exactly: it checks the {@code @NullMarked} packages (so they join one by one), reads JSpecify's
     * generics and arrays, and takes JUnit's {@code assertNotNull} as a check. Nothing that leaves a class or a field out.
     */
    private static final Set<String> NULLAWAY_OPTIONS = Set.of("-XepOpt:NullAway:OnlyNullMarked=true",
            "-XepOpt:NullAway:JSpecifyMode=true", "-XepOpt:NullAway:HandleTestAssertionLibraries=true");

    @Test
    void nullAwayFailsTheBuildOnTheMarkedPackages() {
        List<String> flags = errorProneFlags();
        assertTrue(flags.contains("-Xep:NullAway:ERROR"), "NullAway's findings are errors: " + flags);
        assertEquals(NULLAWAY_OPTIONS, flags.stream().filter(f -> f.startsWith("-XepOpt:")).collect(TreeSet::new, Set::add,
                Set::addAll), "NullAway's options, and no other");
        Element path = elements(child(configuration("maven-compiler-plugin"), "annotationProcessorPaths"), "path").stream()
                .filter(p -> "com.uber.nullaway".equals(text(p, "groupId")) && "nullaway".equals(text(p, "artifactId")))
                .findFirst().orElseThrow(() -> new AssertionError("nullaway is on javac's processor path, beside Error Prone"));
        assertTrue(text(path, "version").matches("\\d+\\.\\d+\\.\\d+"), "pinned: " + text(path, "version"));
        Element jspecify = elements(child(POM.getDocumentElement(), "dependencies"), "dependency").stream()
                .filter(d -> "org.jspecify".equals(text(d, "groupId")) && "jspecify".equals(text(d, "artifactId")))
                .findFirst().orElseThrow(() -> new AssertionError("JSpecify's annotations are a dependency"));
        assertTrue(text(jspecify, "version").matches("\\d+\\.\\d+\\.\\d+"), "pinned: " + text(jspecify, "version"));
        assertNull(text(jspecify, "scope"), "main code is marked with them, so they're no test-only dependency");
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
                "spotbugs-maven-plugin", "exec-maven-plugin", "properties-maven-plugin", "maven-checkstyle-plugin",
                "jacoco-maven-plugin")) {
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
                    "spotbugs-maven-plugin", "findsecbugs-plugin", "spotbugs-exclude.xml", "exec-maven-plugin",
                    "properties-maven-plugin", "SpotBugsCache", "maven-checkstyle-plugin", "checkstyle.xml", "jacoco")) {
                assertFalse(profile.getTextContent().contains(plugin), text(profile, "id") + " leaves " + plugin + " as it is");
            }
            assertEquals(List.of("id", "properties"), childNames(profile), text(profile, "id") + " only sets properties");
            for (Element property : elements(child(profile, "properties"), null)) {
                String name = property.getTagName();
                assertTrue("argLine".equals(name) || (name.startsWith(FLOOR) && mainPackages().contains(name.substring(FLOOR.length()))),
                        text(profile, "id") + " sets only the tests' command line and a package's coverage floor, not " + name);
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
                "spotbugs.testClassFilesDirectory", "exec.skip", "skip.exec", "properties.skip", "checkstyle.skip",
                "checkstyle.failOnViolation", "checkstyle.failsOnError", "checkstyle.violationSeverity",
                "checkstyle.includeTestSourceDirectory", "checkstyle.config.location", "checkstyle.suppressions.location",
                "checkstyle.excludes", "checkstyle.includes", "checkstyle.maxAllowedViolations", "checkstyle.consoleOutput",
                "jacoco.skip", "jacoco.haltOnFailure", "jacoco.destFile", "jacoco.dataFile", "jacoco.append", "jacoco.propertyName")) {
            assertEquals(0, POM.getElementsByTagName(property).getLength(), property + " isn't set in pom.xml");
        }
    }

    @Test
    void jacocoMeasuresEveryRunThatTestsAndChecksItInVerify() {
        Element agent = execution("jacoco-maven-plugin", "prepare-agent");
        Element report = execution("jacoco-maven-plugin", "report");
        Element check = execution("jacoco-maven-plugin", "check");
        assertEquals(3, elements(child(plugin("jacoco-maven-plugin"), "executions"), "execution").size(), "JaCoCo runs only these");
        assertEquals("initialize", text(agent, "phase"), "the agent is on surefire's command line before the tests");
        assertEquals("test", text(report, "phase"), "the report on every build that runs the tests, verify or not");
        assertEquals("verify", text(check, "phase"));
        assertTrue(text(configuration("maven-surefire-plugin"), "argLine").startsWith("@{argLine} "),
                "surefire's command line starts with what prepare-agent set (late, so the agent and headless-ui's flags both get in)");
        assertTrue(texts(child(POM.getDocumentElement(), "properties"), "argLine").getFirst().isEmpty(),
                "argLine starts empty: only headless-ui's flags and the agent go in it");
        // This run's own file: a build with -DskipTests has none (report and check skip), and never reads an earlier run's.
        String exec = "${project.build.directory}/jacoco/" + RUN + ".exec";
        assertEquals(exec, text(child(agent, "configuration"), "destFile"));
        assertEquals(exec, text(child(report, "configuration"), "dataFile"));
        assertEquals(exec, text(child(check, "configuration"), "dataFile"));
        assertEquals(List.of("app.unichat.*"), texts(child(child(agent, "configuration"), "includes"), "include"),
                "the agent instruments all of ours (and only ours: the libraries' coverage isn't ours to keep)");
        assertEquals(List.of("destFile", "includes"), childNames(child(agent, "configuration")));
        assertEquals(List.of("dataFile"), childNames(child(report, "configuration")));
        assertEquals(List.of("dataFile", "haltOnFailure", "rules"), childNames(child(check, "configuration")));
        assertEquals("true", text(child(check, "configuration"), "haltOnFailure"), "a package below its floor fails the build");
        assertEquals(List.of("groupId", "artifactId", "version", "executions"), childNames(plugin("jacoco-maven-plugin")),
                "no configuration for every execution, where an <excludes> or a skip would reach them all");
        assertEquals(0, plugin("jacoco-maven-plugin").getElementsByTagName("excludes").getLength(), "no class left out");
    }

    @Test
    void everyPackageHasACoverageFloorInItsProperty() {
        List<String> packages = new ArrayList<>();
        for (Element rule : elements(child(child(execution("jacoco-maven-plugin", "check"), "configuration"), "rules"), "rule")) {
            assertEquals(List.of("element", "includes", "limits"), childNames(rule), "a rule is a package and its floor, no excludes");
            assertEquals("PACKAGE", text(rule, "element"));
            List<String> pkg = texts(child(rule, "includes"), "include");
            assertEquals(1, pkg.size(), "one package a rule: " + pkg);
            Element limit = child(child(rule, "limits"), "limit");
            assertEquals(List.of("counter", "value", "minimum"), childNames(limit), pkg + ": a floor, no maximum");
            assertEquals("LINE", text(limit, "counter"), pkg.getFirst());
            assertEquals("COVEREDRATIO", text(limit, "value"), pkg.getFirst());
            assertEquals("${" + FLOOR + pkg.getFirst() + "}", text(limit, "minimum"), pkg + "'s floor is its property");
            assertFalse(packages.contains(pkg.getFirst()), pkg + " has one rule");
            packages.add(pkg.getFirst());
        }
        assertEquals(mainPackages(), new TreeSet<>(packages), "every package with main code has its floor, and only those");
    }

    @Test
    void theFloorsAreTheOnesRecorded() {
        Map<String, String> plain = floorProperties(child(POM.getDocumentElement(), "properties"));
        assertEquals(FLOORS_PLAIN, plain, "the floors: a change here is one in FLOORS_PLAIN, lowering one with its reason");
        assertEquals(mainPackages(), plain.keySet(), "every package with main code has its floor, and only those");
        for (Map.Entry<String, String> floor : plain.entrySet()) {
            assertAFloor(floor.getKey(), floor.getValue());
            assertEquals(ZERO_IN_THE_PLAIN_BUILD.contains(floor.getKey()), new BigDecimal(floor.getValue()).signum() == 0,
                    floor.getKey() + ": a floor of 0 only where the plain build covers under 5 % (its tests are UI tests)");
        }
        Element profile = elements(child(POM.getDocumentElement(), "profiles"), "profile").stream()
                .filter(p -> "headless-ui".equals(text(p, "id"))).findFirst().orElseThrow();
        Map<String, String> headless = floorProperties(child(profile, "properties"));
        assertEquals(FLOORS_HEADLESS, headless, "headless-ui's floors: a change here is one in FLOORS_HEADLESS");
        for (Map.Entry<String, String> floor : headless.entrySet()) {
            assertAFloor(floor.getKey(), floor.getValue());
            assertTrue(new BigDecimal(floor.getValue()).compareTo(new BigDecimal(plain.get(floor.getKey()))) > 0,
                    floor.getKey() + ": headless-ui raises a floor, never lowers it: " + floor.getValue() + " against " + plain.get(floor.getKey()));
        }
    }

    /** A ratio that is a multiple of 0.05. */
    private static void assertAFloor(String pkg, String value) {
        BigDecimal min = new BigDecimal(value);
        assertEquals(0, min.remainder(new BigDecimal("0.05")).signum(), pkg + "'s floor is a multiple of 0.05: " + min);
        assertTrue(min.signum() >= 0 && min.compareTo(BigDecimal.ONE) <= 0, pkg + "'s floor is a ratio: " + min);
    }

    /** The {@code jacoco.floor.<package>} properties among {@code properties}, by package. */
    private static Map<String, String> floorProperties(Element properties) {
        Map<String, String> floors = new TreeMap<>();
        for (Element p : elements(properties, null)) {
            if (p.getTagName().startsWith(FLOOR)) floors.put(p.getTagName().substring(FLOOR.length()), p.getTextContent().trim());
        }
        return floors;
    }

    private static final String FLOOR = "jacoco.floor.";

    /**
     * Each package's floor as measured at 067a1bd on macOS: the lower line coverage of {@code verify} and
     * {@code verify -Pheadless-ui}, rounded down to 0.05. Raising one is a change here too; lowering one needs its reason.
     */
    private static final Map<String, String> FLOORS_PLAIN = floors(true);

    /** headless-ui's own floors, for the packages its UI tests reach further: its coverage at 067a1bd, rounded down to 0.05. */
    private static final Map<String, String> FLOORS_HEADLESS = floors(false);

    /** Packages whose tests are UI tests, which the plain build doesn't run: under 5 % there, so a floor of 0. */
    private static final List<String> ZERO_IN_THE_PLAIN_BUILD = List.of("app.unichat.ui.login", "app.unichat.ui.settings");

    private static Map<String, String> floors(boolean plain) {
        Map<String, String> m = new TreeMap<>();
        if (plain) {
            m.put("app.unichat", "0.10");
            m.put("app.unichat.assist", "0.85");
            m.put("app.unichat.autosave", "0.85");
            m.put("app.unichat.bridge", "0.95");
            m.put("app.unichat.cloak", "0.65");
            m.put("app.unichat.cloud", "0.90");
            m.put("app.unichat.gif", "0.85");
            m.put("app.unichat.links", "0.85");
            m.put("app.unichat.login", "0.70");
            m.put("app.unichat.matrix", "0.80");
            m.put("app.unichat.model", "0.95");
            m.put("app.unichat.server", "0.70");
            m.put("app.unichat.service", "0.75");
            m.put("app.unichat.settings", "0.65");
            m.put("app.unichat.stt", "0.80");
            m.put("app.unichat.support", "0.90");
            m.put("app.unichat.ui", "0.10");
            m.put("app.unichat.ui.chats", "0.10");
            m.put("app.unichat.ui.desktop", "0.25");
            m.put("app.unichat.ui.emoji", "0.05");
            m.put("app.unichat.ui.login", "0.00");
            m.put("app.unichat.ui.media", "0.10");
            m.put("app.unichat.ui.settings", "0.00");
            m.put("app.unichat.ui.timeline", "0.10");
            m.put("app.unichat.update", "0.60");
            m.put("app.unichat.video", "0.80");
            m.put("app.unichat.voice", "0.80");
        } else {
            m.put("app.unichat.assist", "0.90");
            m.put("app.unichat.service", "0.90");
            m.put("app.unichat.settings", "0.80");
            m.put("app.unichat.stt", "0.85");
            m.put("app.unichat.ui", "0.80");
            m.put("app.unichat.ui.chats", "0.90");
            m.put("app.unichat.ui.desktop", "0.50");
            m.put("app.unichat.ui.emoji", "0.75");
            m.put("app.unichat.ui.login", "0.35");
            m.put("app.unichat.ui.media", "0.85");
            m.put("app.unichat.ui.settings", "0.75");
            m.put("app.unichat.ui.timeline", "0.85");
            m.put("app.unichat.update", "0.70");
            m.put("app.unichat.video", "0.85");
        }
        return m;
    }

    /** The packages with a class under src/main/java. */
    static Set<String> mainPackages() {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).map(f -> root.relativize(f.getParent()).toString()
                    .replace(f.getFileSystem().getSeparator(), ".")).collect(TreeSet::new, Set::add, Set::addAll);
        } catch (IOException e) {
            throw new IllegalStateException("src/main/java can't be read", e);
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
    void spotBugsSkipsOnlyARepeatOfARunThatPassed() {
        // Before SpotBugs: SpotBugsCache checks what it reads and writes spotbugs.skip, which properties-maven-plugin reads.
        Element check = executionById("exec-maven-plugin", "spotbugs-inputs");
        Element passed = executionById("exec-maven-plugin", "spotbugs-passed");
        assertEquals(2, elements(child(plugin("exec-maven-plugin"), "executions"), "execution").size(), "exec runs only these two");
        for (Element e : List.of(check, passed)) {
            Element c = child(e, "configuration");
            assertEquals(List.of("java"), texts(child(e, "goals"), "goal"));
            assertEquals("app.unichat.build.SpotBugsCache", text(c, "mainClass"));
            assertEquals("test", text(c, "classpathScope"));
        }
        assertEquals(List.of("check", "${project.basedir}", RUN), texts(child(child(check, "configuration"), "arguments"), "argument"));
        assertEquals(List.of("passed", "${project.basedir}", RUN), texts(child(child(passed, "configuration"), "arguments"), "argument"));
        Element decision = executionById("properties-maven-plugin", "spotbugs-decision");
        assertEquals(1, elements(child(plugin("properties-maven-plugin"), "executions"), "execution").size(), "properties reads only it");
        assertEquals(List.of("read-project-properties"), texts(child(decision, "goals"), "goal"));
        assertEquals(List.of("${project.build.directory}/spotbugs-cache/skip-" + RUN + ".properties"),
                texts(child(child(decision, "configuration"), "files"), "file"), "only this run's decision: a run whose check"
                        + " didn't run finds none and stops, never an earlier run's");
        assertTrue(text(child(POM.getDocumentElement(), "properties"), "maven.build.timestamp.format").matches("[yMdHms\\-S]+"),
                "the run's name is its start, to the millisecond, as a file name on every system");
        for (String plugin : List.of("exec-maven-plugin", "properties-maven-plugin")) {
            for (String quiet : List.of("skip", "quiet")) {
                assertEquals(0, plugin(plugin).getElementsByTagName(quiet).getLength(), plugin + " has no <" + quiet + ">");
            }
        }
        // The order Maven runs them in: the check, then the read (post-integration-test), then SpotBugs, then the mark (verify).
        assertEquals("post-integration-test", text(check, "phase"));
        assertEquals("post-integration-test", text(decision, "phase"));
        assertEquals("verify", text(execution("spotbugs-maven-plugin", "check"), "phase"));
        assertEquals("verify", text(passed, "phase"), "marked after SpotBugs passed, never before");
        List<String> order = elements(child(child(POM.getDocumentElement(), "build"), "plugins"), "plugin").stream()
                .map(p -> text(p, "artifactId")).toList();
        assertTrue(order.indexOf("spotbugs-maven-plugin") < order.indexOf("exec-maven-plugin")
                && order.indexOf("exec-maven-plugin") < order.indexOf("properties-maven-plugin"),
                "SpotBugs before exec (the mark runs after it in verify), exec before properties (the read after the check)");
        assertFalse(codeText(POM.getDocumentElement()).contains("spotbugs.skip"), "spotbugs.skip comes only from SpotBugsCache");
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

    @Test
    void checkstyleChecksMainAndTestCodeAndFailsOnAnyFinding() {
        Element execution = execution("maven-checkstyle-plugin", "check");
        assertTrue(BEFORE_INSTALL.contains(text(execution, "phase")), "Checkstyle runs in verify or before: " + text(execution, "phase"));
        Element config = configuration("maven-checkstyle-plugin");
        assertEquals("checkstyle.xml", text(config, "configLocation"), "the project's checks, only them");
        assertNotEquals("false", text(config, "failOnViolation"), "a finding fails the build");
        assertEquals("warning", text(config, "violationSeverity"), "a warning fails too, not only an error");
        assertEquals("true", text(config, "includeTestSourceDirectory"), "the tests are checked too");
        assertEquals("true", text(config, "consoleOutput"), "each finding is printed");
        List<Element> configs = new ArrayList<>(elements(execution, "configuration"));
        configs.add(config);
        for (Element c : configs) {
            for (String leftOut : List.of("suppressionsLocation", "excludes", "includes", "sourceDirectories",
                    "testSourceDirectories", "maxAllowedViolations", "failsOnError", "skip", "resourceIncludes")) {
                assertTrue(elements(c, leftOut).isEmpty(), "no <" + leftOut + ">: nothing is left out of Checkstyle's sight");
            }
        }
        Element tool = elements(child(plugin("maven-checkstyle-plugin"), "dependencies"), "dependency").stream()
                .filter(d -> "checkstyle".equals(text(d, "artifactId"))).findFirst().orElseThrow();
        assertTrue(text(tool, "version").matches("\\d+\\.\\d+\\.\\d+"), "Checkstyle itself pinned, not the plugin's old default");
    }

    @Test
    void checkstyleKeepsTheSizeLimitsAndEveryCheckThatCatchesDefects() {
        Document checks = read("checkstyle.xml");
        Element checker = checks.getDocumentElement();
        assertEquals("error", property(checker, "severity"), "every finding is an error");
        assertEquals("800", property(module(checks, "FileLength"), "max"), "files stay under 800 lines");
        assertEquals("100", property(module(checks, "MethodLength"), "max"), "methods stay under 100 lines");
        Map<String, Map<String, String>> actual = new TreeMap<>();
        NodeList modules = checks.getElementsByTagName("module");
        for (int i = 0; i < modules.getLength(); i++) {
            Element m = (Element) modules.item(i);
            Map<String, String> props = new TreeMap<>();
            for (Element p : elements(m, "property")) {
                if (!p.getAttribute("name").equals("severity")) props.put(p.getAttribute("name"), p.getAttribute("value"));
            }
            assertNull(actual.put(m.getAttribute("name"), props), m.getAttribute("name") + " is in checkstyle.xml once");
        }
        assertEquals(new TreeMap<>(CHECKSTYLE_MODULES), actual,
                "checkstyle.xml runs these modules with exactly these options: none taken out, none loosened (a wider format, an"
                        + " ignore, fewer tokens); a change here is a decision, made in this test too");
        // Left out with its reason: Error Prone forbids the default MissingSwitchDefault wants (an enum switch that
        // names every constant), and PMD's NonExhaustiveSwitch catches the switch that misses a value without one.
        assertTrue(allComments(checks).contains("MissingSwitchDefault is left out"), "MissingSwitchDefault's reason is in checkstyle.xml");
        assertTrue(errorProneFlags().contains("-Xep:UnnecessaryDefaultInEnumSwitch:ERROR"), "Error Prone has that switch's case");
        NodeList pmdLeftOut = read("pmd-rules.xml").getElementsByTagName("exclude");
        for (int i = 0; i < pmdLeftOut.getLength(); i++) {
            assertNotEquals("NonExhaustiveSwitch", ((Element) pmdLeftOut.item(i)).getAttribute("name"), "PMD has the missing default's");
        }
        NodeList all = checks.getElementsByTagName("module");
        for (int i = 0; i < all.getLength(); i++) {
            Element m = (Element) all.item(i);
            String name = m.getAttribute("name");
            assertFalse(name.startsWith("Suppression") || name.startsWith("SuppressWith") || name.equals("BeforeExecutionExclusionFileFilter"),
                    name + ": a finding kept is one member's @SuppressWarnings(\"checkstyle:...\") // why, never a file or a pattern");
            String severity = property(m, "severity");
            assertTrue(severity == null || severity.equals("error"), name + " fails the build (severity " + severity + ")");
        }
    }

    /** Naming as the code writes it: Checkstyle's default formats, none widened. */
    private static final Map<String, String> DEFAULTS = Map.of();

    /** Every module checkstyle.xml has, with its options (severity apart, always error), exactly. */
    private static final Map<String, Map<String, String>> CHECKSTYLE_MODULES = checkstyleModules();

    private static Map<String, Map<String, String>> checkstyleModules() {
        Map<String, Map<String, String>> m = new TreeMap<>();
        m.put("Checker", DEFAULTS);
        m.put("TreeWalker", DEFAULTS);
        m.put("FileLength", Map.of("max", "800", "fileExtensions", "java"));
        m.put("MethodLength", Map.of("max", "100", "tokens", "METHOD_DEF, CTOR_DEF, COMPACT_CTOR_DEF"));
        m.put("EmptyCatchBlock", Map.of("exceptionVariableName", "expected|ignored|_"));
        m.put("HiddenField", Map.of("ignoreConstructorParameter", "true", "ignoreSetter", "true", "setterCanReturnItsClass", "true",
                "ignoreAbstractMethods", "true"));
        m.put("CatchParameterName", Map.of("format", "^(_|[a-z][a-zA-Z0-9]*)$"));
        for (String plain : List.of("SuppressWarningsFilter", "SuppressWarningsHolder", "EqualsHashCode", "CovariantEquals",
                "StringLiteralEquality", "FallThrough", "DefaultComesLast", "InnerAssignment", "OneStatementPerLine",
                "MultipleVariableDeclarations", "EmptyStatement", "SimplifyBooleanExpression", "SimplifyBooleanReturn", "NoFinalizer",
                "UpperEll", "ArrayTypeStyle", "ModifierOrder", "RedundantModifier", "UnusedImports", "AvoidStarImport",
                "RedundantImport", "OneTopLevelClass", "OuterTypeFilename", "PackageDeclaration", "PackageName", "TypeName",
                "MethodName", "MemberName", "StaticVariableName", "ConstantName", "ParameterName", "LambdaParameterName",
                "LocalVariableName", "LocalFinalVariableName", "PatternVariableName", "RecordComponentName", "ClassTypeParameterName",
                "MethodTypeParameterName", "InterfaceTypeParameterName", "RecordTypeParameterName", "IllegalIdentifierName")) {
            m.put(plain, DEFAULTS);
        }
        return m;
    }

    /** checkstyle.xml's one module named {@code name}. */
    private static Element module(Document checks, String name) {
        NodeList all = checks.getElementsByTagName("module");
        List<Element> found = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            if (name.equals(((Element) all.item(i)).getAttribute("name"))) found.add((Element) all.item(i));
        }
        assertEquals(1, found.size(), name + " is in checkstyle.xml once");
        return found.getFirst();
    }

    /** The value of the module's own property {@code name}, or null. */
    private static String property(Element module, String name) {
        return elements(module, "property").stream().filter(p -> name.equals(p.getAttribute("name")))
                .map(p -> p.getAttribute("value")).findFirst().orElse(null);
    }

    /** Every comment in {@code d}, together. */
    private static String allComments(Document d) {
        StringBuilder out = new StringBuilder();
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            for (Node n = all.item(i).getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Comment c) out.append(c.getData()).append('\n');
            }
        }
        return out.toString();
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

    /** What names this build run: the decision SpotBugsCache writes for it, and only it, is the one read. */
    private static final String RUN = "${maven.build.timestamp}";

    /** The plugin's execution named {@code id}. */
    private static Element executionById(String artifactId, String id) {
        for (Element e : elements(child(plugin(artifactId), "executions"), "execution")) {
            if (id.equals(text(e, "id"))) return e;
        }
        throw new AssertionError(artifactId + " has the execution " + id);
    }

    /** The text of {@code e} and all below it, comments left out. */
    private static String codeText(Node e) {
        StringBuilder out = new StringBuilder();
        NodeList all = e.getChildNodes();
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n instanceof Comment) continue;
            if (n.getNodeType() == Node.TEXT_NODE) out.append(n.getNodeValue());
            else out.append(codeText(n));
        }
        return out.toString();
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
            // checkstyle.xml names its DTD, which is never fetched; no entity is ever expanded.
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(Path.of(file).toFile());
        } catch (Exception e) {
            throw new IllegalStateException(file + " can't be read", e);
        }
    }
}

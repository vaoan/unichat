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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checks that fail the build stay on: {@code pom.xml} is read as it is, so turning one off quietly (an argument
 * taken out, a rule dropped, a {@code skip}) fails here.
 */
class BuildRulesTest {
    private static final Document POM = read();

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
        assertEquals(List.of("showWarnings", "compilerArgs"), childNames(config),
                "the compiler's configuration is the lint and nothing else (no failOnWarning, compilerArgument, ...)");
        assertEquals("true", text(config, "showWarnings"));
    }

    @Test
    void aLintTurnedOffSaysWhy() {
        for (Element arg : elements(child(configuration("maven-compiler-plugin"), "compilerArgs"), "arg")) {
            if (!arg.getTextContent().trim().startsWith("-Xlint:-")) continue;
            assertTrue(previous(arg) instanceof Comment, arg.getTextContent() + " has its reason in a comment just before it");
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
        for (String plugin : List.of("maven-compiler-plugin", "maven-enforcer-plugin", "modernizer-maven-plugin")) {
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
            for (String plugin : List.of("maven-compiler-plugin", "maven-enforcer-plugin", "modernizer-maven-plugin")) {
                assertFalse(profile.getTextContent().contains(plugin), text(profile, "id") + " leaves " + plugin + " as it is");
            }
        }
    }

    @Test
    void noCheckIsSkipped() {
        assertEquals(0, POM.getElementsByTagName("skip").getLength(), "no <skip> anywhere in pom.xml");
        for (String property : List.of("enforcer.skip", "modernizer.skip", "maven.compiler.failOnWarning")) {
            assertEquals(0, POM.getElementsByTagName(property).getLength(), property + " isn't set in pom.xml");
        }
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

    private static Document read() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        } catch (Exception e) {
            throw new IllegalStateException("pom.xml can't be read", e);
        }
    }
}

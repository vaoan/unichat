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
        List<String> names = new ArrayList<>();
        for (Node n = rules.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) names.add(e.getTagName());
        }
        assertEquals(List.of("requireJavaVersion", "requireMavenVersion", "dependencyConvergence",
                "banDuplicatePomDependencyVersions", "requirePluginVersions", "requireReleaseDeps", "banCircularDependencies"),
                names);
        assertEquals("[25,)", child(child(rules, "requireJavaVersion"), "version").getTextContent().trim());
        assertNotEquals("false", text(child(execution, "configuration"), "fail"), "the enforcer fails the build");
    }

    @Test
    void modernizerFailsAtTheProjectsJava() {
        Element config = configuration("modernizer-maven-plugin");
        assertEquals("${maven.compiler.release}", text(config, "javaVersion"));
        assertEquals("true", text(config, "failOnViolations"));
        assertEquals("true", text(config, "includeTestClasses"));
        execution("modernizer-maven-plugin", "modernizer");
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

    /** The direct children named {@code tag}. */
    private static List<Element> elements(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList all = parent.getChildNodes();
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element e && e.getTagName().equals(tag)) out.add(e);
        }
        return out;
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

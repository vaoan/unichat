package app.unichat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CI's rules ({@code .github/CLAUDE.md}) hold in every workflow, read as it is: actions pinned by SHA, no
 * expression pasted into a script, the token read-only but where a job publishes, and the two CI-only checks (CodeQL,
 * OWASP Dependency-Check) wired so that a finding fails the run.
 */
class WorkflowRulesTest {
    private static final Path WORKFLOWS = Path.of(".github/workflows");
    private static final Path SUPPRESSIONS = Path.of("dependency-check-suppressions.xml");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    /** A {@code uses:} line: an action at its commit's full SHA, the tag it was in a comment after it. */
    private static final Pattern PINNED = Pattern.compile("\\s*(?:- )?uses: [\\w.-]+/[\\w./-]+@[0-9a-f]{40} # v\\d+(?:\\.\\d+)*");
    private static final Pattern USES_LINE = Pattern.compile("\\s*(?:- )?uses:.*");
    /** The jobs that may write, and what: the ones that publish a release, and CodeQL's upload of its results. */
    private static final Set<String> WRITERS = Set.of("build.yml release contents", "natives.yml publish contents",
            "codeql.yml analyze security-events");
    /** What names the one dependency a suppression is for. */
    private static final Set<String> DEPENDENCY = Set.of("packageUrl", "gav", "sha1", "filePath");

    @Test
    void everyActionIsPinnedByItsCommitsSha() throws IOException {
        for (Path file : workflows()) {
            List<String> lines = Files.readAllLines(file);
            List<String> uses = lines.stream().filter(l -> USES_LINE.matcher(l).matches()).toList();
            for (String line : uses) {
                assertTrue(PINNED.matcher(line).matches(), file + ": \"" + line.strip() + "\" is pinned by its commit's SHA,"
                        + " with the tag in a comment (git ls-remote --tags)");
            }
            assertEquals(values(read(file), "uses").size(), uses.size(), file + ": every uses: is on a line of its own");
        }
    }

    @Test
    void noExpressionIsPastedIntoAScript() throws IOException {
        for (Path file : workflows()) {
            JsonNode workflow = read(file);
            // run: is a shell script, github-script's script: a JavaScript one: both take a value through env: only.
            for (String key : List.of("run", "script")) {
                for (String script : values(workflow, key)) {
                    assertFalse(script.contains("${{"), file + ": a value goes into a " + key + ": through env:, never as ${{ }}: "
                            + script);
                }
            }
        }
    }

    @Test
    void theTokenIsReadOnlyButWhereAJobPublishes() throws IOException {
        for (Path file : workflows()) {
            JsonNode workflow = read(file);
            assertEquals(Map.of("contents", "read"), YAML.convertValue(workflow.path("permissions"), Map.class),
                    file + ": the token reads the contents only, at the top");
            workflow.path("jobs").properties().forEach(job -> {
                JsonNode permissions = job.getValue().path("permissions");
                String name = file.getFileName() + " " + job.getKey();
                // A scalar grants every scope at once: write-all (or anything else) is refused, read-all only reads.
                assertTrue(permissions.isMissingNode() || permissions.isObject() || "read-all".equals(permissions.asText()),
                        name + ": permissions: " + permissions + " (a scope at a time, or read-all)");
                permissions.properties().forEach(p -> {
                    String who = name + " " + p.getKey();
                    assertTrue("read".equals(p.getValue().asText()) || "none".equals(p.getValue().asText()) || WRITERS.contains(who),
                            who + ": " + p.getValue().asText() + " (only " + WRITERS + " write)");
                });
            });
        }
    }

    @Test
    void theBuildJobsRunTheWholeVerifyAndNothingTurnsAChecksOff() throws IOException {
        JsonNode jobs = read(WORKFLOWS.resolve("build.yml")).path("jobs");
        assertEquals(List.of("./mvnw -B verify"), mvnw(jobs.path("test")), "the tests job runs the whole verify, as it is");
        assertEquals(List.of("./mvnw -B verify -Pheadless-ui"), mvnw(jobs.path("ui-headless")),
                "the UI job runs verify with the profile, as it is");
        for (Path file : workflows()) {
            for (String name : envNames(read(file))) {
                assertFalse(name.startsWith("MAVEN_"), file + ": " + name + " would add options to every build (MAVEN_ARGS, MAVEN_OPTS)");
            }
        }
    }

    @Test
    void aMavenVariableInAnyEnvIsCaught() {
        assertEquals(List.of("MAVEN_ARGS"), envNames(yamlTree("jobs: {a: {env: {MAVEN_ARGS: -Dpmd.skip}, steps: [{run: x}]}}")));
        assertEquals(List.of("MAVEN_OPTS"), envNames(yamlTree("jobs: {a: {steps: [{run: x, env: {MAVEN_OPTS: y}}]}}")));
        assertEquals(List.of("MAVEN_OPTS"), envNames(yamlTree("env: {MAVEN_OPTS: y}\njobs: {}")));
    }

    private static JsonNode yamlTree(String yaml) {
        try {
            return YAML.readTree(yaml);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The {@code run:} lines of a job that start a build ({@code ./mvnw}). */
    private static List<String> mvnw(JsonNode job) {
        return values(job, "run").stream().filter(r -> r.contains("mvnw")).map(String::strip).toList();
    }

    /** The names of every variable an {@code env:} sets, at the top of a workflow, in a job or in a step. */
    private static List<String> envNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node.isObject()) {
            node.properties().forEach(p -> {
                if (p.getKey().equals("env") && p.getValue().isObject()) p.getValue().properties().forEach(e -> names.add(e.getKey()));
                names.addAll(envNames(p.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(n -> names.addAll(envNames(n)));
        }
        return names;
    }

    @Test
    void codeqlRunsSecurityAndQualityAndFailsOnAResult() {
        JsonNode workflow = read(WORKFLOWS.resolve("codeql.yml"));
        assertTrue(workflow.path("on").has("push") && workflow.path("on").has("pull_request"), "on push and on pull requests");
        List<JsonNode> steps = steps(workflow, "analyze");
        JsonNode init = step(steps, "github/codeql-action/init@");
        assertEquals("java-kotlin", init.path("with").path("languages").asText());
        assertEquals("security-and-quality", init.path("with").path("queries").asText());
        JsonNode analyze = step(steps, "github/codeql-action/analyze@");
        assertEquals("sarif", analyze.path("with").path("output").asText(), "the results are written where the next step reads them");
        String gate = steps.get(steps.indexOf(analyze) + 1).path("run").asText();
        assertTrue(gate.contains("sarif/*.sarif") && gate.contains("exit 1"), "the step after the analysis fails on a result: " + gate);
        assertTrue(gate.contains("suppressions"), "a result suppressed in the code with its reason doesn't count: " + gate);
        assertEquals(List.of(), errorsLetThrough(workflow.path("jobs").path("analyze")), "a failing analysis fails the run");
    }

    @Test
    void dependencyCheckFailsOnACvssOfSevenWithTheSuppressionsFile() throws IOException {
        JsonNode workflow = read(WORKFLOWS.resolve("build.yml"));
        List<JsonNode> steps = steps(workflow, "dependency-check");
        String check = steps.stream().map(s -> s.path("run").asText()).filter(r -> r.contains("dependency-check-maven"))
                .findFirst().orElseThrow(() -> new AssertionError("a step runs Dependency-Check"));
        List<String> args = List.of(check.split("\\s+"));
        assertTrue(args.stream().anyMatch(a -> a.matches("org\\.owasp:dependency-check-maven:\\d+\\.\\d+\\.\\d+:check")),
                "the plugin at a pinned version: " + check);
        assertEquals(List.of("-DfailBuildOnCVSS=7"), args.stream().filter(a -> a.contains("failBuildOnCVSS")).toList(),
                "a CVSS of 7 or more fails the job, set once: " + check);
        assertTrue(args.contains("-DsuppressionFile=" + SUPPRESSIONS), "the suppressions file, and no other: " + check);
        assertTrue(args.contains("-DdataDirectory=\"$HOME/.dependency-check\""), "its database where the cache keeps it: " + check);
        assertFalse(check.contains("failOnError") || check.contains("skip") || check.contains("failBuildOnAnyVulnerability"),
                "nothing that lets an error or a finding pass: " + check);
        JsonNode restore = step(steps, "actions/cache/restore@");
        JsonNode save = step(steps, "actions/cache/save@");
        assertEquals("~/.dependency-check", restore.path("with").path("path").asText(), "the database is cached between runs");
        assertEquals("~/.dependency-check", save.path("with").path("path").asText(), "the database is cached between runs");
        assertTrue(save.path("if").asText().startsWith("always()"), "what the scan downloaded is kept when it fails: " + save);
        assertEquals(List.of(), errorsLetThrough(workflow.path("jobs").path("dependency-check")), "a failing scan fails the run");
        List<String> needs = new ArrayList<>();
        workflow.path("jobs").path("release").path("needs").forEach(n -> needs.add(n.asText()));
        assertTrue(needs.contains("dependency-check"), "a release waits for it: " + needs);
        assertFalse(Files.readString(Path.of("pom.xml")).contains("dependency-check-maven"),
                "CI only: never in the local verify (its NVD database is ~2 GB)");
    }

    @Test
    void everySuppressionNamesOneDependencyAndOneCveWithItsReason() {
        assertEquals(List.of(), problems(xml(SUPPRESSIONS)), SUPPRESSIONS.toString());
    }

    @Test
    void aSuppressionThatSaysNothingOrReachesTooFarIsCaught() {
        String good = "<suppress><notes>Only the server's jar, never shipped</notes><packageUrl>pkg:maven/a/b@1</packageUrl>"
                + "<cve>CVE-2026-1</cve></suppress>";
        assertEquals(List.of(), problems(parse(good)));
        assertEquals(1, problems(parse(good.replace("Only the server's jar, never shipped", " "))).size(), "no reason");
        assertEquals(1, problems(parse(good.replace("<cve>CVE-2026-1</cve>", "<cvssBelow>7</cvssBelow>"))).size(), "no CVE");
        assertEquals(1, problems(parse(good.replace("<cve>", "<cpe>cpe:/a:x</cpe><cve>"))).size(), "a whole product");
        assertEquals(1, problems(parse(good.replace("<packageUrl>pkg:maven/a/b@1</packageUrl>", ""))).size(), "every dependency");
        assertEquals(1, problems(parse(good.replace("<packageUrl>pkg:maven/a/b@1", "<packageUrl regex=\"true\">.*"))).size(),
                "a pattern");
    }

    /** What's wrong with each {@code <suppress>}: it names one dependency and one CVE, and says why. */
    private static List<String> problems(Document suppressions) {
        List<String> problems = new ArrayList<>();
        for (Element suppress : children(suppressions.getDocumentElement())) {
            Map<String, Integer> names = new TreeMap<>();
            children(suppress).forEach(e -> names.merge(e.getLocalName(), 1, Integer::sum));
            String notes = children(suppress).stream().filter(e -> "notes".equals(e.getLocalName()))
                    .map(Node::getTextContent).findFirst().orElse("");
            Set<String> others = new TreeSet<>(names.keySet());
            others.removeAll(Set.of("notes", "cve"));
            others.removeAll(DEPENDENCY);
            // A regex (<packageUrl regex="true">.*</packageUrl>) names any number of dependencies.
            children(suppress).stream().filter(e -> e.hasAttribute("regex")).forEach(e -> others.add(e.getLocalName() + " regex"));
            boolean oneDependency = names.keySet().stream().filter(DEPENDENCY::contains).mapToInt(names::get).sum() == 1;
            if (notes.isBlank() || names.getOrDefault("cve", 0) != 1 || !oneDependency || !others.isEmpty()) {
                problems.add(names + " notes \"" + notes.strip() + "\": one dependency, one <cve> and its <notes>");
            }
        }
        return problems;
    }

    /** Where a job or one of its steps carries on after an error ({@code continue-on-error} that isn't false). */
    private static List<String> errorsLetThrough(JsonNode job) {
        List<String> found = new ArrayList<>();
        List<JsonNode> places = new ArrayList<>(List.of(job));
        job.path("steps").forEach(places::add);
        for (JsonNode place : places) {
            JsonNode carryOn = place.path("continue-on-error");
            if (!carryOn.isMissingNode() && !"false".equals(carryOn.asText())) {
                found.add(place.path("uses").asText(place.path("run").asText("the job")));
            }
        }
        return found;
    }

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            List<Path> all = files.filter(f -> f.toString().endsWith(".yml")).sorted().toList();
            assertTrue(all.contains(WORKFLOWS.resolve("codeql.yml")) && all.contains(WORKFLOWS.resolve("build.yml")), all::toString);
            return all;
        }
    }

    private static JsonNode read(Path file) {
        try {
            return YAML.readTree(file.toFile());
        } catch (IOException e) {
            throw new IllegalStateException(file + " can't be read", e);
        }
    }

    /** Every text under a key of this name, anywhere in the tree. */
    private static List<String> values(JsonNode node, String key) {
        List<String> found = new ArrayList<>();
        if (node.isObject()) {
            node.properties().forEach(p -> {
                if (p.getKey().equals(key) && p.getValue().isTextual()) found.add(p.getValue().asText());
                found.addAll(values(p.getValue(), key));
            });
        } else if (node.isArray()) {
            node.forEach(n -> found.addAll(values(n, key)));
        }
        return found;
    }

    private static List<JsonNode> steps(JsonNode workflow, String job) {
        List<JsonNode> steps = new ArrayList<>();
        workflow.path("jobs").path(job).path("steps").forEach(steps::add);
        assertFalse(steps.isEmpty(), "a job " + job + " with its steps");
        return steps;
    }

    private static JsonNode step(List<JsonNode> steps, String uses) {
        return steps.stream().filter(s -> s.path("uses").asText().startsWith(uses)).findFirst()
                .orElseThrow(() -> new AssertionError("a step uses " + uses));
    }

    private static List<Element> children(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) children.add(e);
        }
        return children;
    }

    private static Document xml(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalStateException(file + " can't be read", e);
        }
    }

    private static Document parse(String text) {
        String xml = text.startsWith("<?xml") ? text : "<suppressions xmlns=\"x\">" + text + "</suppressions>";
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new InputSource(Reader.of(xml)));
        } catch (Exception e) {
            throw new IllegalStateException("the suppressions can't be read", e);
        }
    }
}

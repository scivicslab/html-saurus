package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E2E test verifying that one project is called the same thing in the two places that have to
 * agree: the directory it lives in and the name baked into every page the site builder wrote.
 *
 * <p>A page addresses the portal by the directory name, and the portal resolves a project by the
 * directory it lives in, so the two agree whatever the navbar says. Twice they did not. Two
 * projects were renamed on disk and their configuration was not, and later every project whose
 * navbar title is a display name rather than its directory name ({@code nigsc_homepage2}, titled
 * "NIG Supercomputer") posted that title: the Path button offered a path under a directory that
 * does not exist and the Rebuild button asked for a project the portal does not have, which
 * answers 404. Both survived any number of rebuilds because a build that writes the wrong name
 * still succeeds.
 *
 * <p>This does not start a server: per the testing standard (see
 * {@code TestingStandard_260404_oo01}, doc_SCIVICS001), an E2E test connects to an environment
 * someone else already brought up. Start one first:
 * <pre>
 *   java -jar html-saurus.jar ~/works --portal-mode --serve --port 28001
 * </pre>
 *
 * <p>Run:
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.ProjectNameConsistencyE2E \
 *     -Dexec.classpathScope=test
 *
 *   # Override the portal and the directory it serves:
 *   PORTAL_URL=http://localhost:28015 PORTAL_ROOT=/home/devteam/works mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.htmlsaurus.ProjectNameConsistencyE2E \
 *     -Dexec.classpathScope=test
 * </pre>
 */
public class ProjectNameConsistencyE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("PORTAL_URL", "http://localhost:28001");

    private static final Path ROOT =
            Path.of(System.getenv().getOrDefault("PORTAL_ROOT", System.getProperty("user.home") + "/works"));

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** What the portal's front page links to, which is the set of projects it knows about. */
    private static final Pattern PORTAL_LINK =
            Pattern.compile("class=\"project-link\" href=\"/([A-Za-z0-9_.-]+)/\"");
    /** The Path button's value: the repository-relative path of the Markdown source. */
    private static final Pattern DATA_PATH = Pattern.compile("data-path=\"([^/\"]+)/");
    /** The argument the Rebuild button posts to /api/build-async. */
    private static final Pattern BUILD_ARG = Pattern.compile("encodeURIComponent\\('([^']+)'\\)");

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Project Name Consistency E2E: " + BASE_URL + " (root " + ROOT + ") ===");

        List<String> projects = portalProjects();
        check("the portal lists projects", !projects.isEmpty(), "the front page linked to none");

        for (String project : projects) {
            Path dir = ROOT.resolve(project);
            if (!Files.isDirectory(dir)) {
                check(project + ": the portal links to a directory that exists", false,
                        "no directory " + dir);
                continue;
            }
            checkBuiltPages(project, dir);
            checkRebuildIsAccepted(project, dir);
        }

        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    /**
     * Every built page carries the name twice: in the Path button's value and in the argument the
     * Rebuild button posts. One page of the project is enough to catch a rename, because the
     * builder writes the same name into all of them.
     */
    private static void checkBuiltPages(String project, Path dir) throws IOException {
        Path page = aBuiltPage(dir);
        if (page == null) {
            System.out.println("SKIP: " + project + ": no built page under static-html");
            return;
        }
        String html = Files.readString(page, StandardCharsets.UTF_8);

        Matcher path = DATA_PATH.matcher(html);
        String inPathButton = path.find() ? path.group(1) : null;
        check(project + ": the Path button offers a path under its own directory",
                project.equals(inPathButton),
                "the Path button offers '" + inPathButton + "/...' (" + page.getFileName() + ")");

        Matcher build = BUILD_ARG.matcher(html);
        String inRebuild = build.find() ? build.group(1) : null;
        if (inRebuild != null) {
            check(project + ": the Rebuild button names its own project",
                    project.equals(inRebuild),
                    "the Rebuild button posts '" + inRebuild + "'");
        }
    }

    /**
     * The name a page would post must be one the portal has. A name it does not have answers 404,
     * which is what a reader sees as "Rebuild failed" with nothing else said.
     */
    private static void checkRebuildIsAccepted(String project, Path dir) throws Exception {
        Path page = aBuiltPage(dir);
        if (page == null) {
            return;
        }
        Matcher build = BUILD_ARG.matcher(Files.readString(page, StandardCharsets.UTF_8));
        if (!build.find()) {
            return;
        }
        String named = build.group(1);
        // Asks for the name only: a project the portal knows answers 202 and starts building,
        // one it does not answers 404. Nothing here waits for the build to finish.
        int status = CLIENT.send(
                HttpRequest.newBuilder(URI.create(BASE_URL + "/api/build-async/html/" + named))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        check(project + ": the portal accepts the name its own pages post",
                status == 202,
                "POST /api/build-async/html/" + named + " answered " + status);
    }

    /** Any one page the site builder wrote, other than the front page. */
    private static Path aBuiltPage(Path dir) throws IOException {
        Path staticHtml = dir.resolve("static-html");
        if (!Files.isDirectory(staticHtml)) return null;
        try (var walk = Files.walk(staticHtml)) {
            List<Path> pages = new ArrayList<>(walk
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .filter(p -> !p.getFileName().toString().equals("index.html"))
                    .sorted()
                    .toList());
            return pages.isEmpty() ? null : pages.get(0);
        }
    }

    private static List<String> portalProjects() throws Exception {
        String html = CLIENT.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        List<String> names = new ArrayList<>();
        Matcher m = PORTAL_LINK.matcher(html);
        while (m.find()) {
            if (!names.contains(m.group(1))) names.add(m.group(1));
        }
        return names;
    }

    private static void check(String name, boolean condition, String failureMessage) {
        if (condition) {
            System.out.println("PASS: " + name);
            passed++;
        } else {
            System.err.println("FAIL: " + name + " — " + failureMessage);
            failed++;
        }
    }
}

package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Prints an HTML file to PDF with headless Chrome, the way a browser shows it: KaTeX and Mermaid run, and the fonts
 * of the machine are used (ExportToPdf_261119_oo01).
 */
final class ChromePrinter implements HtmlToPdfPrinter {

    /** The system property that names the Chrome to use. */
    static final String PROPERTY = "html-saurus.chrome";
    /** The commands tried, in order, when the property names none. */
    static final List<String> CANDIDATES = List.of("google-chrome-stable", "google-chrome", "chromium", "chromium-browser");
    /** How long Chrome lets the page load fonts, scripts and math before it prints. */
    static final int VIRTUAL_TIME_BUDGET_MS = 30_000;

    private final Path chrome;

    ChromePrinter(Path chrome) {
        this.chrome = chrome;
    }

    /** @return the Chrome named by {@value #PROPERTY}, else the first of {@link #CANDIDATES} on {@code PATH} */
    static Optional<Path> findChrome() {
        String given = System.getProperty(PROPERTY);
        if (given != null && !given.isBlank()) {
            Path p = Path.of(given.strip());
            return Files.isExecutable(p) ? Optional.of(p) : Optional.empty();
        }
        String path = System.getenv("PATH");
        if (path == null) return Optional.empty();
        for (String name : CANDIDATES) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path p = Path.of(dir, name);
                if (Files.isExecutable(p)) return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * Prints {@code html} to {@code pdf}. Chrome and the processes it started are killed when the printing is
     * interrupted, runs past {@code timeout}, or ends in any other way before Chrome has exited.
     *
     * @param workDir where Chrome keeps its profile and its log for this printing
     * @throws IOException when Chrome fails, runs past {@code timeout}, or writes no PDF; the message holds the end of
     *                     its log
     */
    @Override
    public void printHtmlToPdf(Path html, Path pdf, Path workDir, Duration timeout) throws IOException, InterruptedException {
        Files.deleteIfExists(pdf);
        Path profile = Files.createDirectories(workDir.resolve("chrome-profile"));
        Path log = workDir.resolve("chrome.log");
        List<String> cmd = new ArrayList<>(List.of(chrome.toString(),
                "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check", "--hide-scrollbars",
                // In a Pod, the new headless mode of the Chromium that Playwright ships did not exit on any page, even a
                // one-line one, until the first flag was given; and it wrote no PDF for a long page ("Cannot create new
                // shared memory region" in its log) until the second was given, because a Pod's /dev/shm is 64 MB.
                "--disable-field-trial-config", "--disable-dev-shm-usage",
                "--allow-file-access-from-files", "--no-pdf-header-footer",
                "--virtual-time-budget=" + VIRTUAL_TIME_BUDGET_MS,
                "--user-data-dir=" + profile,
                "--print-to-pdf=" + pdf));
        // Chrome refuses to start its sandbox as root (a build Pod); a normal user keeps it.
        if ("root".equals(System.getProperty("user.name"))) cmd.add(1, "--no-sandbox");
        cmd.add(html.toUri().toString());
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Chrome did not finish printing within " + timeout.toSeconds() + " s: " + tail(log));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            killWithChildren(process);
        }
        if (!Files.isRegularFile(pdf) || Files.size(pdf) == 0) {
            throw new IOException("Chrome wrote no PDF (exit " + process.exitValue() + "): " + tail(log));
        }
    }

    /** Kills a process that is still running, and every process it started: Chrome runs its renderers as children. */
    private static void killWithChildren(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String tail(Path log) {
        try {
            String s = Files.readString(log);
            return s.length() > 600 ? s.substring(s.length() - 600) : s;
        } catch (IOException e) {
            return "(no log)";
        }
    }
}

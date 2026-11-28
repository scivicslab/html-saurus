package com.scivicslab.htmlsaurus;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Reads one fact about a file from git's history, and answers {@code null} when git cannot tell. */
final class GitHistory {

    /** How long git gets to answer for one file. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private GitHistory() {
    }

    /**
     * Runs {@code git log -1} for {@code file} with the given format options.
     *
     * @param file          the file; git runs in its directory
     * @param formatOptions the options that choose what to print, for example {@code --format=%aI}
     * @return git's one line, or {@code null} when the file is not tracked, is outside a repository, has no directory,
     *         git is not installed, git fails, or git does not answer in time. Git's error text is never returned.
     */
    static String lastCommit(Path file, String... formatOptions) {
        try {
            List<String> command = new ArrayList<>(List.of("git", "log", "-1"));
            command.addAll(List.of(formatOptions));
            command.add("--");
            command.add(file.toAbsolutePath().toString());
            ExternalProcess.Result r = ExternalProcess.run(command, file.toAbsolutePath().getParent(), TIMEOUT);
            String out = r.output().trim();
            return r.succeeded() && !out.isEmpty() ? out : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}

package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one external program and waits for it, for at most a given time.
 *
 * <p>The program's output goes to a temporary file, not to a pipe this process reads. Nothing is then waiting on the
 * program's output stream, so the time limit is the only wait and it holds: a program that prints nothing and never
 * ends is stopped when the limit passes, which a read of its pipe up to end of stream would not allow. The output is
 * read from the file afterwards, and the file is deleted. No thread besides the caller's is made; the caller waits on
 * the operating system's own process wait, so it should be a virtual thread or a thread that has nothing else to do,
 * not an actor's message loop.</p>
 */
final class ExternalProcess {

    /**
     * The end of one run.
     *
     * @param timedOut whether the time limit passed and the program was killed
     * @param exitCode the program's exit code, or {@code -1} when it timed out
     * @param output   what the program wrote to its standard output and standard error together
     */
    record Result(boolean timedOut, int exitCode, String output) {
        boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    private ExternalProcess() {
    }

    /**
     * Runs {@code command}, with standard error merged into standard output.
     *
     * @param command   the program and its arguments
     * @param directory the working directory, or {@code null} for this process's own
     * @param timeout   how long to wait before killing the program
     * @return how the run ended and what the program wrote
     * @throws IOException          when the program cannot be started or its output cannot be read
     * @throws InterruptedException when the waiting thread is interrupted; the program is killed first
     */
    static Result run(List<String> command, Path directory, Duration timeout) throws IOException, InterruptedException {
        Path log = Files.createTempFile("html-saurus-process-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            if (directory != null) {
                pb.directory(directory.toFile());
            }
            Process p = pb.start();
            boolean finished;
            try {
                finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                p.destroyForcibly();
                throw e;
            }
            if (!finished) {
                p.destroyForcibly();
                p.waitFor();
            }
            String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            return new Result(!finished, finished ? p.exitValue() : -1, output);
        } finally {
            Files.deleteIfExists(log);
        }
    }
}

package com.scivicslab.htmlsaurus;

/**
 * Asks one host about its URLs, one after another, leaving a pause between the starts.
 *
 * <p>A plain object. It is run as an actor by {@link ExternalLinkChecker}, which is what makes
 * the calls to one host arrive one at a time; nothing in here is written to be thread safe.</p>
 */
final class HostWorker {

    private final ExternalLinkChecker.Probe probe;
    private final long intervalMs;
    private long lastStartNanos;
    private boolean asked;

    HostWorker(ExternalLinkChecker.Probe probe, long intervalMs) {
        this.probe = probe;
        this.intervalMs = intervalMs;
    }

    /** Waits out what is left of the pause since the last request, then asks. */
    ExternalLinkChecker.Outcome check(String url) {
        if (asked) {
            long waitMs = intervalMs - (System.nanoTime() - lastStartNanos) / 1_000_000;
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ExternalLinkChecker.Outcome(ExternalLinkChecker.Kind.UNCERTAIN, 0, "interrupted");
                }
            }
        }
        asked = true;
        lastStartNanos = System.nanoTime();
        return probe.probe(url);
    }
}

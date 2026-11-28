package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What this server is showing ({@code ActivitySummary_260905_oo01}): the documents it sent to a browser most recently,
 * newest first, one entry per document, and the time the next LLM call for the Activity column is allowed.
 *
 * <p>A plain POJO with no locks and no clock of its own, hosted as an actor ({@link #host}) so the request threads
 * that note a served page and the ones that answer {@code GET /api/activity} never share it. It is a leaf: none of its
 * methods asks an actor or waits; a test pins this.</p>
 */
final class ActivityMonitor {

    /** One document this server sent to a browser: what it was, and when. */
    record Served(String path, String title, long at) {}

    /** How many recently served documents are remembered. */
    static final int SERVED_LIMIT = 20;

    /** The name of the actor in the portal's {@link ActorSystem}. */
    static final String ACTOR = "activity-monitor";

    private final Deque<Served> served = new ArrayDeque<>();
    /** Epoch millis at which the next activity translation call is allowed. */
    private long translateAllowedAtMs;

    /** Remembers one served document, newest first, without repeating a document already in the list. */
    void noteServed(String path, String title, long nowMs) {
        if (title == null || title.isBlank()) return;
        served.removeIf(v -> v.path().equals(path));
        served.addFirst(new Served(path, title, nowMs));
        while (served.size() > SERVED_LIMIT) served.pollLast();
    }

    /** @return the documents served, newest first, as a copy the caller owns */
    List<Served> recent() {
        return new ArrayList<>(served);
    }

    /**
     * Claims the one LLM call the Activity column may make in each {@code intervalMs}.
     *
     * @return {@code true} to the caller that got the call, {@code false} to every other one until the interval is over
     */
    boolean claimTranslation(long nowMs, long intervalMs) {
        if (nowMs < translateAllowedAtMs) return false;
        translateAllowedAtMs = nowMs + intervalMs;
        return true;
    }

    /** Puts one monitor into {@code system} as the actor named {@link #ACTOR}. */
    static ActorRef<ActivityMonitor> host(ActorSystem system) {
        return system.actorOf(ACTOR, new ActivityMonitor());
    }
}

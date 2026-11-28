package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * What {@link TranslationClient} remembers between calls: the model chosen on AI workspace's Settings screen (kept for a
 * few seconds) and the time until which the translation server is treated as down.
 *
 * <p>A plain POJO with no locks and no clock of its own: every method gets the time it works at. It is hosted as an actor
 * ({@link #host}) so that the Job threads calling the client never share it. It is a <em>leaf</em>: none of its methods
 * asks another actor or waits on anything, so a caller waiting for its answer on a virtual thread can never be part of an
 * {@code ask} cycle. {@code TranslationEndpointStateTest} pins this.</p>
 */
final class TranslationEndpointState {

    /** The name of the actor in the portal's {@link ActorSystem}. */
    static final String ACTOR = "translation-endpoint";

    private String cachedModel;
    private long cachedAtMs;
    /** Epoch millis until which the server counts as down; 0 = up. */
    private long downUntilMs;

    /** @return the model cached less than {@code ttlMs} before {@code nowMs}, or {@code null} when none is fresh */
    String cachedModel(long nowMs, long ttlMs) {
        return cachedModel != null && nowMs - cachedAtMs < ttlMs ? cachedModel : null;
    }

    void cacheModel(String model, long nowMs) {
        this.cachedModel = model;
        this.cachedAtMs = nowMs;
    }

    /** @return whether attempts should be skipped at {@code nowMs} */
    boolean isDown(long nowMs) {
        return nowMs < downUntilMs;
    }

    /** Counts the server as down until {@code untilMs}. */
    void markDown(long untilMs) {
        this.downUntilMs = untilMs;
    }

    /** Counts the server as up again. */
    void markUp() {
        this.downUntilMs = 0;
    }

    /** Puts one state into {@code system} as the actor named {@link #ACTOR}. */
    static ActorRef<TranslationEndpointState> host(ActorSystem system) {
        return system.actorOf(ACTOR, new TranslationEndpointState());
    }
}

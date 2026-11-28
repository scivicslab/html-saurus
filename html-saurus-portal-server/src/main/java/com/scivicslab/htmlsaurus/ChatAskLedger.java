package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.util.HashMap;
import java.util.Map;

/**
 * The requests {@link ChatAsk} has sent and where each stands. The request threads that send one and poll it, and the
 * gateway that takes the reply, all reach it as messages.
 *
 * <p>A plain POJO, hosted as an actor ({@link #host}). It is a leaf: it asks no actor and waits on none, so the gateway
 * actor that takes a reply may wait for it with {@code ask(...).join()}; a test pins this.</p>
 */
final class ChatAskLedger {

    /** The name of the actor in the portal's {@link ActorSystem}. */
    static final String ACTOR = "chat-ask-ledger";

    private final Map<String, ChatAsk.Answer> answers = new HashMap<>();
    private long next;

    /** Notes a new request as not yet answered. @return its id, {@code revise-<nowMs>-<n>} */
    String open(long nowMs) {
        String id = "revise-" + nowMs + "-" + (++next);
        answers.put(id, new ChatAsk.Answer(false, null, null));
        return id;
    }

    /** Drops a request the node did not take. */
    void withdraw(String id) {
        answers.remove(id);
    }

    /** @return where the request stands, or {@code null} when none was sent under that id */
    ChatAsk.Answer answer(String id) {
        return answers.get(id);
    }

    /** @return whether the reply was taken: {@code false} when no request was sent under that id */
    boolean accept(String id, String reply, String text) {
        if (!answers.containsKey(id)) return false;
        answers.put(id, new ChatAsk.Answer(true, reply, text));
        return true;
    }

    /** Puts one ledger into {@code system} as the actor named {@link #ACTOR}. */
    static ActorRef<ChatAskLedger> host(ActorSystem system) {
        return system.actorOf(ACTOR, new ChatAskLedger());
    }
}

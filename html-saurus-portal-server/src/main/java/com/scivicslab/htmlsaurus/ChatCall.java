package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.util.concurrent.CompletionException;

/**
 * One blocking question to the translation server, for {@link ParagraphJoinJudge} when no GPU broker is set.
 *
 * <p>A plain POJO hosted as an actor of its own per question, so the call runs on the actor's virtual thread and
 * several questions run side by side. It is a leaf: it asks no actor and waits on none (the wait is on the socket); a
 * test pins this. The caller gets the answer as the future of its {@code ask}.</p>
 */
final class ChatCall {

    /** The blocking call: {@code TranslationClient#chat}. */
    @FunctionalInterface
    interface Chatter {
        String chat(String system, String user, int timeoutSeconds) throws IOException, InterruptedException;
    }

    private final Chatter chatter;

    ChatCall(Chatter chatter) {
        this.chatter = chatter;
    }

    /** @return the model's answer; fails the {@code ask} with the call's exception */
    String chat(String system, String user, int timeoutSeconds) {
        try {
            return chatter.chat(system, user, timeoutSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }
}

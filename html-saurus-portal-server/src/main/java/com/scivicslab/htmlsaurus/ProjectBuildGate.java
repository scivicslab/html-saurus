package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lets one piece of long work at a time run for a key: an export's rebuild of one project's HTML, or a scan of the works
 * directory. It replaces a lock held across that work.
 *
 * <p>A plain POJO, hosted as an actor ({@link #host}) and a leaf: it keeps, for each key, the queue of those who want their
 * turn, and the head of the queue is the one that has it. It runs none of the work and waits on nothing. A caller that must
 * wait is handed a callback to run when its turn comes; {@link #leave} returns the callback of the next in line, and the
 * caller of {@code leave} runs it, outside the actor. Waiting itself happens in {@link Turn}, on the waiter's own thread.</p>
 */
final class ProjectBuildGate {

    /** The name of the actor in the portal's {@link ActorSystem}. */
    static final String ACTOR = "project-build-gate";

    private record Waiter(long ticket, Runnable granted) {}

    private final Map<String, Deque<Waiter>> queues = new HashMap<>();

    /** Joins the queue of {@code key}. @return whether the turn is the caller's now; if not, {@code granted} will be run */
    boolean enter(String key, long ticket, Runnable granted) {
        Deque<Waiter> queue = queues.computeIfAbsent(key, k -> new ArrayDeque<>());
        queue.add(new Waiter(ticket, granted));
        return queue.size() == 1;
    }

    /**
     * Ends a turn, or withdraws a wait that was never granted.
     *
     * @return what to run for the next in line, or {@code null} when none is waiting or the ticket was not at the head
     */
    Runnable leave(String key, long ticket) {
        Deque<Waiter> queue = queues.get(key);
        if (queue == null) return null;
        boolean head = !queue.isEmpty() && queue.peekFirst().ticket() == ticket;
        queue.removeIf(w -> w.ticket() == ticket);
        if (queue.isEmpty()) {
            queues.remove(key);
            return null;
        }
        return head ? queue.peekFirst().granted() : null;
    }

    /** @return how many are in line for {@code key}, the one with the turn included */
    int inLine(String key) {
        Deque<Waiter> queue = queues.get(key);
        return queue == null ? 0 : queue.size();
    }

    /** Puts one gate into {@code system} as the actor named {@link #ACTOR}. */
    static ActorRef<ProjectBuildGate> host(ActorSystem system) {
        return system.actorOf(ACTOR, new ProjectBuildGate());
    }

    /** The turns of one key, as the work asks for them. Holds no state of its own, so one may be shared. */
    static final class Turn {
        private static final AtomicLong TICKETS = new AtomicLong();

        private final ActorRef<ProjectBuildGate> gate;
        private final String key;

        Turn(ActorRef<ProjectBuildGate> gate, String key) {
            this.gate = gate;
            this.key = key;
        }

        /**
         * Waits for the turn on the caller's thread.
         *
         * @return the ticket to hand to {@link #leave}
         * @throws InterruptedException if interrupted while waiting; the place in line is given up
         */
        long enter() throws InterruptedException {
            long ticket = TICKETS.incrementAndGet();
            CompletableFuture<Void> granted = new CompletableFuture<>();
            boolean now = gate.ask(g -> g.enter(key, ticket, () -> granted.complete(null))).join();
            if (!now) {
                try {
                    granted.get();
                } catch (InterruptedException e) {
                    leave(ticket);
                    throw e;
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e);   // granted is only ever completed normally
                }
            }
            return ticket;
        }

        void leave(long ticket) {
            Runnable next = gate.ask(g -> g.leave(key, ticket)).join();
            if (next != null) next.run();
        }
    }
}

package com.scivicslab.htmlsaurus;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Predicate;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;

/**
 * The work directory of every export the portal has started, by job id (ExportToPdf_261119_oo01). The job registry
 * forgets a job an hour after it ended, and with it the way to download its PDF; this keeps a list of directories so
 * that the portal can delete the ones nobody can reach any more.
 *
 * <p>A plain POJO with an unsynchronised map. It is hosted as an actor ({@link #host}): the request that starts an
 * export, the request that clears one and the periodic sweep all reach it as messages, one at a time. It is a leaf: it
 * asks no actor and waits on none (the registry's question is a map read, see {@link #sweep}); a test pins this.</p>
 */
final class ExportWorkDirectoryLedger {

    private final Map<String, Path> directoryOfJob = new HashMap<>();

    /** Notes the work directory of a job that has just started. */
    void remember(String jobId, Path workDirectory) {
        directoryOfJob.put(jobId, workDirectory);
    }

    /** @return whether a directory is kept for the job */
    boolean remembers(String jobId) {
        return directoryOfJob.containsKey(jobId);
    }

    /** Stops keeping a job's directory, for a job whose directory is deleted by the one who cleared it. */
    void forget(String jobId) {
        directoryOfJob.remove(jobId);
    }

    /**
     * Deletes the work directories of the jobs the registry no longer lists.
     *
     * @param registryStillKnows tells, for a job id, whether the registry still lists the job
     * @return how many directories were deleted
     */
    int deleteOfForgottenJobs(Predicate<String> registryStillKnows) {
        int deleted = 0;
        Iterator<Map.Entry<String, Path>> entries = directoryOfJob.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Path> entry = entries.next();
            if (registryStillKnows.test(entry.getKey())) continue;
            PdfExportJob.deleteDirectory(entry.getValue());
            entries.remove();
            deleted++;
        }
        return deleted;
    }

    private static final Logger LOGGER = Logger.getLogger("com.scivicslab.htmlsaurus.PortalServer");

    /** The name of the actor in the portal's {@link ActorSystem}. */
    static final String ACTOR = "export-work-directory-ledger";

    /** {@link #deleteOfForgottenJobs}, with a line in the log when something was deleted. */
    void sweep(Predicate<String> registryStillKnows) {
        int deleted = deleteOfForgottenJobs(registryStillKnows);
        if (deleted > 0) LOGGER.log(Level.INFO, "Deleted {0} export work director(ies) of forgotten jobs", deleted);
    }

    /** Puts a ledger into {@code system} as the actor named {@link #ACTOR}. */
    static ActorRef<ExportWorkDirectoryLedger> host(ActorSystem system) {
        return system.actorOf(ACTOR, new ExportWorkDirectoryLedger());
    }

    /**
     * Sends the ledger a sweep every {@code period}, the first one after {@code period}.
     *
     * @return the scheduler to close when the portal stops
     */
    static Scheduler sweepEvery(ActorRef<ExportWorkDirectoryLedger> ledger, Predicate<String> registryStillKnows,
                                long period, TimeUnit unit) {
        Scheduler scheduler = new Scheduler(1);
        scheduler.scheduleWithFixedDelay(ACTOR + "-sweep", ledger, l -> l.sweep(registryStillKnows), period, period, unit);
        return scheduler;
    }
}

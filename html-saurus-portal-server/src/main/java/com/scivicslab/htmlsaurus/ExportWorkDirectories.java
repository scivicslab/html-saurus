package com.scivicslab.htmlsaurus;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The work directory of every export the portal has started, by job id (ExportToPdf_261119_oo01). The job registry
 * forgets a job an hour after it ended, and with it the way to download its PDF; this keeps a list of directories so
 * that the portal can delete the ones nobody can reach any more.
 */
final class ExportWorkDirectories {

    private final Map<String, Path> directoryOfJob = new ConcurrentHashMap<>();

    /** Notes the work directory of a job that has just started. */
    void remember(String jobId, Path workDirectory) {
        directoryOfJob.put(jobId, workDirectory);
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
}

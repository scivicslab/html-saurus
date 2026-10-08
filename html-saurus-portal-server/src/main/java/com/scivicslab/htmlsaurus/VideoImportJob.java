package com.scivicslab.htmlsaurus;

import com.scivicslab.jobregistry.Job;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/**
 * The work of one video import: transcribe the video, write the transcript as one Markdown document
 * under a project's {@code docs/}, and rebuild the project once at the end.
 *
 * <p>Runs as a {@code JobRegistry.Work} on an actor of its own, like {@link PdfImportJob}, because
 * Whisper on a long video takes minutes — far longer than a browser request should be held open. A
 * reload, or closing the tab, does not stop it.
 *
 * <p>Progress is reported as a single step rather than a page count: the transcript server answers
 * once, at the end, so there is no intermediate number to report honestly. The job's phase text is
 * what tells the Import screen where it has got to.
 */
class VideoImportJob {

    private final String url;
    private final TranscriptClient transcripts;
    private final Path destDir;
    /** Directory and file stem to write under, or blank to derive one from the video's title. */
    private final String stemOverride;
    /** Document title, or blank to use the video's own title. */
    private final String titleOverride;
    /** {@code "<project>/docs/<destPath>"} — how the finished file is named back to the browser. */
    private final String fileDisplayPrefix;
    /** Runs once, after the document is written. Not run if the import fails or is stopped. */
    private final Runnable onDone;

    VideoImportJob(String url, TranscriptClient transcripts, Path destDir, String stemOverride,
                   String titleOverride, String fileDisplayPrefix, Runnable onDone) {
        this.url = url;
        this.transcripts = transcripts;
        this.destDir = destDir;
        this.stemOverride = stemOverride;
        this.titleOverride = titleOverride;
        this.fileDisplayPrefix = fileDisplayPrefix;
        this.onDone = onDone;
    }

    /**
     * Transcribes the video and writes it out.
     *
     * @param job the registry's handle on this run, used to report progress and to notice a stop
     * @throws Exception to fail the job; the registry records the message
     */
    void run(Job<ImportOutcome> job) throws Exception {
        job.progress(0, 1);
        job.result(ImportOutcome.NOTHING_YET);
        job.phase("Transcribing");

        TranscriptClient.TranscriptResult transcript = transcripts.fetch(url);
        if (job.isTerminal()) {
            return;   // stop() got here while Whisper was running; write nothing
        }

        job.phase("Saving");
        String title = titleOverride == null || titleOverride.isBlank()
                ? transcript.title() : titleOverride.strip();
        String stem = stemOverride == null || stemOverride.isBlank()
                ? WebImportService.titleToStem(transcript.title()) : stemOverride.strip();

        // One document = one directory (HtmlSaurus_260806_oo01): the .md file and its poster image
        // sit together, flat, under a directory named after the document.
        Path docDir = destDir.resolve(stem);
        Files.createDirectories(docDir);

        int images = 0;
        String poster = "";
        DataUriImage thumbnail = decodeDataUri(transcript.thumbnail());
        if (thumbnail != null) {
            Files.write(docDir.resolve(thumbnail.filename()), thumbnail.bytes());
            poster = "![](" + thumbnail.filename() + ")\n\n";
            images = 1;
        }

        String markdown = assembleDocument(poster + String.join("\n\n", transcript.paragraphs()),
                url, title);
        Files.writeString(docDir.resolve(stem + ".md"), markdown, StandardCharsets.UTF_8);

        job.progress(1, 1);
        job.result(new ImportOutcome(fileDisplayPrefix + "/" + stem + "/" + stem + ".md", images));
        onDone.run();
    }

    /** Joins the transcript with YAML frontmatter naming the video it came from. */
    static String assembleDocument(String bodyMarkdown, String sourceUrl, String title) {
        return "---\n"
            + "title: " + yamlQuote(title) + "\n"
            + "source_video: " + yamlQuote(sourceUrl) + "\n"
            + "---\n\n"
            + bodyMarkdown;
    }

    /** A poster image recovered from a {@code data:} URI: the bytes and the filename to save under. */
    record DataUriImage(String filename, byte[] bytes) {}

    /**
     * Decodes the {@code data:image/...;base64,...} thumbnail the transcript server returns into a
     * real file, so the imported document keeps its poster the way a PDF or Word import keeps its
     * images — as a file beside the {@code .md}, not as a kilobytes-long inline URI. Returns
     * {@code null} for an empty, non-{@code data:}, or undecodable thumbnail.
     */
    static DataUriImage decodeDataUri(String dataUri) {
        if (dataUri == null || !dataUri.startsWith("data:")) {
            return null;
        }
        int comma = dataUri.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String header = dataUri.substring("data:".length(), comma).toLowerCase(Locale.ROOT);
        if (!header.contains("base64")) {
            return null;   // a percent-encoded data: URI is not what this server sends
        }
        String mediaType = header.substring(0, header.indexOf(';') < 0 ? header.length() : header.indexOf(';'));
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUri.substring(comma + 1).replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length == 0) {
            return null;
        }
        return new DataUriImage("thumbnail" + WebImportService.extensionFor(mediaType, ""), bytes);
    }

    private static String yamlQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

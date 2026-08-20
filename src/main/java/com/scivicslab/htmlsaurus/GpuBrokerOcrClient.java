package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.scivicslab.gpubroker.client.GpuBrokerClient;
import com.scivicslab.gpubroker.client.GpuBrokerClientException;
import com.scivicslab.gpubroker.client.JobResult;
import com.scivicslab.gpubroker.client.Priority;

/**
 * OCR via {@code quarkus-gpu-broker}'s async job path, instead of a direct HTTP call to one
 * fixed node ({@link YomiTokuOcrClient}/{@link MarkerOcrClient}). Adapts {@code gpu-broker-client}'s
 * async submit-then-callback shape into {@link OcrClient#ocrPage}'s synchronous contract, so
 * {@link PdfImportJobActor} needs no changes at all -- see {@code GpuBrokerOcrIntegration_260820_oo01}.
 *
 * <p>Whether this class or the direct clients are used is decided once, at {@code PortalServer}
 * startup, by whether {@code GPU_BROKER_URL} is set -- never at request time, and never as a
 * fallback from one to the other.
 */
class GpuBrokerOcrClient implements OcrClient {

    private static final long RESULT_TIMEOUT_SECONDS = 120; // matches YomiTokuOcrClient/MarkerOcrClient's own HTTP timeout

    /** The exact bytes+Content-Type a direct {@link OcrClient} would have sent to its own backend
     *  (multipart, per that backend's own field contract) -- see {@link YomiTokuOcrClient#buildRequest}
     *  / {@link MarkerOcrClient#buildRequest}. Submitted through gpu-broker unchanged: the OCR
     *  backend itself cannot tell whether it was called directly or via the broker. */
    record MultipartRequest(byte[] body, String contentType) {}

    @FunctionalInterface
    interface RequestBuilder {
        MultipartRequest build(byte[] onePagePdfBytes) throws IOException;
    }

    private final GpuBrokerClient client;
    private final String queueName;
    private final String backendId;
    private final RequestBuilder buildRequest;
    private final Function<String, Result> parseResult;

    /**
     * @param client       shared across every backend -- one {@code GpuBrokerClient} per {@code PortalServer}
     * @param queueName    the queue quarkus-gpu-broker discovered this backend under (e.g. {@code "yomitoku-ocr"})
     * @param backendId    this {@link OcrClient}'s {@link #backendId()} (e.g. {@code "yomitoku"})
     * @param buildRequest builds the multipart body to submit -- {@link YomiTokuOcrClient#buildRequest}
     *                     or {@link MarkerOcrClient#buildRequest}, the same shape the direct client sends
     * @param parseResult  parses the wrapped job's response body -- {@link YomiTokuOcrClient#parseResult}
     *                     or {@link MarkerOcrClient#parseResult}, the same parsing the direct client uses
     */
    GpuBrokerOcrClient(GpuBrokerClient client, String queueName, String backendId,
                        RequestBuilder buildRequest, Function<String, Result> parseResult) {
        this.client = client;
        this.queueName = queueName;
        this.backendId = backendId;
        this.buildRequest = buildRequest;
        this.parseResult = parseResult;
    }

    @Override
    public String backendId() {
        return backendId;
    }

    @Override
    public Result ocrPage(byte[] onePagePdfBytes) throws IOException {
        MultipartRequest req = buildRequest.build(onePagePdfBytes);

        CompletableFuture<JobResult> resultFuture = new CompletableFuture<>();
        try {
            client.submit(queueName, req.body(), req.contentType(), Priority.BACKGROUND, resultFuture::complete);
        } catch (GpuBrokerClientException e) {
            throw new IOException(backendId + " OCR submission to gpu-broker failed", e);
        }

        JobResult result;
        try {
            result = resultFuture.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(backendId + " OCR via gpu-broker was interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException(backendId + " OCR via gpu-broker did not complete in time", e);
        }

        if (result.status() != JobResult.Status.DONE) {
            throw new IOException(backendId + " OCR failed via gpu-broker: " + result.error());
        }
        return parseResult.apply(new String(result.body(), StandardCharsets.UTF_8));
    }
}

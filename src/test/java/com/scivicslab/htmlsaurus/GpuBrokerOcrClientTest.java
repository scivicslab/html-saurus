package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.client.GpuBrokerClient;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * No real {@code quarkus-gpu-broker} needed: {@code http://localhost:1} refuses the connection
 * immediately, which is enough to exercise {@link GpuBrokerOcrClient}'s own error translation
 * ({@code GpuBrokerClientException} -> {@code IOException}) without depending on an external service.
 */
class GpuBrokerOcrClientTest {

    @Test
    void ocrPage_translatesAnUnreachableBrokerIntoIOException() {
        GpuBrokerClient client = new GpuBrokerClient("http://localhost:1", "html-saurus-test", 10);
        GpuBrokerOcrClient ocr = new GpuBrokerOcrClient(client, "yomitoku-ocr", "yomitoku",
                YomiTokuOcrClient::buildRequest, YomiTokuOcrClient::parseResult);

        assertThrows(java.io.IOException.class, () -> ocr.ocrPage(new byte[] {1, 2, 3}));

        client.close();
    }
}

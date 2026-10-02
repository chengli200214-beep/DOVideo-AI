package com.example.server.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SiliconFlowGenerationProviderTest {
    ObjectMapper json = new ObjectMapper();
    GenerationTask task = new GenerationTask("trace-1", 1, "hash", "siliconflow", "configured-model", "{}",
            GenerationTask.State.RUNNING, "remote-1", null, null, null, null, null, 0, false, 0, null, 0, 0, 0);
    GenerationProperties properties() {
        var config = new GenerationProperties();
        config.setPaidEnabled(true);
        config.setApiKey("test-key-no-real-account");
        config.setArtifactHosts("artifacts.example.com");
        config.setAuthorizationId("contract-test");
        config.setApprovedModels(List.of("configured-model"));
        config.setMaxPaidTasks(2);
        config.setBudgetLimit(new java.math.BigDecimal("10"));
        config.setReservationPerTask(new java.math.BigDecimal("5"));
        return config;
    }

    @Test
    void serializesProviderContractAndParsesAllStatusesWithoutExternalCalls() throws Exception {
        List<Request> calls = new ArrayList<>();
        List<String> responses = List.of("{\"requestId\":\"remote-1\"}", "{\"status\":\"InQueue\"}",
                "{\"status\":\"InProgress\"}", "{\"status\":\"Failed\",\"reason\":\"unsafe\"}",
                "{\"status\":\"Succeed\",\"results\":{\"videos\":[{\"url\":\"https://artifacts.example.com/a.mp4\"}]}}");
        var provider = new SiliconFlowGenerationProvider(properties(), json,
                new OkHttpClient.Builder().addInterceptor(chain -> {
                    calls.add(chain.request());
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                            .message("OK").body(ResponseBody.create(responses.get(calls.size() - 1), MediaType.get("application/json"))).build();
                }));
        var request = new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO, "animate", "blur", "960x960", "data:image/png;base64,test", 42L);
        assertEquals("remote-1", provider.submit(task, request));
        assertEquals(GenerationProvider.Output.Status.PENDING, provider.poll(task).status());
        assertEquals(GenerationProvider.Output.Status.PENDING, provider.poll(task).status());
        assertEquals(GenerationProvider.Output.Status.FAILED, provider.poll(task).status());
        assertEquals("https://artifacts.example.com/a.mp4", provider.poll(task).url());
        Buffer buffer = new Buffer();
        calls.getFirst().body().writeTo(buffer);
        var payload = json.readTree(buffer.readUtf8());
        assertEquals("configured-model", payload.path("model").asText());
        assertEquals("960x960", payload.path("image_size").asText());
        assertEquals(42, payload.path("seed").asInt());
        assertEquals(request.image(), payload.path("image").asText());
        assertEquals("trace-1", calls.getFirst().header("X-Trace-Id"));
        assertEquals("/v1/video/submit", calls.getFirst().url().encodedPath());
        buffer = new Buffer();
        calls.get(1).body().writeTo(buffer);
        assertEquals("remote-1", json.readTree(buffer.readUtf8()).path("requestId").asText());
    }

    @Test
    void rejectionAndAmbiguousResponsesAreDifferentAndPostIsNeverRetried() {
        for (int code : List.of(400, 401, 402, 403, 404, 408, 413, 415, 422, 429, 500, 503, 504)) {
            List<Request> calls = new ArrayList<>();
            var provider = new SiliconFlowGenerationProvider(properties(), json,
                    new OkHttpClient.Builder().addInterceptor(chain -> {
                        calls.add(chain.request());
                        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                                .message("error").body(ResponseBody.create("failure", MediaType.get("text/plain"))).build();
                    }));
            var request = new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO, "test", null, "1280x720", null, null);
            if (code < 500 && code != 408)
                assertEquals("SUBMISSION_HTTP_" + code, assertThrows(GenerationProvider.Rejected.class,
                        () -> provider.submit(task, request)).code);
            else assertEquals("SUBMISSION_HTTP_" + code, assertThrows(GenerationProvider.Uncertain.class,
                    () -> provider.submit(task, request)).code);
            assertEquals(1, calls.size());
        }
        int[] calls = {0};
        var timeout = new SiliconFlowGenerationProvider(properties(), json,
                new OkHttpClient.Builder().addInterceptor(chain -> { calls[0]++; throw new IOException("timeout"); }));
        assertEquals("SUBMISSION_TRANSPORT_ERROR", assertThrows(GenerationProvider.Uncertain.class, () -> timeout.submit(task,
                new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO, "test", null, "1280x720", null, null))).code);
        assertEquals(1, calls[0]);
    }

    @Test
    void recoveryModeCanOnlyQueryOriginalRequestAndCannotSubmit() throws Exception {
        var config = properties();
        config.setPaidEnabled(false);
        config.setRecoveryEnabled(true);
        List<Request> calls = new ArrayList<>();
        var provider = new SiliconFlowGenerationProvider(config, json, new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.add(chain.request());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("{\"status\":\"InProgress\"}", MediaType.get("application/json"))).build();
        }));
        assertThrows(com.example.server.exception.BusinessException.class, () -> provider.submit(task,
                new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO, "test", null, "720x1280", null, null)));
        assertTrue(calls.isEmpty());
        assertEquals(GenerationProvider.Output.Status.PENDING, provider.poll(task).status());
        assertEquals(1, calls.size());
        assertEquals("/v1/video/status", calls.getFirst().url().encodedPath());
        var body = new Buffer(); calls.getFirst().body().writeTo(body);
        assertEquals("remote-1", json.readTree(body.readUtf8()).path("requestId").asText());
        config.setRecoveryEnabled(false);
        assertThrows(com.example.server.exception.BusinessException.class, () -> provider.poll(task));
        assertEquals(1, calls.size());
        config.setRecoveryEnabled(true); config.setApiKey("");
        assertThrows(com.example.server.exception.BusinessException.class, provider::requireRecoveryEnabled);
    }

    @Test
    void invalidSubmitResponsesExposeOnlySafeCategoriesAndAreNeverRetried() {
        for (String response : List.of("{secret-token", "{}", "null", "{\"requestId\":123}",
                "{\"requestId\":\"https://secret.example/token\"}", "x".repeat(1_048_577))) {
            int[] calls = {0};
            var provider = new SiliconFlowGenerationProvider(properties(), json,
                    new OkHttpClient.Builder().addInterceptor(chain -> {
                        calls[0]++;
                        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                                .code(200).message("OK").body(ResponseBody.create(response, MediaType.get("application/json"))).build();
                    }));
            var error = assertThrows(GenerationProvider.Uncertain.class, () -> provider.submit(task,
                    new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO, "test", null, "1280x720", null, null)));
            String expected = response.startsWith("{secret") ? "SUBMISSION_INVALID_RESPONSE"
                    : response.length() > 1_048_576 ? "SUBMISSION_RESPONSE_TOO_LARGE" : "SUBMISSION_MISSING_REQUEST_ID";
            assertEquals(expected, error.code);
            assertEquals(expected, error.getMessage());
            assertNull(error.getCause());
            assertEquals(1, calls[0]);
        }
    }

    @Test
    void unknownStatusAndMissingArtifactCannotBeReportedAsSuccess() {
        for (String response : List.of("{\"status\":\"Unknown\"}", "{\"status\":\"Succeed\"}")) {
            var provider = new SiliconFlowGenerationProvider(properties(), json,
                    new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder().request(chain.request())
                            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(ResponseBody.create(response, MediaType.get("application/json"))).build()));
            assertThrows(IOException.class, () -> provider.poll(task));
        }
    }
}

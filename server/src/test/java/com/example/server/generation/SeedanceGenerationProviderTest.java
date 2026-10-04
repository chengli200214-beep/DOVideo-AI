package com.example.server.generation;

import com.example.server.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SeedanceGenerationProviderTest {
    final ObjectMapper json = new ObjectMapper();
    static GenerationProperties config() {
        var properties = new GenerationProperties();
        properties.setProvider("seedance"); properties.setPaidEnabled(true);
        properties.getSeedance().setApiKey("offline-ark-key");
        properties.setTextModel(SeedanceGenerationProvider.MODEL); properties.setImageModel(SeedanceGenerationProvider.MODEL);
        properties.setAuthorizationId("offline-seedance"); properties.setApprovedModels(List.of(SeedanceGenerationProvider.MODEL));
        properties.setMaxPaidTasks(2); properties.setBudgetLimit(new BigDecimal("10")); properties.setReservationPerTask(new BigDecimal("5"));
        for (var capability : List.of(properties.getText(), properties.getImage())) {
            capability.setSizes(List.of("1280x720", "720x1280", "720x720")); capability.setNegativePrompt(false);
        }
        return properties;
    }
    static String png(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }
    GenerationTask task() {
        return new GenerationTask("trace-seedance", 1, "hash", "seedance", SeedanceGenerationProvider.MODEL, "{}",
                GenerationTask.State.RUNNING, "cgt-original", null, null, null, null, null, 0, false, 0, null, 0, 0, 0, null, null);
    }
    static Response response(Request request, int status, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("offline")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
    @Test void textAndPrivateImageWireContractsUseDedicatedKeyAndFixedParameters() throws Exception {
        var properties = config();
        List<Request> calls = new ArrayList<>();
        var provider = new SeedanceGenerationProvider(properties, json, new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.add(chain.request()); return response(chain.request(), 200, "{\"id\":\"cgt-original\"}");
        }));
        String image = png(720, 1280);
        for (var request : List.of(new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO, "product", null, "1280x720", null, 42L),
                new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO, "animate", null, "720x1280", image, 7L))) {
            assertEquals("cgt-original", provider.submit(task(), request));
            var call = calls.getLast(); var buffer = new Buffer(); call.body().writeTo(buffer);
            var payload = json.readTree(buffer.readUtf8());
            assertEquals("Bearer offline-ark-key", call.header("Authorization"));
            assertEquals("trace-seedance", call.header("X-Client-Request-Id"));
            assertEquals("POST", call.method()); assertEquals("/api/v3/contents/generations/tasks", call.url().encodedPath());
            assertEquals(SeedanceGenerationProvider.MODEL, payload.path("model").asText());
            assertEquals(5, payload.path("duration").asInt()); assertEquals("720p", payload.path("resolution").asText());
            assertFalse(payload.path("generate_audio").asBoolean()); assertTrue(payload.path("watermark").asBoolean());
            assertFalse(payload.has("negative_prompt"));
            if (request.kind() == GenerationRequest.Kind.IMAGE_TO_VIDEO) {
                assertEquals("9:16", payload.path("ratio").asText()); assertEquals("first_frame", payload.path("content").get(1).path("role").asText());
                assertEquals(image, payload.path("content").get(1).path("image_url").path("url").asText());
                assertFalse(json.writeValueAsString(provider.submissionParameters(task().model(), request)).contains("data:image"));
            } else { assertEquals("16:9", payload.path("ratio").asText()); assertEquals(1, payload.path("content").size()); }
        }
        assertEquals(2, calls.size());
    }
    @Test void recoveryOnlyModeUsesGetAndChecksRemoteTaskIdentity() throws Exception {
        var properties = config(); properties.setPaidEnabled(false); properties.getSeedance().setRecoveryEnabled(true);
        List<Request> calls = new ArrayList<>();
        var statuses = List.of("queued", "running", "failed", "cancelled", "expired", "succeeded");
        var provider = new SeedanceGenerationProvider(properties, json, new OkHttpClient.Builder().addInterceptor(chain -> {
            String status = statuses.get(calls.size()); calls.add(chain.request());
            return response(chain.request(), 200, "{\"id\":\"cgt-original\",\"model\":\"" + SeedanceGenerationProvider.MODEL
                    + "\",\"status\":\"" + status + "\",\"content\":{\"video_url\":\"https://artifacts.example.com/video.mp4\"}}");
        }));
        assertThrows(BusinessException.class, () -> provider.submit(task(), new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO,"x",null,"1280x720",null,null)));
        assertTrue(calls.isEmpty());
        for (String status : statuses) {
            var output = provider.poll(task());
            assertEquals(status.equals("succeeded") ? GenerationProvider.Output.Status.SUCCEEDED
                    : Set.of("queued", "running").contains(status) ? GenerationProvider.Output.Status.PENDING : GenerationProvider.Output.Status.FAILED, output.status());
            assertEquals("GET", calls.getLast().method());
            assertEquals("/api/v3/contents/generations/tasks/cgt-original", calls.getLast().url().encodedPath());
        }
        properties.getSeedance().setRecoveryEnabled(false);
        assertThrows(BusinessException.class, () -> provider.poll(task())); assertEquals(6, calls.size());
    }
    @Test void noCredentialsOrAuthorizationOrWrongEndpointNeverSendAPost() {
        var properties = config(); List<Request> calls = new ArrayList<>();
        var provider = new SeedanceGenerationProvider(properties, json, new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.add(chain.request()); return response(chain.request(), 200, "{\"id\":\"cgt-original\"}");
        }));
        var input = new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO,"product",null,"1280x720",null,null);
        properties.setPaidEnabled(false); assertThrows(BusinessException.class, () -> provider.submit(task(), input));
        properties.setPaidEnabled(true); properties.setMaxPaidTasks(0); assertThrows(BusinessException.class, () -> provider.submit(task(), input));
        properties.setMaxPaidTasks(2); properties.getSeedance().setApiKey(""); assertThrows(BusinessException.class, () -> provider.submit(task(), input));
        properties.getSeedance().setApiKey("offline"); properties.getSeedance().setBaseUrl("https://untrusted.example.com/api/v3");
        assertThrows(BusinessException.class, () -> provider.submit(task(), input)); assertTrue(calls.isEmpty());
    }
    @Test void unsupportedInputsFailBeforeNetworkActivity() throws Exception {
        var provider = new SeedanceGenerationProvider(config(), json, new OkHttpClient.Builder().addInterceptor(chain -> { fail("No network permitted"); return null; }));
        for (var input : List.of(new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO,"x","blur","1280x720",null,null),
                new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO,"x",null,"960x960",null,null),
                new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO,"x",null,"1280x720",null,Long.MAX_VALUE),
                new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO,"x",null,"720x1280",png(1,1),null),
                new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO,"x",null,"720x1280","https://private.example.com/a.png",null)))
            assertThrows(IllegalArgumentException.class, () -> provider.submit(task(), input));
    }
    @Test void explicitHttpRejectionsAndAmbiguousResultsAreSafeAndNeverRetried() {
        var input = new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO,"x",null,"1280x720",null,null);
        for (int status : List.of(400,401,403,408,429,500,503,307)) {
            int[] calls = {0};
            var provider = new SeedanceGenerationProvider(config(), json, new OkHttpClient.Builder().addInterceptor(chain -> {
                calls[0]++; return response(chain.request(), status, "secret-provider-response");
            }));
            if (status >= 400 && status < 500 && status != 408)
                assertEquals("SUBMISSION_HTTP_" + status, assertThrows(GenerationProvider.Rejected.class, () -> provider.submit(task(),input)).code);
            else assertEquals("SUBMISSION_HTTP_" + status, assertThrows(GenerationProvider.Uncertain.class, () -> provider.submit(task(),input)).code);
            assertEquals(1, calls[0]);
        }
        int[] calls = {0};
        var provider = new SeedanceGenerationProvider(config(), json, new OkHttpClient.Builder().addInterceptor(chain -> {calls[0]++;throw new IOException("secret timeout detail");}));
        var error = assertThrows(GenerationProvider.Uncertain.class, () -> provider.submit(task(),input));
        assertEquals("SUBMISSION_TRANSPORT_ERROR", error.getMessage()); assertNull(error.getCause()); assertEquals(1,calls[0]);
    }
    @Test void malformedOversizedAndMissingIdsRemainUnknownWithRedactedDiagnostics() {
        for (String body : List.of("{secret", "null", "{}", "{\"id\":42}", "{\"id\":\"https://private/token\"}", "x".repeat(1_048_577))) {
            int[] calls = {0};
            var provider = new SeedanceGenerationProvider(config(), json, new OkHttpClient.Builder().addInterceptor(chain -> {
                calls[0]++;return response(chain.request(),200,body);
            }));
            var error = assertThrows(GenerationProvider.Uncertain.class, () -> provider.submit(task(),new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO,"x",null,"1280x720",null,null)));
            assertEquals(body.startsWith("{secret") ? "SUBMISSION_INVALID_RESPONSE" : body.length()>1_048_576
                    ? "SUBMISSION_RESPONSE_TOO_LARGE" : "SUBMISSION_MISSING_REQUEST_ID", error.code);
            assertEquals(error.code,error.getMessage()); assertEquals(1,calls[0]);
        }
    }
    @Test void wrongRemoteTaskModelUnknownStatusOrMissingArtifactNeverReportsSuccess() {
        for (String body : List.of("{}", "{\"id\":\"other\",\"model\":\""+task().model()+"\",\"status\":\"succeeded\"}",
                "{\"id\":\"cgt-original\",\"model\":\"other\",\"status\":\"succeeded\"}",
                "{\"id\":\"cgt-original\",\"model\":\""+task().model()+"\",\"status\":\"new-status\"}",
                "{\"id\":\"cgt-original\",\"model\":\""+task().model()+"\",\"status\":\"succeeded\"}")) {
            var provider = new SeedanceGenerationProvider(config(),json,new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(),200,body)));
            assertThrows(IOException.class, () -> provider.poll(task()));
        }
    }
    @Test void onlySeedanceCanDownloadFromConfiguredProviderHosts() {
        var properties=config(); properties.getSeedance().setArtifactHosts("ark.example.com");
        assertEquals("",properties.artifactHostsFor("siliconflow"));
        assertEquals("",properties.artifactHostsFor("unknown-provider"));
        assertEquals("ark.example.com",properties.artifactHostsFor("seedance"));
    }
}

package com.example.server.utils;

import com.example.server.service.AgentTelemetry;
import com.example.server.service.QdrantVectorStore;
import com.example.server.service.VideoChunkingService;
import com.example.server.service.VideoEvidenceRetrievalService;
import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisProviderConfigurationTest {
    @TempDir Path temp;

    @Test void deepSeekOnlyConfigurationStartsWithoutEnablingOrSharingCredentialsWithOptionalServices() {
        new ApplicationContextRunner().withBean(EmbeddingUtils.class).withBean(AliyunAsrUtils.class)
                .withPropertyValues("ai.deepseek.api-key=deepseek-test-only", "ai.deepseek.base-url=https://api.deepseek.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var embedding = context.getBean(EmbeddingUtils.class);
                    var asr = context.getBean(AliyunAsrUtils.class);
                    assertEquals("", ReflectionTestUtils.getField(embedding, "apiKey"));
                    assertEquals("", ReflectionTestUtils.getField(asr, "apiKey"));
                    assertTrue(assertThrows(IllegalArgumentException.class, () -> embedding.embed("a product")).getMessage().contains("ai.embedding"));
                    assertTrue(assertThrows(IllegalArgumentException.class, () -> asr.audioToText("not-opened.wav")).getMessage().contains("ai.asr"));
                    assertEquals(List.of(), embedding.embed(" "));
                });
    }

    @Test void separatelyEnabledServicesStillRequireTheirOwnKeyAndDoNotFallbackToDeepSeek() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            new ApplicationContextRunner().withBean(EmbeddingUtils.class).withBean(AliyunAsrUtils.class)
                    .withPropertyValues("ai.deepseek.api-key=deepseek-test-only", "ai.embedding.enabled=true",
                            "ai.embedding.base-url=" + server.url("/v1"), "ai.embedding.model=local-embedding",
                            "ai.asr.enabled=true", "ai.asr.url=" + server.url("/audio/transcriptions"), "ai.asr.model=local-asr")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThrows(IllegalArgumentException.class, () -> context.getBean(EmbeddingUtils.class).embed("text"));
                        assertThrows(IllegalArgumentException.class, () -> context.getBean(AliyunAsrUtils.class).audioToText("not-opened.wav"));
                        assertEquals(0, server.getRequestCount());
                    });
        }
    }

    @Test void independentCredentialsAndModelsReachOnlyTheirConfiguredServices() throws Exception {
        try (var embeddingServer = new MockWebServer(); var asrServer = new MockWebServer()) {
            embeddingServer.start(); asrServer.start();
            embeddingServer.enqueue(new MockResponse().setBody("{\"data\":[{\"embedding\":[0.25,-0.5]}]}"));
            asrServer.enqueue(new MockResponse().setBody("{\"text\":\" recognized speech \"}"));
            var audio = temp.resolve("sample.wav"); Files.write(audio, new byte[]{1, 2, 3});
            new ApplicationContextRunner().withBean(EmbeddingUtils.class).withBean(AliyunAsrUtils.class)
                    .withPropertyValues("ai.deepseek.api-key=deepseek-test-only",
                            "ai.embedding.enabled=true", "ai.embedding.api-key=embedding-test-only",
                            "ai.embedding.base-url=" + embeddingServer.url("/v1/"), "ai.embedding.model=local-embedding",
                            "ai.asr.enabled=true", "ai.asr.api-key=asr-test-only",
                            "ai.asr.url=" + asrServer.url("/v1/audio/transcriptions"), "ai.asr.model=local-asr")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertEquals(List.of(0.25, -0.5), context.getBean(EmbeddingUtils.class).embed("test input"));
                        assertEquals("recognized speech", context.getBean(AliyunAsrUtils.class).audioToText(audio.toString()));
                    });
            var embedding = embeddingServer.takeRequest(1, TimeUnit.SECONDS);
            var asr = asrServer.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(embedding); assertNotNull(asr);
            assertEquals("/v1/embeddings", embedding.getPath());
            assertEquals("Bearer embedding-test-only", embedding.getHeader("Authorization"));
            assertEquals("local-embedding", new ObjectMapper().readTree(embedding.getBody().readUtf8()).path("model").asText());
            assertEquals("/v1/audio/transcriptions", asr.getPath());
            assertEquals("Bearer asr-test-only", asr.getHeader("Authorization"));
            assertTrue(asr.getBody().readUtf8().contains("local-asr"));
        }
    }

    @Test void optionalServicesRefuseRemotePlainHttpAndMalformedCredentialEndpointsBeforeNetwork() {
        for (String endpoint : List.of("http://external.example/v1", "http:/missing-host", "https://user:pass@external.example/v1",
                "https://external.example/v1?key=hidden", "https://external.example/v1#fragment")) {
            var builder = new OkHttpClient.Builder().addInterceptor(chain -> { fail("Invalid endpoint reached HTTP"); return null; });
            assertThrows(IllegalArgumentException.class, () -> new EmbeddingUtils(true, "embedding-key", endpoint, "model", builder).embed("text"));
            assertThrows(IllegalArgumentException.class, () -> new AliyunAsrUtils(true, "asr-key", endpoint, "model", builder).audioToText("not-opened.wav"));
        }
    }

    @Test void optionalServiceRedirectsCannotForwardRequestsAndAreNotRetried() throws Exception {
        try (var origin = new MockWebServer(); var destination = new MockWebServer()) {
            origin.start(); destination.start();
            var audio = temp.resolve("sample.wav"); Files.write(audio, new byte[]{1});
            for (int i = 0; i < 2; i++) origin.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", destination.url("/unexpected")));
            var embedding = new EmbeddingUtils(true, "embedding-key", origin.url("/v1").toString(), "model");
            assertThrows(IllegalStateException.class, () -> embedding.embed("text"));
            var asr = new AliyunAsrUtils(true, "asr-key", origin.url("/transcriptions").toString(), "model");
            assertThrows(IllegalArgumentException.class, () -> asr.audioToText(audio.toString()));
            assertEquals(2, origin.getRequestCount()); assertEquals(0, destination.getRequestCount());
        }
    }

    @Test void legacyAnalysisRejectsProxyAndLookalikeEndpointsEvenWhenAKeyIsPresent() {
        for (String endpoint : List.of("https://api.siliconflow.cn/v1", "http://api.deepseek.com", "https://api.deepseek.com.evil.example",
                "https://api.deepseek.com@evil.example", "https://api.deepseek.com/v1?override=true", "https://api.deepseek.com/other")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> analysis("deepseek-test-only", endpoint));
            assertTrue(failure.getMessage().contains("官方"));
        }
        assertDoesNotThrow(() -> analysis("deepseek-test-only", "https://api.deepseek.com"));
        assertDoesNotThrow(() -> analysis("deepseek-test-only", "https://api.deepseek.com/v1/"));
    }

    @Test void legacyAnalysisCanStartUnconfiguredAndFailsBeforeSchedulingAnyModelCall() {
        var executor = mock(ThreadPoolTaskExecutor.class);
        var utils = new DeepSeekUtils("", "https://api.deepseek.com", "deepseek-flash", 60, 0, 0, 0,
                mock(AgentTelemetry.class), new ObjectMapper(), executor);
        var failure = assertThrows(IllegalStateException.class, () -> utils.planRetrieval("find the product"));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        assertTrue(cause.getMessage().contains("ai.deepseek.api-key"));
        verifyNoInteractions(executor);
    }

    @Test void disabledEmbeddingPreservesTextChunkingAndKeywordRetrievalWithoutVectorServiceCalls() {
        var model = mock(DeepSeekUtils.class);
        when(model.summarizeChunk(any())).thenThrow(new IllegalStateException("fixture model unavailable"));
        when(model.planRetrieval(any())).thenThrow(new IllegalStateException("fixture model unavailable"));
        var embedding = new EmbeddingUtils(false, "", "", "");
        var telemetry = mock(AgentTelemetry.class);
        var vectors = mock(QdrantVectorStore.class);
        var segment = new VideoContext.VideoSegment(0, 5000, "water bottle product details", List.of("portable bottle"), List.of());
        var chunks = new VideoChunkingService(model, embedding, telemetry).build(List.of(segment));
        assertEquals(1, chunks.size()); assertTrue(chunks.getFirst().embedding().isEmpty());
        assertTrue(chunks.getFirst().segmentSummary().contains("water bottle"));
        var matches = new VideoEvidenceRetrievalService(model, embedding, vectors, telemetry).retrieve(1L, "bottle", chunks);
        assertEquals(List.of(segment), matches);
        verify(telemetry, times(2)).incrementCurrent("embeddingFallbacks", 1);
        verifyNoInteractions(vectors);
    }

    private DeepSeekUtils analysis(String key, String endpoint) {
        return new DeepSeekUtils(key, endpoint, "deepseek-flash", 60, 0, 0, 0,
                mock(AgentTelemetry.class), new ObjectMapper(), mock(ThreadPoolTaskExecutor.class));
    }
}

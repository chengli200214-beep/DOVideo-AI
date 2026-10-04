package com.example.server.generation;

import com.example.server.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.example.server.generation.GenerationTask.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationFlowTest {
    JdbcTemplate jdbc;
    GenerationRepository repository;
    GenerationProperties properties;
    GenerationProvider provider;
    GenerationArtifactStore artifacts;
    ObjectMapper json = new ObjectMapper();
    GenerationService service;
    GenerationAssetService assets;
    GenerationWorker worker;
    GenerationRequest text = new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO,
            "cat running", null, "1280x720", null, 7L);

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(data);
        for (String migration : List.of("V4__create_generation_tasks.sql", "V5__generation_inputs_and_trace.sql", "V6__creative_storyboards.sql", "V7__shot_generations.sql", "V8__films_and_reviews.sql", "V9__deepseek_storyboard_planning.sql", "V10__generation_poll_deadlines.sql", "V11__project_library_and_asset_cleanup.sql")) {
        try (var input = new ClassPathResource("db/migration/" + migration).getInputStream(); var connection = data.getConnection()) {
            // H2 supports the MySQL DDL except per-column ASCII collation.
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin", "").getBytes(StandardCharsets.UTF_8)));
        }
        }
        repository = new GenerationRepository(jdbc);
        properties = new GenerationProperties();
        provider = spy(new MockGenerationProvider());
        artifacts = mock(GenerationArtifactStore.class);
        when(artifacts.save(any(), anyString())).thenAnswer(call -> {
            GenerationTask task = call.getArgument(0);
            return new GenerationArtifactStore.Artifact("generated/" + task.userId() + "/" + task.id() + "/video.mp4", 4441, "checksum");
        });
        when(artifacts.readableUrl(anyString())).thenReturn("http://minio.local/presigned");
        var objects = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
        doAnswer(call -> { objects.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(artifacts).putReference(anyString(), any(), anyString());
        when(artifacts.readReference(anyString())).thenAnswer(call -> objects.get(call.getArgument(0)));
        assets = new GenerationAssetService(jdbc, artifacts);
        service = new GenerationService(repository, properties, List.of(provider), json, artifacts, assets);
        worker = new GenerationWorker(repository, service, json, artifacts);
    }

    void step(String id) throws Exception {
        jdbc.update("UPDATE generation_tasks SET next_run_at=0 WHERE id=?", id);
        worker.process(id);
    }

    @Test
    void fullLoopSurvivesWorkerRestartAndOnlySucceedsAfterSaving() throws Exception {
        var submission = service.submit(1, "key-1", text);
        String id = submission.task().id();
        assertEquals(QUEUED, submission.task().state());
        assertThrows(BusinessException.class, () -> service.artifact(1, id));
        step(id);
        assertEquals(RUNNING, service.get(1, id).state());
        GenerationRepository restartedRepository = new GenerationRepository(jdbc);
        worker = new GenerationWorker(restartedRepository, service, json, artifacts);
        step(id);
        assertEquals(SAVING, service.get(1, id).state());
        assertThrows(BusinessException.class, () -> service.artifact(1, id));
        step(id);
        assertEquals(SUCCEEDED, service.get(1, id).state());
        assertNotNull(service.get(1, id).artifactSha256());
        assertEquals("http://minio.local/presigned", service.artifact(1, id));
        assertNull(repository.byId(id).sourceUrl());
        assertTrue(service.submit(1, "key-1", text).reused());
        step(id);
        verify(provider, times(1)).submit(any(), any());
        verify(artifacts, times(1)).save(any(), anyString());
    }

    @Test
    void simultaneousSubmissionsAndWorkerClaimsOnlyCreateOneTaskAndOneCall() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<GenerationService.Submission>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> { start.await(); return service.submit(1, "same", text); }));
            start.countDown();
            String id = futures.getFirst().get().task().id();
            for (var future : futures) assertEquals(id, future.get().task().id());
            CountDownLatch submitting = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(call -> { submitting.countDown(); release.await(); return "mock-" + id; }).when(provider).submit(any(), any());
            Future<?> first = pool.submit(() -> { worker.process(id); return null; });
            submitting.await();
            worker.process(id);
            release.countDown();
            first.get();
            verify(provider, times(1)).submit(any(), any());
        }
    }

    @Test
    void idempotencyIsOwnerScopedAndRejectsChangedPayload() throws Exception {
        String id = service.submit(1, "CaseKey", text).task().id();
        var changed = new GenerationRequest(text.kind(), "other prompt", null, text.imageSize(), null, text.seed());
        assertThrows(BusinessException.class, () -> service.submit(1, "CaseKey", changed));
        assertNotEquals(id, service.submit(2, "CaseKey", text).task().id());
        assertThrows(java.util.NoSuchElementException.class, () -> service.get(2, id));
        assertThrows(java.util.NoSuchElementException.class, () -> service.retry(2, id));
        assertThrows(java.util.NoSuchElementException.class, () -> service.artifact(2, id));
        assertThrows(java.util.NoSuchElementException.class, () -> service.reconcile(2, id, "remote"));
    }

    @Test
    void ambiguousSubmissionIsNeverAutomaticallyResubmittedAndCanBeReconciled() throws Exception {
        doThrow(new IOException("read timeout after provider accepted")).when(provider).submit(any(), any());
        String id = service.submit(1, "timeout", text).task().id();
        step(id);
        assertEquals(SUBMISSION_UNKNOWN, service.get(1, id).state());
        step(id);
        assertThrows(BusinessException.class, () -> service.retry(1, id));
        service.reconcile(1, id, "remote-known");
        step(id);
        step(id);
        assertEquals(SUCCEEDED, service.get(1, id).state());
        verify(provider, times(1)).submit(any(), any());
    }

    @Test
    void safeSubmissionDiagnosticsSurviveRestartWithoutResubmission() throws Exception {
        doThrow(new GenerationProvider.Uncertain("SUBMISSION_HTTP_503")).when(provider).submit(any(), any());
        String id = service.submit(1, "diagnostic", text).task().id();
        step(id);
        assertEquals(SUBMISSION_UNKNOWN, service.get(1, id).state());
        assertEquals("SUBMISSION_HTTP_503", service.get(1, id).errorCode());
        worker = new GenerationWorker(new GenerationRepository(jdbc), service, json, artifacts);
        step(id);
        assertTrue(service.submit(1, "diagnostic", text).reused());
        assertThrows(BusinessException.class, () -> service.retry(1, id));
        assertEquals("SUBMISSION_HTTP_503", service.get(1, id).errorCode());
        verify(provider, times(1)).submit(any(), any());

        doThrow(new GenerationProvider.Rejected("safe rejection", "SUBMISSION_HTTP_422"))
                .when(provider).submit(any(), any());
        String rejected = service.submit(1, "diagnostic-rejected", text).task().id();
        step(rejected);
        assertEquals(FAILED, service.get(1, rejected).state());
        assertEquals("SUBMISSION_HTTP_422", service.get(1, rejected).errorCode());
    }

    @Test
    void expiredSubmittingLeaseBecomesUnknownAndStaleWorkerCannotWrite() throws Exception {
        String id = service.submit(1, "crash", text).task().id();
        GenerationTask stale = repository.claim(id, System.currentTimeMillis());
        assertTrue(repository.beginSubmission(stale, System.currentTimeMillis()));
        jdbc.update("UPDATE generation_tasks SET lease_until=0 WHERE id=?", id);
        step(id);
        assertEquals(SUBMISSION_UNKNOWN, service.get(1, id).state());
        assertFalse(repository.finish(stale, RUNNING, "stale-id", null, null, null, 0, false, 0, System.currentTimeMillis()));
        verify(provider, never()).submit(any(), any());
    }

    @Test
    void saveFailureRefreshesUrlAndStopsAfterFiveFailuresThenManualRecoveryUsesSameRemote() throws Exception {
        doThrow(new IOException("storage unavailable")).when(artifacts).save(any(), anyString());
        String id = service.submit(1, "storage", text).task().id();
        step(id);
        String remote = service.get(1, id).remoteId();
        for (int i = 0; i < 5; i++) { step(id); step(id); }
        assertEquals(FAILED, service.get(1, id).state());
        assertEquals(5, service.get(1, id).attempts());
        assertTrue(service.get(1, id).recoverable());
        doReturn(new GenerationArtifactStore.Artifact("fixed-key", 10, "sha")).when(artifacts).save(any(), anyString());
        service.retry(1, id);
        step(id);
        step(id);
        assertEquals(SUCCEEDED, service.get(1, id).state());
        assertEquals(remote, service.get(1, id).remoteId());
        verify(provider, times(1)).submit(any(), any());
    }

    @Test
    void modelFailureAndRejectedSubmissionAreTerminal() throws Exception {
        doReturn(new GenerationProvider.Output(GenerationProvider.Output.Status.FAILED, null)).when(provider).poll(any());
        String id = service.submit(1, "failed", text).task().id();
        step(id); step(id);
        assertEquals("MODEL_FAILED", service.get(1, id).errorCode());
        assertThrows(BusinessException.class, () -> service.retry(1, id));
        doThrow(new GenerationProvider.Rejected("400")).when(provider).submit(any(), any());
        String rejected = service.submit(1, "rejected", text).task().id();
        step(rejected);
        assertEquals("SUBMISSION_REJECTED", service.get(1, rejected).errorCode());
        assertThrows(BusinessException.class, () -> service.retry(1, rejected));
    }

    @Test
    void expiredQueuedAndRunningWorkResumeWithoutResubmission() throws Exception {
        String id = service.submit(1, "queued", text).task().id();
        repository.claim(id, System.currentTimeMillis());
        jdbc.update("UPDATE generation_tasks SET lease_until=0 WHERE id=?", id);
        step(id);
        repository.claim(id, System.currentTimeMillis());
        jdbc.update("UPDATE generation_tasks SET lease_until=0 WHERE id=?", id);
        step(id); step(id);
        assertEquals(SUCCEEDED, service.get(1, id).state());
        verify(provider, times(1)).submit(any(), any());
    }

    @Test
    void realProviderIsBlockedByDefaultAndGuardAlsoAppliesToPersistedTasks() throws Exception {
        properties.setProvider("seedance");
        var real = new SeedanceGenerationProvider(properties, json);
        service = new GenerationService(repository, properties, List.of(real), json, artifacts, assets);
        assertThrows(BusinessException.class, () -> service.submit(1, "paid", text));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM generation_tasks", Integer.class));
        repository.insert("persisted", 1, "persisted", "hash", "seedance", SeedanceGenerationProvider.MODEL, json.writeValueAsString(text), 0);
        worker = new GenerationWorker(repository, service, json, artifacts);
        assertThrows(BusinessException.class, () -> worker.process("persisted"));
        assertEquals(QUEUED, repository.byId("persisted").state());
        assertNull(repository.byId("persisted").leaseToken());
    }

    @Test
    void recoveryOnlyModeReconcilesAndArchivesWithoutNewSubmissionOrReservation() throws Exception {
        properties.setProvider("seedance");
        properties.setPaidEnabled(false);
        properties.getSeedance().setRecoveryEnabled(true);
        properties.getSeedance().setApiKey("offline-recovery-key");
        properties.getSeedance().setArtifactHosts("artifacts.example.com");
        var calls = new ArrayList<okhttp3.Request>();
        provider = new SeedanceGenerationProvider(properties, json, new okhttp3.OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    calls.add(chain.request());
                    return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200).message("OK").body(okhttp3.ResponseBody.create(
                                    "{\"id\":\"verified-original-id\",\"model\":\"" + SeedanceGenerationProvider.MODEL
                                            + "\",\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://artifacts.example.com/original.mp4\"}}",
                                    okhttp3.MediaType.get("application/json"))).build();
                }));
        service = new GenerationService(repository, properties, List.of(provider), json, artifacts, assets);
        worker = new GenerationWorker(repository, service, json, artifacts);
        repository.insert("original", 1, "original-intent", "hash", "seedance", SeedanceGenerationProvider.MODEL, json.writeValueAsString(text), 0);
        var claim = repository.claim("original", System.currentTimeMillis());
        repository.finish(claim, SUBMISSION_UNKNOWN, null, null, null, "SUBMISSION_UNKNOWN", 0, false, 0, System.currentTimeMillis());
        jdbc.update("INSERT INTO generation_authorizations(id,policy_hash,used_tasks,reserved_cost) VALUES ('original-authorization','hash',1,2.5)");
        jdbc.update("INSERT INTO generation_reservations VALUES ('original','original-authorization',2.5,0)");
        assertTrue(service.get(1, "original").recoveryAvailable());
        assertThrows(BusinessException.class, () -> service.submit(1, "new-generation", text));
        properties.getSeedance().setRecoveryEnabled(false);
        assertFalse(service.get(1, "original").recoveryAvailable());
        assertThrows(BusinessException.class, () -> service.reconcile(1, "original", "verified-original-id"));
        assertEquals(SUBMISSION_UNKNOWN, service.get(1, "original").state());
        properties.getSeedance().setRecoveryEnabled(true);
        service.reconcile(1, "original", "verified-original-id");
        worker = new GenerationWorker(new GenerationRepository(jdbc), service, json, artifacts);
        step("original"); step("original");
        assertEquals(SUCCEEDED, service.get(1, "original").state());
        assertEquals("verified-original-id", service.get(1, "original").remoteId());
        assertEquals(1, calls.size());
        assertEquals("GET", calls.getFirst().method());
        assertEquals("/api/v3/contents/generations/tasks/verified-original-id", calls.getFirst().url().encodedPath());
        assertEquals(1, jdbc.queryForObject("SELECT used_tasks FROM generation_authorizations WHERE id='original-authorization'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM generation_reservations", Integer.class));
        repository.insert("queued-paid", 1, "queued-paid", "hash", "seedance", SeedanceGenerationProvider.MODEL, json.writeValueAsString(text), 0);
        assertThrows(BusinessException.class, () -> worker.process("queued-paid"));
        assertEquals(QUEUED, service.get(1, "queued-paid").state());
        assertEquals(1, calls.size());
    }

    @Test
    void imageRequestsValidateActualFormatAndDoNotAcceptArbitraryUrls() throws Exception {
        String png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ZkAAAAASUVORK5CYII=";
        var image = new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO, "animate", null, "960x960", png, null);
        String id = service.submit(1, "image", image).task().id();
        step(id); step(id); step(id);
        assertEquals(SUCCEEDED, service.get(1, id).state());
        for (String invalid : List.of("https://127.0.0.1/photo.png", "data:image/png;base64,YWJj", png.replace("image/png", "image/jpeg"))) {
            assertThrows(IllegalArgumentException.class, () -> service.submit(1, UUID.randomUUID().toString(),
                    new GenerationRequest(image.kind(), "animate", null, "960x960", invalid, null)));
        }
    }

    @Test
    void privateReferenceIsLoadedIntoSeedanceWirePayloadBeforeSubmission() throws Exception {
        var image = new java.awt.image.BufferedImage(720, 1280, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var png = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, "png", png));
        byte[] bytes = png.toByteArray();
        var asset = assets.upload(1, "image/png", bytes);
        properties.setProvider("seedance");
        properties.setPaidEnabled(true);
        properties.getSeedance().setApiKey("offline-contract-key");
        properties.getSeedance().setArtifactHosts("artifacts.example.com");
        properties.setAuthorizationId("offline-i2v-contract");
        properties.setApprovedModels(List.of(properties.getImageModel()));
        properties.setMaxPaidTasks(1);
        properties.setBudgetLimit(new java.math.BigDecimal("2.5"));
        properties.setReservationPerTask(new java.math.BigDecimal("2.5"));
        var calls = new ArrayList<okhttp3.Request>();
        provider = new SeedanceGenerationProvider(properties, json, new okhttp3.OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    calls.add(chain.request());
                    return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200).message("OK").body(okhttp3.ResponseBody.create("{\"id\":\"offline-remote\"}",
                                    okhttp3.MediaType.get("application/json"))).build();
                }));
        service = new GenerationService(repository, properties, List.of(provider), json, artifacts, assets);
        worker = new GenerationWorker(repository, service, json, artifacts);
        String id = service.submit(1, "private-reference-wire", new GenerationRequest(
                GenerationRequest.Kind.IMAGE_TO_VIDEO, "animate bottle", null, "720x1280", null, null, asset.id()))
                .task().id();
        step(id);
        assertEquals(RUNNING, service.get(1, id).state());
        assertEquals("offline-remote", service.get(1, id).remoteId());
        assertEquals(1, calls.size());
        var buffer = new okio.Buffer();
        calls.getFirst().body().writeTo(buffer);
        var payload = json.readTree(buffer.readUtf8());
        assertEquals(properties.getImageModel(), payload.path("model").asText());
        assertEquals("9:16", payload.path("ratio").asText());
        assertEquals("720p", payload.path("resolution").asText());
        String wireImage = payload.path("content").get(1).path("image_url").path("url").asText();
        assertTrue(wireImage.startsWith("data:image/png;base64,"));
        assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(wireImage.substring(wireImage.indexOf(',') + 1)));
        assertFalse(payload.has("referenceImageId"));
        assertEquals(id, calls.getFirst().header("X-Client-Request-Id"));
        assertEquals(asset.sha256(), service.trace(1, id).effectiveSubmission().path("imageReference").path("sha256").asText());
        assertEquals("[private-reference]", service.trace(1, id).effectiveSubmission().path("parameters")
                .path("content").get(1).path("image_url").path("url").asText());
        assertFalse(json.writeValueAsString(service.trace(1, id)).contains("data:image"));
    }

    @Test
    void receivedRemoteIdIsPreservedWhenFirstCheckpointWriteFails() throws Exception {
        String id = service.submit(1, "db-error", text).task().id();
        var flaky = spy(repository);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("database temporarily unavailable"))
                .doCallRealMethod().when(flaky).finish(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), anyLong(), anyLong());
        worker = new GenerationWorker(flaky, service, json, artifacts);
        step(id);
        assertEquals(RUNNING, service.get(1, id).state());
        assertEquals("mock-" + id, service.get(1, id).remoteId());
        verify(provider, times(1)).submit(any(), any());
    }

    @Test
    void repeatedQueryFailureStopsAndReconcileCannotBindAnAlreadyUsedRemoteId() throws Exception {
        doThrow(new IOException("query unavailable")).when(provider).poll(any());
        String id = service.submit(1, "query-error", text).task().id();
        step(id);
        for (int i = 0; i < 5; i++) step(id);
        assertEquals(FAILED, service.get(1, id).state());
        assertTrue(service.get(1, id).recoverable());
        assertEquals("STATUS_QUERY_FAILED", service.get(1, id).errorCode());
        String unknown = service.submit(1, "unknown", text).task().id();
        var claim = repository.claim(unknown, System.currentTimeMillis());
        repository.finish(claim, SUBMISSION_UNKNOWN, null, null, null, "SUBMISSION_UNKNOWN", 0, false, 0, System.currentTimeMillis());
        assertThrows(BusinessException.class, () -> service.reconcile(1, unknown, service.get(1, id).remoteId()));
        assertEquals(SUBMISSION_UNKNOWN, service.get(1, unknown).state());
    }
}

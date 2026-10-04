package com.example.server.generation;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;

import static com.example.server.generation.GenerationTask.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Existing paid records remain auditable without retaining an executable retired provider. */
class RetiredGenerationProviderTest {
    GenerationFlowTest flow;
    GenerationProvider seedance;

    @BeforeEach void setup() throws Exception {
        flow = new GenerationFlowTest();
        flow.setup();
        flow.properties = SeedanceGenerationProviderTest.config();
        seedance = spy(new SeedanceGenerationProvider(flow.properties, flow.json,
                new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
                    throw new AssertionError("Historical tasks must never call Seedance");
                })));
        flow.service = new GenerationService(flow.repository, flow.properties, List.of(flow.provider, seedance),
                flow.json, flow.artifacts, flow.assets);
        flow.worker = new GenerationWorker(flow.repository, flow.service, flow.json, flow.artifacts);
        flow.jdbc.update("INSERT INTO generation_authorizations(id,policy_hash,used_tasks,reserved_cost) VALUES ('historical','old-policy',1,2.5)");
    }

    private void historical(String id, GenerationTask.State state) throws Exception {
        String payload = flow.json.writeValueAsString(flow.text);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        flow.repository.insert(id, 1, id, hash, "siliconflow", "Wan-AI/Wan2.2-T2V-A14B", payload, flow.text.prompt(), 0);
        flow.jdbc.update("UPDATE generation_tasks SET state=?,remote_id=?,recoverable=?,effective_json=? WHERE id=?",
                state.name(), state == QUEUED || state == SUBMITTING || state == SUBMISSION_UNKNOWN ? null : "old-remote-" + id,
                state == FAILED, "{\"parameters\":{\"model\":\"historical-model\"}}", id);
    }

    private void reserved(String id) {
        flow.jdbc.update("INSERT INTO generation_reservations VALUES (?,'historical',2.5,0)", id);
    }

    private void assertLedgerUnchanged() {
        assertEquals(1, flow.jdbc.queryForObject("SELECT used_tasks FROM generation_authorizations WHERE id='historical'", Integer.class));
        assertEquals(0, new BigDecimal("2.5").compareTo(flow.jdbc.queryForObject(
                "SELECT reserved_cost FROM generation_authorizations WHERE id='historical'", BigDecimal.class)));
        assertEquals(1, flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_reservations", Integer.class));
    }

    @Test void completedHistoryAndPrivateArtifactRemainReadableWithoutTheProvider() throws Exception {
        historical("completed", SUCCEEDED);
        reserved("completed");
        String key = "generated/1/completed/video.mp4";
        flow.jdbc.update("UPDATE generation_tasks SET artifact_key=?,artifact_size=4441,artifact_sha256='old-checksum' WHERE id='completed'", key);
        var before = flow.repository.byId("completed");

        var view = flow.service.get(1, "completed");
        assertEquals(SUCCEEDED, view.state());
        assertEquals("siliconflow", view.provider());
        assertEquals(before.model(), view.model());
        assertFalse(view.recoveryAvailable());
        assertEquals(List.of(view), flow.service.recent(1));
        var trace = flow.service.trace(1, "completed");
        assertEquals(flow.text.prompt(), trace.originalPrompt());
        assertEquals("historical-model", trace.effectiveSubmission().path("parameters").path("model").asText());
        assertEquals(1, trace.events().size());
        assertEquals("http://minio.local/presigned", flow.service.artifact(1, "completed"));
        verify(flow.artifacts).readableUrl(key);
        assertThrows(NoSuchElementException.class, () -> flow.service.get(2, "completed"));
        assertThrows(NoSuchElementException.class, () -> flow.service.trace(2, "completed"));
        assertThrows(NoSuchElementException.class, () -> flow.service.artifact(2, "completed"));
        assertEquals(before, flow.repository.byId("completed"));
        assertLedgerUnchanged();
        verify(seedance, never()).submit(any(), any());
        verify(seedance, never()).poll(any());
    }

    @Test void recoveryAndReconciliationExplicitlyRefuseRetiredProviderWithoutChangingHistory() throws Exception {
        historical("failed", FAILED);
        historical("unknown", SUBMISSION_UNKNOWN);
        reserved("unknown");
        var failed = flow.repository.byId("failed");
        var unknown = flow.repository.byId("unknown");

        var retry = assertThrows(BusinessException.class, () -> flow.service.retry(1, "failed"));
        var reconcile = assertThrows(BusinessException.class,
                () -> flow.service.reconcile(1, "unknown", "verified-old-remote"));
        for (var error : List.of(retry, reconcile)) {
            assertEquals(ErrorCode.CONFLICT, error.errorCode());
            assertTrue(error.getMessage().contains("SiliconFlow 视频服务已停用"));
        }
        assertEquals(failed, flow.repository.byId("failed"));
        assertEquals(unknown, flow.repository.byId("unknown"));
        assertLedgerUnchanged();
        verify(seedance, never()).submit(any(), any());
        verify(seedance, never()).poll(any());
    }

    @Test void retiredTasksAreDeferredAndDoNotBlockMockOrBecomeSeedanceTasks() throws Exception {
        var states = List.of(QUEUED, SUBMITTING, RUNNING, SAVING);
        for (int i = 0; i < 8; i++) historical("old-" + i, states.get(i % states.size()));
        reserved("old-2");
        flow.properties.setProvider("mock");
        String fresh = flow.service.submit(1, "new-mock", flow.text).task().id();

        flow.worker.tick();
        assertEquals(List.of(fresh), flow.repository.due(System.currentTimeMillis()).stream().map(GenerationTask::id).toList());
        flow.worker.tick();
        assertEquals(RUNNING, flow.service.get(1, fresh).state());
        for (int i = 0; i < 8; i++) {
            var old = flow.repository.byId("old-" + i);
            assertEquals(states.get(i % states.size()), old.state());
            assertEquals("siliconflow", old.provider());
            assertEquals("Wan-AI/Wan2.2-T2V-A14B", old.model());
            assertNull(old.leaseToken());
            assertTrue(old.nextRunAt() > System.currentTimeMillis());
        }
        assertLedgerUnchanged();
        verify(flow.provider, times(1)).submit(any(), any());
        verify(seedance, never()).submit(any(), any());
        verify(seedance, never()).poll(any());
        verify(flow.artifacts, never()).save(any(), any());
    }

    @Test void replayKeepsOriginalTaskWhileNewSubmissionsCannotSelectTheRetiredProvider() throws Exception {
        historical("old-intent", RUNNING);
        reserved("old-intent");
        // A new default provider must not reinterpret an already accepted idempotency key.
        var replay = flow.service.submit(1, "old-intent", flow.text);
        assertTrue(replay.reused());
        assertEquals("old-intent", replay.task().id());
        assertEquals("siliconflow", replay.task().provider());
        flow.properties.setProvider("siliconflow");
        var error = assertThrows(BusinessException.class, () -> flow.service.submit(1, "new-intent", flow.text));
        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        assertTrue(error.getMessage().contains("已停用"));
        assertTrue(flow.service.capabilities().stream().noneMatch(GenerationService.ModelCapability::available));
        assertEquals(1, flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_tasks", Integer.class));
        assertLedgerUnchanged();
        verify(seedance, never()).submit(any(), any());
        verify(seedance, never()).poll(any());
    }
}

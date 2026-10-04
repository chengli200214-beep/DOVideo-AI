package com.example.server.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.server.generation.GenerationTask.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GenerationSchedulingRecoveryTest {
    GenerationFlowTest flow;

    @BeforeEach void setup() throws Exception {
        flow = new GenerationFlowTest();
        flow.setup();
    }

    @Test void disabledSubmissionAndRecoveryTasksDoNotStarveAnEnabledProvider() throws Exception {
        var disabled = spy(new SeedanceGenerationProvider(flow.properties, flow.json));
        flow.service = new GenerationService(flow.repository, flow.properties,
                List.of(flow.provider, disabled), flow.json, flow.artifacts, flow.assets);
        flow.worker = new GenerationWorker(flow.repository, flow.service, flow.json, flow.artifacts);
        for (int i = 0; i < 8; i++) {
            String id = "disabled-" + i;
            flow.repository.insert(id, 1, id, "hash", "seedance", SeedanceGenerationProvider.MODEL,
                    flow.json.writeValueAsString(flow.text), 0);
            if (i % 2 == 1) {
                var claimed = flow.repository.claim(id, System.currentTimeMillis());
                assertTrue(flow.repository.finish(claimed, RUNNING, "remote-" + i, null, null,
                        null, 0, false, 0, System.currentTimeMillis()));
            }
        }
        String enabled = flow.service.submit(1, "enabled-mock", flow.text).task().id();

        flow.worker.tick();
        assertEquals(List.of(enabled), flow.repository.due(System.currentTimeMillis()).stream()
                .map(GenerationTask::id).toList());
        flow.worker.tick();
        assertEquals(RUNNING, flow.repository.byId(enabled).state());
        verify(flow.provider, times(1)).submit(any(), any());
        verify(disabled, never()).submit(any(), any());
        verify(disabled, never()).poll(any());
        assertEquals(0, flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_reservations", Integer.class));
        for (int i = 0; i < 8; i++) {
            var paused = flow.repository.byId("disabled-" + i);
            assertEquals(i % 2 == 0 ? QUEUED : RUNNING, paused.state());
            assertTrue(paused.nextRunAt() > System.currentTimeMillis());
            assertNull(paused.leaseToken());
        }
    }

    @Test void staleUnavailableCandidateCannotDeferAnotherWorkersClaim() throws Exception {
        String id = flow.service.submit(1, "claim-race", flow.text).task().id();
        var candidate = flow.repository.byId(id);
        var claimed = flow.repository.claim(id, System.currentTimeMillis());
        assertNotNull(claimed);
        assertFalse(flow.repository.deferUnavailable(candidate, System.currentTimeMillis()));
        assertEquals(claimed.leaseToken(), flow.repository.byId(id).leaseToken());
        assertEquals(claimed.nextRunAt(), flow.repository.byId(id).nextRunAt());
    }

    @Test void oldQueuedTaskReceivesItsPollingWindowAtActualSubmission() throws Exception {
        doReturn(new GenerationProvider.Output(GenerationProvider.Output.Status.PENDING, null))
                .when(flow.provider).poll(any());
        String id = flow.service.submit(1, "old-queue", flow.text).task().id();
        flow.jdbc.update("UPDATE generation_tasks SET created_at=? WHERE id=?",
                System.currentTimeMillis() - 25 * 60 * 60 * 1000L, id);
        long beforeSubmission = System.currentTimeMillis();
        flow.step(id);
        var submitted = flow.repository.byId(id);
        assertTrue(submitted.submittedAt() >= beforeSubmission);
        assertEquals(submitted.submittedAt() + GenerationRepository.POLL_WINDOW_MS, submitted.pollDeadlineAt());
        flow.step(id);
        assertEquals(RUNNING, flow.repository.byId(id).state());

        flow.worker = new GenerationWorker(new GenerationRepository(flow.jdbc), flow.service, flow.json, flow.artifacts);
        flow.step(id);
        assertEquals(RUNNING, flow.repository.byId(id).state());
        assertEquals(submitted.pollDeadlineAt(), flow.repository.byId(id).pollDeadlineAt());
        verify(flow.provider, times(1)).submit(any(), any());
    }

    @Test void manualTimeoutRecoveryUsesANewWindowAndTheSameRemoteTask() throws Exception {
        doReturn(new GenerationProvider.Output(GenerationProvider.Output.Status.PENDING, null))
                .when(flow.provider).poll(any());
        String id = flow.service.submit(1, "expired-poll", flow.text).task().id();
        flow.step(id);
        var original = flow.repository.byId(id);
        flow.jdbc.update("UPDATE generation_tasks SET poll_deadline_at=? WHERE id=?",
                System.currentTimeMillis() - 1, id);
        flow.step(id);
        assertEquals(FAILED, flow.repository.byId(id).state());
        assertEquals("POLL_TIMEOUT", flow.repository.byId(id).errorCode());
        assertTrue(flow.repository.byId(id).recoverable());

        long beforeRetry = System.currentTimeMillis();
        flow.service.retry(1, id);
        flow.step(id);
        var resumed = flow.repository.byId(id);
        assertEquals(RUNNING, resumed.state());
        assertEquals(original.remoteId(), resumed.remoteId());
        assertEquals(original.submittedAt(), resumed.submittedAt());
        assertTrue(resumed.pollDeadlineAt() >= beforeRetry + GenerationRepository.POLL_WINDOW_MS);
        doReturn(new GenerationProvider.Output(GenerationProvider.Output.Status.SUCCEEDED, "mock://sample.mp4"))
                .when(flow.provider).poll(any());
        flow.step(id);
        flow.step(id);
        assertEquals(SUCCEEDED, flow.repository.byId(id).state());
        verify(flow.provider, times(1)).submit(any(), any());
    }

    @Test void legacyRunningTaskGetsOnePersistedRecoveryWindowWithoutResubmission() throws Exception {
        doReturn(new GenerationProvider.Output(GenerationProvider.Output.Status.PENDING, null))
                .when(flow.provider).poll(any());
        flow.repository.insert("legacy", 1, "legacy", "hash", "mock", "mock-video",
                flow.json.writeValueAsString(flow.text), 0);
        var claimed = flow.repository.claim("legacy", System.currentTimeMillis());
        flow.repository.finish(claimed, RUNNING, "legacy-remote", null, null, null,
                0, false, 0, System.currentTimeMillis());
        assertNull(flow.repository.byId("legacy").pollDeadlineAt());
        flow.step("legacy");
        var resumed = flow.repository.byId("legacy");
        assertEquals(RUNNING, resumed.state());
        assertNotNull(resumed.pollDeadlineAt());
        assertNull(resumed.submittedAt());

        flow.worker = new GenerationWorker(new GenerationRepository(flow.jdbc), flow.service, flow.json, flow.artifacts);
        flow.step("legacy");
        assertEquals(resumed.pollDeadlineAt(), flow.repository.byId("legacy").pollDeadlineAt());
        assertEquals("legacy-remote", flow.repository.byId("legacy").remoteId());
        verify(flow.provider, never()).submit(any(), any());
    }

    @Test void upgradeBackfillsSubmissionEventsWithoutInventingLegacySubmissionTimes() throws Exception {
        String known = flow.service.submit(1, "known-submission", flow.text).task().id();
        var claimed = flow.repository.claim(known, System.currentTimeMillis());
        long originalSubmission = System.currentTimeMillis();
        assertTrue(flow.repository.beginSubmission(claimed, originalSubmission));
        flow.repository.insert("legacy-no-event", 1, "legacy-no-event", "hash", "mock", "mock-video",
                flow.json.writeValueAsString(flow.text), 0);
        flow.jdbc.update("UPDATE generation_tasks SET state='RUNNING',remote_id='old-remote' WHERE id='legacy-no-event'");
        flow.jdbc.execute("ALTER TABLE generation_tasks DROP COLUMN submitted_at");
        flow.jdbc.execute("ALTER TABLE generation_tasks DROP COLUMN poll_deadline_at");
        try (var connection = flow.jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("db/migration/V10__generation_poll_deadlines.sql"));
        }
        assertEquals(originalSubmission, flow.repository.byId(known).submittedAt());
        assertEquals(SUBMITTING, flow.repository.byId(known).state());
        assertNull(flow.repository.byId(known).pollDeadlineAt());
        assertEquals(RUNNING, flow.repository.byId("legacy-no-event").state());
        assertNull(flow.repository.byId("legacy-no-event").submittedAt());
        assertNull(flow.repository.byId("legacy-no-event").pollDeadlineAt());
        verify(flow.provider, never()).submit(any(), any());
    }

    @Test void archivedProjectBlocksDirectTaskRecoveryButKeepsResultsReadable() throws Exception {
        var shots = new ShotGenerationFlowTest();
        shots.setup();
        shots.ready(3);
        var submitted = shots.service.submit(1, shots.project.id(), "archive-guard",
                shots.input("INITIAL", shots.ids()));
        String failed = submitted.versions().get(0).task().id();
        String unknown = submitted.versions().get(1).task().id();
        String completed = submitted.versions().get(2).task().id();
        shots.complete(completed);
        shots.flow.jdbc.update("UPDATE generation_tasks SET state='FAILED',recoverable=TRUE,remote_id=? WHERE id=?",
                "original-failed", failed);
        shots.flow.jdbc.update("UPDATE generation_tasks SET state='FAILED' WHERE id=?", unknown);
        shots.storyboard.repository.archive(1, shots.project.id(), true, System.currentTimeMillis());
        // Cover a legacy/imported uncertain task on an already archived project as well as a normal failed one.
        shots.flow.jdbc.update("UPDATE generation_tasks SET state='SUBMISSION_UNKNOWN' WHERE id=?", unknown);
        assertThrows(com.example.server.exception.BusinessException.class, () -> shots.flow.service.retry(1, failed));
        assertThrows(com.example.server.exception.BusinessException.class,
                () -> shots.flow.service.reconcile(1, unknown, "original-unknown"));
        assertEquals(FAILED, shots.flow.service.get(1, failed).state());
        assertEquals(SUBMISSION_UNKNOWN, shots.flow.service.get(1, unknown).state());
        assertEquals("http://minio.local/presigned", shots.flow.service.artifact(1, completed));

        shots.storyboard.repository.archive(1, shots.project.id(), false, System.currentTimeMillis());
        assertEquals(RUNNING, shots.flow.service.retry(1, failed).state());
        assertEquals("original-failed", shots.flow.service.get(1, failed).remoteId());
        assertEquals(RUNNING, shots.flow.service.reconcile(1, unknown, "original-unknown").state());
        verify(shots.flow.provider, times(1)).submit(any(), any()); // Only the completed clip was submitted.
    }
}

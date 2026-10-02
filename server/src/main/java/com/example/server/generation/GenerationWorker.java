package com.example.server.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.example.server.generation.GenerationTask.State;

@Component
public class GenerationWorker {
    private static final Logger log = LoggerFactory.getLogger(GenerationWorker.class);
    private final GenerationRepository repository;
    private final GenerationService service;
    private final ObjectMapper json;
    private final GenerationArtifactStore artifacts;

    public GenerationWorker(GenerationRepository repository, GenerationService service,
                            ObjectMapper json, GenerationArtifactStore artifacts) {
        this.repository = repository;
        this.service = service;
        this.json = json;
        this.artifacts = artifacts;
    }

    @Scheduled(fixedDelayString = "${generation.worker-delay-ms:2000}",
            initialDelayString = "${generation.worker-initial-delay-ms:0}")
    public void tick() {
        for (GenerationTask candidate : repository.due(System.currentTimeMillis())) {
            try { process(candidate.id()); }
            catch (Exception error) {
                // Keep DB failures recoverable through lease expiration; do not swallow them as success.
                log.warn("generation_worker_error task={} type={}", candidate.id(), error.getClass().getSimpleName());
            }
        }
    }

    void process(String id) throws Exception {
        GenerationTask candidate = repository.byId(id);
        if (candidate == null) return;
        GenerationProvider provider = service.provider(candidate.provider());
        // Recovery permission never permits a QUEUED task to cross the paid submission boundary.
        if (candidate.state() == State.QUEUED) provider.requireEnabled();
        else provider.requireRecoveryEnabled();
        GenerationTask task = repository.claim(id, System.currentTimeMillis());
        if (task == null) return;
        String submittedRemote = null;
        boolean submissionBegan = false;
        try {
            switch (task.state()) {
                case QUEUED -> {
                    // Parse before the durable submission boundary. A persisted malformed payload cannot be sent.
                    var submission = service.prepare(task);
                    if (!service.beginSubmission(task, submission, System.currentTimeMillis())) return;
                    submissionBegan = true;
                    submittedRemote = provider.submit(task, submission.request());
                    finish(task, State.RUNNING, submittedRemote, null, null, null, 0, false, 0);
                }
                case SUBMITTING -> finish(task, State.SUBMISSION_UNKNOWN, task.remoteId(), null, null,
                        "SUBMISSION_UNKNOWN", 0, false, 0);
                case RUNNING -> {
                    GenerationProvider.Output output = provider.poll(task);
                    switch (output.status()) {
                        case PENDING -> {
                            if (System.currentTimeMillis() - task.createdAt() > 24 * 60 * 60 * 1000L) {
                                finish(task, State.FAILED, task.remoteId(), null, null, "POLL_TIMEOUT", 0, true, 0);
                            } else finish(task, State.RUNNING, task.remoteId(), null, null, null, task.attempts(), false, 5000);
                        }
                        case FAILED -> finish(task, State.FAILED, task.remoteId(), null, null, "MODEL_FAILED", 0, false, 0);
                        case SUCCEEDED -> finish(task, State.SAVING, task.remoteId(), output.url(), null, null, task.attempts(), false, 0);
                    }
                }
                case SAVING -> {
                    var artifact = artifacts.save(task, task.sourceUrl());
                    finish(task, State.SUCCEEDED, task.remoteId(), null, artifact, null, 0, false, 0);
                }
                default -> { }
            }
        } catch (GenerationProvider.Rejected error) {
            finish(task, State.FAILED, task.remoteId(), task.sourceUrl(), null, error.code, 0, false, 0);
        } catch (Exception error) {
            String diagnostic = error instanceof GenerationProvider.Uncertain uncertain ? uncertain.code : "SUBMISSION_UNKNOWN";
            log.warn("generation_step_failed task={} state={} type={} code={}", id, task.state(), error.getClass().getSimpleName(), diagnostic);
            if (task.state() == State.QUEUED || task.state() == State.SUBMITTING) {
                if (task.state() == State.QUEUED && !submissionBegan) {
                    // Input loading/validation or quota failure precedes all provider activity.
                    finish(task, State.FAILED, null, null, null,
                            error instanceof com.example.server.exception.BusinessException ? "SUBMISSION_AUTHORIZATION_DENIED"
                                    : "SUBMISSION_PREPARATION_FAILED", 0, false, 0);
                } else if (submittedRemote != null) {
                    // A transient DB write failure must not discard a requestId already received.
                    finish(task, State.RUNNING, submittedRemote, null, null, null, 0, false, 0);
                } else finish(task, State.SUBMISSION_UNKNOWN, task.remoteId(), null, null, diagnostic, 0, false, 0);
            } else {
                int attempts = task.attempts() + 1;
                // Saving retries re-poll to refresh provider URLs that may have expired.
                finish(task, attempts >= 5 ? State.FAILED : State.RUNNING, task.remoteId(), null, null,
                        task.state() == State.SAVING ? "ARTIFACT_SAVE_FAILED" : "STATUS_QUERY_FAILED",
                        attempts, attempts >= 5, Math.min(60_000, 2000L << Math.min(attempts, 5)));
            }
        }
    }

    private void finish(GenerationTask task, State state, String remote, String source,
                        GenerationArtifactStore.Artifact artifact, String error, int attempts,
                        boolean recoverable, long delay) {
        long now = System.currentTimeMillis();
        if (repository.finish(task, state, remote, source, artifact, error, attempts, recoverable, now + delay, now)) {
            log.info("generation_transition task={} from={} to={}", task.id(), task.state(), state);
        }
    }
}

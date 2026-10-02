package com.example.server.generation;

public record GenerationTask(String id, long userId, String requestHash, String provider,
        String model, String requestJson, State state, String remoteId, String sourceUrl,
        String artifactKey, Long artifactSize, String artifactSha256, String errorCode,
        int attempts, boolean recoverable, long nextRunAt, String leaseToken,
        long leaseUntil, long createdAt, long updatedAt) {
    public enum State { QUEUED, SUBMITTING, SUBMISSION_UNKNOWN, RUNNING, SAVING, SUCCEEDED, FAILED }
}

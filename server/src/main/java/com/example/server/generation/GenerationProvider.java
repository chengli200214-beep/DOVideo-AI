package com.example.server.generation;

public interface GenerationProvider {
    String name();
    void requireEnabled();
    default void requireRecoveryEnabled() { requireEnabled(); }
    /** Validate loaded private inputs before the durable paid submission boundary. */
    default void validateRequest(String model, GenerationRequest request) { }
    /** Persist provider-specific parameters without embedded private image bytes. */
    default java.util.Map<String, Object> submissionParameters(String model, GenerationRequest request) {
        var parameters = SiliconFlowGenerationProvider.submissionBody(model, request);
        parameters.remove("image");
        return parameters;
    }
    String submit(GenerationTask task, GenerationRequest request) throws Exception;
    Output poll(GenerationTask task) throws Exception;
    record Output(Status status, String url) {
        public enum Status { PENDING, SUCCEEDED, FAILED }
    }
    /** Only explicit rejection is safe to classify as not accepted. All other submit errors are ambiguous. */
    class Rejected extends RuntimeException {
        public final String code;
        public Rejected(String message) { this(message, "SUBMISSION_REJECTED"); }
        public Rejected(String message, String code) { super(message); this.code = diagnosticCode(code); }
    }
    /** Keep a bounded diagnostic category without retaining provider bodies, URLs or credentials. */
    class Uncertain extends java.io.IOException {
        public final String code;
        public Uncertain(String code) { super(diagnosticCode(code)); this.code = code; }
    }
    private static String diagnosticCode(String code) {
        if (code == null || !code.matches("SUBMISSION_[A-Z0-9_]{1,48}")) throw new IllegalArgumentException("Invalid provider diagnostic category");
        return code;
    }
}

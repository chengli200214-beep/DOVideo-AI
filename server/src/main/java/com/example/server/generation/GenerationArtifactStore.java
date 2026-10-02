package com.example.server.generation;

public interface GenerationArtifactStore {
    Artifact save(GenerationTask task, String sourceUrl) throws Exception;
    String readableUrl(String key);
    void putReference(String key, byte[] bytes, String mime) throws Exception;
    byte[] readReference(String key) throws Exception;
    default void copyArtifact(String key, java.nio.file.Path target) throws Exception { throw new UnsupportedOperationException(); }
    default Artifact saveFile(String key, java.nio.file.Path file) throws Exception { throw new UnsupportedOperationException(); }
    default java.io.InputStream openArtifact(String key) throws Exception { throw new UnsupportedOperationException(); }
    record Artifact(String key, long size, String sha256) { }
}

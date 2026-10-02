package com.example.server.film;
import com.example.server.generation.GenerationArtifactStore;
public interface CompositionRenderer {
    Result render(CompositionRepository.Task task, FilmSpec spec) throws Exception;
    record Result(GenerationArtifactStore.Artifact artifact,String metadataJson) { }
    class Failure extends Exception {
        private final String code;
        public Failure(String code) { super(code); this.code=code; }
        public String code() { return code; }
    }
}

package com.example.server.generation;

import org.springframework.stereotype.Component;

@Component
public class MockGenerationProvider implements GenerationProvider {
    public String name() { return "mock"; }
    public void requireEnabled() { }
    public String submit(GenerationTask task, GenerationRequest request) { return "mock-" + task.id(); }
    public Output poll(GenerationTask task) {
        return new Output(Output.Status.SUCCEEDED, "mock://sample.mp4");
    }
}

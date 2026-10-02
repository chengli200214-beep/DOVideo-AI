package com.example.server.film;

import java.util.List;
/** Everything a render needs is captured at submission; rendering never reads a mutable draft. */
public record FilmSpec(int revision, int selectionVersion, int width, int height, boolean burnCaptions, List<Clip> clips) {
    public record Clip(String versionId, String shotId, String taskId, String title, String caption, String narration,
                       String artifactKey, long artifactSize, String artifactSha256) { }
}

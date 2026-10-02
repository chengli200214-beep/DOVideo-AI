package com.example.server.storyboard;

import java.util.List;

public record StoryboardDraft(String script, List<Shot> shots) {
    public record Shot(String id, int sequence, String title, String subject, String action,
            String setting, String camera, String referenceAssetId, int desiredDurationSeconds,
            String frameRatio, String caption, String narration, String prompt, int promptVersion,
            Parameters parameters) { }
    public record Parameters(String negativePrompt, Long seed) { }
}

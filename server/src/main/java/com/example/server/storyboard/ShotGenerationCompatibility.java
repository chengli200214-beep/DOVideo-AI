package com.example.server.storyboard;

import java.util.Objects;

/** Compare only video-generation inputs; editing post-production text does not invalidate a clip. */
public final class ShotGenerationCompatibility {
    private ShotGenerationCompatibility() { }

    public static boolean matches(StoryboardDraft.Shot saved, StoryboardDraft.Shot current) {
        if (saved == null || current == null || !Objects.equals(saved.id(), current.id())) return false;
        return Objects.equals(text(saved.prompt()), text(current.prompt()))
                && Objects.equals(text(saved.referenceAssetId()), text(current.referenceAssetId()))
                && Objects.equals(saved.frameRatio(), current.frameRatio())
                && Objects.equals(negative(saved), negative(current))
                && Objects.equals(seed(saved), seed(current));
    }

    private static String negative(StoryboardDraft.Shot shot) {
        return shot.parameters() == null ? null : text(shot.parameters().negativePrompt());
    }
    private static Long seed(StoryboardDraft.Shot shot) {
        return shot.parameters() == null ? null : shot.parameters().seed();
    }
    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

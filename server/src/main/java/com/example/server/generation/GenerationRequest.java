package com.example.server.generation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** New clients use referenceImageId; legacy inline images are archived before task persistence. */
public record GenerationRequest(
        @NotNull Kind kind,
        @NotBlank @Size(max = 2000) String prompt,
        @Size(max = 2000) String negativePrompt,
        @NotBlank String imageSize,
        @Size(max = 7_000_000) String image,
        Long seed,
        @Size(max = 36) String referenceImageId) {
    public GenerationRequest(Kind kind, String prompt, String negativePrompt, String imageSize, String image, Long seed) {
        this(kind, prompt, negativePrompt, imageSize, image, seed, null);
    }
    public enum Kind { TEXT_TO_VIDEO, IMAGE_TO_VIDEO }
}

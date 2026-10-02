package com.example.server.storyboard;

import java.util.List;

/** Duration and ratio describe editorial intent, not arbitrary supplier parameters. */
public record CreativeBrief(String title, String goal, String productName, List<String> sellingPoints,
        String style, int desiredDurationSeconds, String frameRatio, String referenceAssetId, int shotCount) { }

package com.example.server.storyboard;

import java.util.List;

/** A local planner contract. A future paid planner must acquire its own submission gate first. */
public interface StoryboardPlanner {
    String name();
    StoryboardDraft draft(CreativeBrief brief, List<String> previousErrors);
}

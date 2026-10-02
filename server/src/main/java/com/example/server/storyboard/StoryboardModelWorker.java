package com.example.server.storyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StoryboardModelWorker {
    private final StoryboardModelRepository repository;
    private final StoryboardService storyboards;
    private final DeepSeekStoryboardPlanner planner;
    private final ObjectMapper json;
    public StoryboardModelWorker(StoryboardModelRepository repository,StoryboardService storyboards,DeepSeekStoryboardPlanner planner,ObjectMapper json) {
        this.repository=repository; this.storyboards=storyboards; this.planner=planner; this.json=json;
    }
    @Scheduled(fixedDelayString="${storyboard.model.worker-delay-ms:2000}")
    public void tick() { repository.recover(System.currentTimeMillis()); for (String id:repository.due()) process(id); }
    public void process(String id) {
        var task=repository.claim(id,System.currentTimeMillis());
        if (task==null) return;
        try {
            var view=storyboards.get(task.userId(),task.projectId());
            if (view.revision().number()!=task.expectedRevision()) { repository.fail(task,"CONFLICT","REVISION_CHANGED",System.currentTimeMillis()); return; }
            var brief=storyboards.normalizePlanningBrief(task.userId(),view.brief());
            var output=planner.execute(task,brief);
            var errors=storyboards.validatePlanned(task.userId(),brief,output.draft());
            String draft=output.draft()==null ? null : json.writeValueAsString(output.draft());
            repository.finish(task,output,draft,json.writeValueAsString(errors),output.errorCode()==null && errors.isEmpty(),System.currentTimeMillis());
        } catch (DeepSeekStoryboardPlanner.PlanningFailure failure) { repository.fail(task,failure.status,failure.code,System.currentTimeMillis()); }
        catch (com.example.server.exception.BusinessException blocked) { repository.fail(task,"BLOCKED","CALL_DISABLED",System.currentTimeMillis()); }
        catch (Exception unknown) {
            // Includes failure to persist an already returned response; never call the model again to repair storage.
            repository.fail(task,"UNKNOWN","RESULT_NOT_CONFIRMED",System.currentTimeMillis());
        }
    }
}

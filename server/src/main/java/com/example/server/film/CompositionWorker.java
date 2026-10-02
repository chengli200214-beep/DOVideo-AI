package com.example.server.film;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CompositionWorker {
    private static final Logger log=LoggerFactory.getLogger(CompositionWorker.class);
    private final CompositionRepository repository;
    private final CompositionRenderer renderer;
    private final ObjectMapper json;
    public CompositionWorker(CompositionRepository repository,CompositionRenderer renderer,ObjectMapper json) { this.repository=repository; this.renderer=renderer; this.json=json; }
    @Scheduled(fixedDelayString="${composition.worker-delay-ms:2000}",initialDelayString="${composition.worker-initial-delay-ms:0}")
    public void tick() {
        for(var task:repository.due(System.currentTimeMillis())) try { process(task.id()); } catch(Exception e) { log.warn("composition_worker_error task={} type={}",task.id(),e.getClass().getSimpleName()); }
    }
    public void process(String id) throws Exception {
        var task=repository.claim(id,System.currentTimeMillis()); if(task==null) return;
        if(task.attempts()>3) { repository.finish(task,"FAILED",null,null,"RENDER_ATTEMPTS_EXHAUSTED",0,System.currentTimeMillis()); return; }
        try {
            var result=renderer.render(task,json.readValue(task.snapshotJson(),FilmSpec.class));
            repository.finish(task,"SUCCEEDED",result.artifact(),result.metadataJson(),null,0,System.currentTimeMillis());
        } catch(Exception e) {
            String code=e instanceof CompositionRenderer.Failure failure?failure.code():"COMPOSITION_FAILED";
            log.warn("composition_step_failed task={} attempt={} code={} type={}",id,task.attempts(),code,e.getClass().getSimpleName());
            boolean terminal=task.attempts()>=3 || code.equals("INPUT_INTEGRITY_FAILED") || code.equals("INVALID_VIDEO");
            repository.finish(task,terminal?"FAILED":"QUEUED",null,null,code,System.currentTimeMillis()+5000L*task.attempts(),System.currentTimeMillis());
        }
    }
}

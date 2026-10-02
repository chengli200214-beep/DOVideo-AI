package com.example.server.controller;
import com.example.server.common.Result;
import com.example.server.film.*;
import com.example.server.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/generation")
public class EvaluationController {
    private final QualityEvaluationService service;
    private final FfmpegCompositionRenderer renderer;
    public EvaluationController(QualityEvaluationService service,FfmpegCompositionRenderer renderer) { this.service=service; this.renderer=renderer; }
    @GetMapping("/runtime") public Result<java.util.Map<String,Object>> runtime() { return Result.ok(renderer.health()); }
    @GetMapping("/evaluation-cases") public Result<java.util.List<QualityEvaluationService.EvaluationCase>> cases() { return Result.ok(service.cases()); }
    @GetMapping("/projects/{id}/evaluation-report") public Result<QualityEvaluationService.Report> report(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id) throws Exception { return Result.ok(service.report(user,id)); }
    @GetMapping("/projects/{id}/evaluation-report.csv") public ResponseEntity<String> csv(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id) throws Exception {
        return ResponseEntity.ok().header("Content-Type","text/csv; charset=utf-8").header("Content-Disposition","attachment; filename=project-evaluation.csv").body(service.csv(service.report(user,id)));
    }
    @PostMapping("/projects/{id}/reviews/{version}") public Result<QualityEvaluationService.Review> review(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id,@PathVariable String version,@RequestBody QualityEvaluationService.ReviewInput input) {
        return Result.ok(service.review(user,id,version,input));
    }
}

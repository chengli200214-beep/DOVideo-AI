package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.generation.GenerationRequest;
import com.example.server.generation.GenerationService;
import com.example.server.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/generation/tasks")
public class GenerationController {
    private final GenerationService service;
    public GenerationController(GenerationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Result<GenerationService.Submission>> submit(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody GenerationRequest request) throws Exception {
        var submission = service.submit(user, key, request);
        return ResponseEntity.status(submission.reused() ? 200 : 202).body(Result.ok(submission));
    }
    @GetMapping("/{id}")
    public Result<GenerationService.View> get(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
                                             @PathVariable String id) { return Result.ok(service.get(user, id)); }
    @GetMapping
    public Result<java.util.List<GenerationService.View>> recent(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user) {
        return Result.ok(service.recent(user));
    }
    @GetMapping("/{id}/trace")
    public Result<GenerationService.Trace> trace(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
                                               @PathVariable String id) throws Exception { return Result.ok(service.trace(user, id)); }
    @GetMapping("/{id}/artifact")
    public Result<String> artifact(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
                                    @PathVariable String id) { return Result.ok(service.artifact(user, id)); }
    @PostMapping("/{id}/retry")
    public Result<GenerationService.View> retry(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
                                              @PathVariable String id) { return Result.ok(service.retry(user, id)); }
    @PostMapping("/{id}/reconcile")
    public Result<GenerationService.View> reconcile(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
            @PathVariable String id, @RequestBody ReconcileRequest request) {
        return Result.ok(service.reconcile(user, id, request.requestId()));
    }
    public record ReconcileRequest(String requestId) { }
}

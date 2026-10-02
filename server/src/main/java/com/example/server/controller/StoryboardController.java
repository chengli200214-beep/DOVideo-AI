package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.service.AuthService;
import com.example.server.storyboard.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/generation/projects")
public class StoryboardController {
    private final StoryboardService service;
    public StoryboardController(StoryboardService service) { this.service = service; }
    @PostMapping
    public ResponseEntity<Result<StoryboardService.Created>> create(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CreativeBrief brief) throws Exception {
        var result = service.create(user, key, brief);
        return ResponseEntity.status(result.reused() ? 200 : 201).body(Result.ok(result));
    }
    @GetMapping
    public Result<java.util.List<StoryboardService.Summary>> recent(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user) throws Exception {
        return Result.ok(service.recent(user));
    }
    @GetMapping("/{id}")
    public Result<StoryboardService.View> get(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user, @PathVariable String id) throws Exception {
        return Result.ok(service.get(user, id));
    }
    @GetMapping("/{id}/revisions")
    public Result<StoryboardService.History> history(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user, @PathVariable String id) throws Exception {
        return Result.ok(service.history(user, id));
    }
    @PostMapping("/{id}/revisions")
    public Result<StoryboardService.View> edit(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user, @PathVariable String id,
            @RequestBody EditRequest request) throws Exception {
        return Result.ok(service.edit(user, id, request.expectedRevision(), request.draft()));
    }
    @PostMapping("/{id}/confirm")
    public Result<StoryboardService.View> confirm(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user, @PathVariable String id,
            @RequestBody ConfirmRequest request) throws Exception {
        return Result.ok(service.confirm(user, id, request.expectedRevision()));
    }
    public record EditRequest(int expectedRevision, StoryboardDraft draft) { }
    public record ConfirmRequest(int expectedRevision) { }
}

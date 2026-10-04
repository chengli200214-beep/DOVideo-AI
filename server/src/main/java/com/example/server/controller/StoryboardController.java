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
    @GetMapping("/page")
    public Result<StoryboardService.Page> page(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
            @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="false") boolean archived,
            @RequestParam(defaultValue="20") int limit, @RequestParam(required=false) Long beforeUpdatedAt,
            @RequestParam(required=false) String beforeId) throws Exception {
        return Result.ok(service.page(user, q, archived, limit, beforeUpdatedAt, beforeId));
    }
    @PostMapping("/{id}/archive")
    public Result<StoryboardService.View> archive(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
            @PathVariable String id, @RequestBody ArchiveRequest input) throws Exception {
        if (input == null || input.archived() == null) throw new IllegalArgumentException("请明确归档或恢复项目");
        return Result.ok(service.archive(user, id, input.archived()));
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
    public record ArchiveRequest(Boolean archived) { }
}

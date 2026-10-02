package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.service.AuthService;
import com.example.server.storyboard.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/generation")
public class StoryboardModelController {
    private final StoryboardModelService service;
    public StoryboardModelController(StoryboardModelService service) { this.service=service; }
    @GetMapping("/storyboard-model/runtime")
    public Result<StoryboardModelService.RuntimeView> runtime(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user) { return Result.ok(service.runtime()); }
    @PostMapping("/projects/{project}/planning")
    public ResponseEntity<Result<StoryboardModelRepository.Task>> submit(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String project,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Submit request) throws Exception {
        return ResponseEntity.accepted().body(Result.ok(service.submit(user,project,key,request.expectedRevision())));
    }
    @GetMapping("/projects/{project}/planning")
    public Result<java.util.List<StoryboardModelRepository.Task>> recent(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String project) throws Exception { return Result.ok(service.recent(user,project)); }
    @GetMapping("/projects/{project}/planning/{id}")
    public Result<StoryboardModelRepository.Task> get(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String project,@PathVariable String id) throws Exception { return Result.ok(service.get(user,project,id)); }
    public record Submit(int expectedRevision) { }
}

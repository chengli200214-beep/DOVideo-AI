package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.service.AuthService;
import com.example.server.storyboard.ShotGenerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/generation/projects/{id}")
public class ShotGenerationController {
    private final ShotGenerationService service;
    public ShotGenerationController(ShotGenerationService service) { this.service = service; }
    @PostMapping("/budget")
    public Result<ShotGenerationService.Budget> budget(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user, @PathVariable String id,
            @RequestBody ShotGenerationService.BudgetInput input) { return Result.ok(service.configure(user, id, input)); }
    @GetMapping("/generation-quote")
    public Result<ShotGenerationService.Quote> quote(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user, @PathVariable String id,
            @RequestParam int revision) throws Exception { return Result.ok(service.quote(user, id, revision)); }
    @GetMapping("/generations")
    public Result<ShotGenerationService.Overview> overview(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user, @PathVariable String id) {
        return Result.ok(service.overview(user, id));
    }
    @PostMapping("/generations")
    public ResponseEntity<Result<ShotGenerationService.Submitted>> submit(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user, @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody ShotGenerationService.SubmitInput input) throws Exception {
        var result = service.submit(user, id, key, input);
        return ResponseEntity.status(result.reused() ? 200 : 201).body(Result.ok(result));
    }
}

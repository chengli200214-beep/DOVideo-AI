package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.generation.GenerationAssetLifecycleService;
import com.example.server.service.AuthService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/generation/assets")
public class GenerationAssetLibraryController {
    private final GenerationAssetLifecycleService service;
    public GenerationAssetLibraryController(GenerationAssetLifecycleService service) { this.service=service; }
    @GetMapping public Result<GenerationAssetLifecycleService.Page> page(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) Long beforeCreatedAt,@RequestParam(required=false) String beforeId) {
        return Result.ok(service.page(user,limit,beforeCreatedAt,beforeId));
    }
    @DeleteMapping("/{id}") public Result<GenerationAssetLifecycleService.Cleanup> remove(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id) {
        return Result.ok(service.remove(user,id));
    }
    @GetMapping("/cleanup") public Result<java.util.List<GenerationAssetLifecycleService.Cleanup>> cleanup(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user) {
        return Result.ok(service.cleanups(user));
    }
    @PostMapping("/cleanup/{id}/retry") public Result<GenerationAssetLifecycleService.Cleanup> retry(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,@PathVariable String id) {
        return Result.ok(service.retry(user,id));
    }
}

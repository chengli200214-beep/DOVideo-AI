package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.generation.GenerationAssetService;
import com.example.server.generation.GenerationService;
import com.example.server.service.AuthService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/generation")
public class GenerationInputController {
    private final GenerationService tasks;
    private final GenerationAssetService assets;
    public GenerationInputController(GenerationService tasks, GenerationAssetService assets) {
        this.tasks = tasks; this.assets = assets;
    }
    @GetMapping("/capabilities")
    public Result<java.util.List<GenerationService.ModelCapability>> capabilities() { return Result.ok(tasks.capabilities()); }
    @PostMapping("/assets")
    public Result<GenerationAssetService.Asset> upload(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
            @RequestPart("file") MultipartFile file) throws Exception {
        if (file.getSize() > 5 * 1024 * 1024) throw new IllegalArgumentException("参考图片最大 5 MiB");
        try (var input = file.getInputStream()) {
            return Result.ok(assets.upload(user, file.getContentType(), input.readNBytes(5 * 1024 * 1024 + 1)));
        }
    }
    @GetMapping("/assets/{id}")
    public Result<GenerationAssetService.Asset> get(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
            @PathVariable String id) { return Result.ok(assets.owned(user, id)); }
    @GetMapping("/assets/{id}/preview")
    public Result<String> preview(@RequestAttribute(AuthService.REQUEST_USER_ID) Long user,
            @PathVariable String id) { return Result.ok(assets.readableUrl(user, id)); }
}
